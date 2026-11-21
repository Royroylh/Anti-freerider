package af;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Evidence-grounded Q&A. By default there is no model at all: the server does
 * deterministic evidence retrieval and says so. A local OpenAI-compatible model
 * can be configured with AF_AI_ENDPOINT (loopback only). Chat messages, private
 * reminders and votes are never part of the context.
 */
final class Evidence {
    private Evidence() {}

    record Citation(String id, String label, int projectId, int taskId, Integer submissionId,
                    Integer dateChangeId, Integer supportId, String url) {
        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("label", label);
            o.addProperty("type", submissionId != null ? "submission" : dateChangeId != null ? "date_change"
                    : supportId != null ? "support" : "task");
            o.addProperty("projectId", projectId);
            o.addProperty("taskId", taskId);
            o.addProperty("submissionId", submissionId);
            o.addProperty("dateChangeId", dateChangeId);
            o.addProperty("supportId", supportId);
            o.addProperty("url", url);
            return o;
        }
    }

    record TaskEvidence(Tasks.TaskRow task, int score, double bestMatch) {}

    static JsonObject status() {
        String endpoint = env("AF_AI_ENDPOINT");
        boolean testDouble = "test-double".equalsIgnoreCase(env("AF_AI_MODE"));
        boolean configured = endpoint != null && !endpoint.isBlank();
        boolean loopback = configured && isLoopback(endpoint);
        JsonObject o = new JsonObject();
        String mode;
        String label;
        String note;
        if (testDouble) {
            mode = "test-double";
            label = "测试替身（非真实推理）";
            note = "AF_AI_MODE=test-double：回答与引用来自确定性测试替身，不代表模型判断。";
        } else if (configured && loopback) {
            mode = "local-model";
            label = "本地模型（可配置）";
            note = "AF_AI_ENDPOINT 指向本机模型；若调用失败会自动降级为证据检索回退。";
        } else if (configured && !loopback) {
            mode = "evidence-retrieval-fallback";
            label = "证据检索回退（模型端点被拒绝）";
            note = "只允许本机回环地址。项目数据未发送到外部模型。";
        } else {
            mode = "evidence-retrieval-fallback";
            label = "证据检索回退（未配置模型）";
            note = "当前没有本地模型；系统只做确定性证据检索，不是模型推理。所有项目数据留在本机。";
        }
        o.addProperty("mode", mode);
        o.addProperty("label", label);
        o.addProperty("note", note);
        o.addProperty("modelConfigured", configured && loopback && !testDouble);
        o.addProperty("modelName", env("AF_AI_MODEL"));
        o.addProperty("testDouble", testDouble);
        o.addProperty("externalDataSending", false);
        return o;
    }

    static JsonObject ask(Auth.User user, int projectId, String question, int taskId, int submissionId) throws Exception {
        if (question == null || question.trim().isEmpty()) throw new ApiException(400, "Ask a question");
        if (question.length() > 1000) throw new ApiException(400, "Question is too long");
        try (Connection c = Db.open()) {
            Auth.requireMember(c, projectId, user.id());
            Contribution.Result contribution = Contribution.calculate(c, projectId);
            List<Tasks.TaskRow> tasks = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM tasks WHERE project_id=? ORDER BY id")) {
                ps.setInt(1, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) tasks.add(Tasks.load(c, rs.getInt(1)));
                }
            }
            String q = question.toLowerCase(Locale.ROOT);
            String intent = intent(q);
            Target target = resolveTarget(c, tasks, q, intent, taskId, submissionId);
            List<TaskEvidence> ranked = target.error() != null || "criteria".equals(intent)
                    ? List.of() : rank(q, target.task() == null ? tasks : List.of(target.task()));
            if (!ranked.isEmpty() && target.task() != null) {
                TaskEvidence only = ranked.get(0);
                ranked = List.of(new TaskEvidence(only.task(), only.score(), Math.max(1, only.bestMatch())));
            }
            JsonObject fallback;
            if (target.error() != null) {
                fallback = result(target.error(), new JsonArray());
            } else {
                fallback = switch (intent) {
                    case "who" -> whoAnswer(c, projectId, ranked, q);
                    case "criteria" -> criteriaAnswer(c, projectId, target.task(), target.submissionId());
                    case "delay" -> delayAnswer(c, projectId, ranked, q);
                    case "contribution" -> contributionAnswer(c, projectId, contribution);
                    default -> generalAnswer(c, projectId, ranked);
                };
            }
            JsonObject aiStatus = status();
            String mode = aiStatus.get("mode").getAsString();
            String answer = fallback.get("answer").getAsString();
            String modelError = "";
            if ("test-double".equals(mode)) {
                answer = "【测试替身】以下回答由确定性测试替身生成，用于验证引用链路，不是真实模型推理。\n\n" + answer;
            } else if ("local-model".equals(mode) && target.error() == null && !"contribution".equals(intent)) {
                try {
                    String modelAnswer = callLocalModel(question, fallback);
                    if (modelAnswer != null && !modelAnswer.isBlank()) {
                        // The model explains; deterministic retrieval remains attached so every
                        // quality statement still has a task/version citation.
                        answer = modelAnswer.trim()
                                + "\n\n系统证据（确定性检索，不是模型判断）：\n" + fallback.get("answer").getAsString()
                                + "\n\n引用证据：\n" + citationLines(fallback.getAsJsonArray("citations"));
                    }
                } catch (Exception ex) {
                    modelError = "本地模型调用失败，已降级为证据检索回退：" + ex.getMessage();
                    mode = "evidence-retrieval-fallback";
                }
            }
            if ("local-model".equals(mode) && (target.error() != null || "contribution".equals(intent))) {
                mode = "evidence-retrieval-fallback";
            }
            JsonObject out = new JsonObject();
            out.addProperty("answer", answer);
            out.addProperty("mode", mode);
            out.addProperty("modeLabel", "local-model".equals(mode) ? "本地模型" : "evidence-retrieval-fallback".equals(mode)
                    ? (modelError.isEmpty() ? "证据检索回退" : "证据检索回退（模型失败）") : "测试替身");
            out.addProperty("modelError", modelError);
            out.addProperty("intent", intent);
            out.add("citations", fallback.getAsJsonArray("citations"));
            out.add("aiStatus", aiStatus);
            out.addProperty("votesIncluded", false);
            out.addProperty("chatIncluded", false);
            out.add("contributionSummary", Contribution.toJson(contribution));
            return out;
        }
    }

    private record Target(Tasks.TaskRow task, int submissionId, String error) {}

    private static final java.util.regex.Pattern QUOTED_TITLE =
            java.util.regex.Pattern.compile("[《「『“\"]([^》」』”\"]+)[》」』”\"]");
    private static final java.util.regex.Pattern VERSION =
            java.util.regex.Pattern.compile("(?i)\\bv([0-9]{1,9})\\b");

    private static Target resolveTarget(Connection c, List<Tasks.TaskRow> tasks, String question, String intent,
                                        int openTask, int openSubmission) throws SQLException {
        var title = QUOTED_TITLE.matcher(question);
        boolean quoted = title.find();
        boolean criteria = "criteria".equals(intent);
        boolean demonstrative = question.matches("(?s).*(这份|这篇|这项|这个任务|本版|this (report|deliverable|task|version)).*");
        if (!quoted && !criteria) return new Target(null, 0, null);
        Tasks.TaskRow task = null;
        if (!quoted && demonstrative) {
            task = tasks.stream().filter(t -> t.id() == openTask).findFirst().orElse(null);
            if (task == null) return new Target(null, 0, "请先打开具体成果和版本，或在问题中写出成果名称。");
        } else {
            String named = quoted ? title.group(1) : criteriaSubject(question);
            if (named.isBlank()) {
                return new Target(null, 0, "请指定要核对的成果名称，或打开对应任务和版本。");
            }
            String query = normalizeTitle(named);
            if (query.isEmpty()) return new Target(null, 0, "请指定要核对的成果名称。");
            List<Tasks.TaskRow> matches = tasks.stream().filter(t -> titleMatches(t, query, true)).toList();
            if (matches.isEmpty() && query.length() >= 2) {
                matches = tasks.stream().filter(t -> titleMatches(t, query, false)).toList();
            }
            if (matches.isEmpty()) return new Target(null, 0, "在当前项目中未找到成果「" + named + "」。");
            if (matches.size() > 1) {
                String choices = String.join("；", matches.stream()
                        .map(t -> t.title() + "（" + t.branchName() + " · 任务 #" + t.id() + "）").toList());
                return new Target(null, 0, "找到多个候选，请打开其中一项再问“这份报告”：" + choices);
            }
            task = matches.get(0);
        }
        int selected = task.id() == openTask ? openSubmission : 0;
        var version = VERSION.matcher(question);
        boolean versionNamed = version.find();
        if (criteria && (versionNamed || selected > 0)) {
            boolean exactId = !versionNamed;
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM submissions WHERE task_id=? AND "
                    + (exactId ? "id=?" : "version=?"))) {
                ps.setInt(1, task.id());
                ps.setInt(2, exactId ? selected : Integer.parseInt(version.group(1)));
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return new Target(null, 0, "未找到所指定的成果版本，请打开对应版本后重试。");
                    selected = rs.getInt(1);
                }
            }
        }
        return new Target(task, selected, null);
    }

    private static boolean titleMatches(Tasks.TaskRow task, String query, boolean exact) {
        for (String alias : task.title().split("\\s+/\\s+")) {
            String title = normalizeTitle(alias);
            if (exact ? title.equals(query) : title.contains(query)) return true;
        }
        return false;
    }

    private static String normalizeTitle(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
    }

    private static String criteriaSubject(String question) {
        return VERSION.matcher(question).replaceAll("")
                .replaceFirst("^(请问|帮我看看|帮我核对|please check|which criteria did|what criteria did)\\s*", "")
                .replaceFirst("(达到|符合|满足|有哪|的验收标准|达标|\\s+(?:met|meet|criteria)\\b).*$", "")
                .replaceFirst("(是否|能否|有没有)[，。？！?\\s]*$", "")
                .replaceFirst("[，。？！?\\s]+$", "").strip();
    }

    private static String intent(String q) {
        if (q.contains("标准") || q.contains("达到") || q.contains("criteria") || q.contains("达标")
                || q.matches("(?s).*\\bmet\\b.*")) return "criteria";
        if (q.contains("谁") || q.contains("who") || q.contains("哪个人") || q.contains("哪位")) return "who";
        if (q.contains("延期") || q.contains("延误") || q.contains("为什么") || q.contains("原因")
                || q.contains("delay") || q.contains("late")) return "delay";
        if (q.contains("贡献") || q.contains("占比") || q.contains("得分") || q.contains("分数")
                || q.contains("contribution") || q.contains("percent")) return "contribution";
        return "general";
    }

    private static List<TaskEvidence> rank(String q, List<Tasks.TaskRow> tasks) throws SQLException {
        List<TaskEvidence> out = new ArrayList<>();
        try (Connection c = Db.open()) {
            for (Tasks.TaskRow task : tasks) {
                StringBuilder text = new StringBuilder();
                text.append(task.title()).append(' ').append(task.description()).append(' ')
                        .append(task.branchName()).append(' ').append(task.expectedOutput()).append(' ')
                        .append(task.criteria());
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT content,artifact_text,criteria_snapshot FROM submissions WHERE task_id=? ORDER BY version DESC LIMIT 1")) {
                    ps.setInt(1, task.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            text.append(' ').append(nullSafe(rs.getString(1))).append(' ')
                                    .append(nullSafe(rs.getString(2))).append(' ')
                                    .append(nullSafe(rs.getString(3)));
                        }
                    }
                }
                int score = 0;
                try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM task_scores WHERE task_id=?")) {
                    ps.setInt(1, task.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) score = 1;
                    }
                }
                out.add(new TaskEvidence(task, score, similarity(q, text.toString().toLowerCase(Locale.ROOT))));
            }
        }
        out.sort(Comparator.comparingDouble(TaskEvidence::bestMatch).reversed()
                .thenComparing(te -> -te.score()));
        return out;
    }

    private static double similarity(String q, String text) {
        String cleaned = q.replaceAll("[\\p{Punct}\\s]+", "");
        String[] stop = {"谁", "完成", "了", "这份", "报告", "达到", "哪些", "标准", "为什么", "延期", "的", "是",
                "哪", "个", "人", "请问", "一下", "怎么", "如何", "呢", "吗", "数据", "收集", "任务", "工作"};
        List<String> grams = new ArrayList<>();
        for (int len = 2; len <= 6; len++) {
            for (int i = 0; i + len <= cleaned.length(); i++) {
                String gram = cleaned.substring(i, i + len);
                boolean skip = false;
                for (String s : stop) if (s.contains(gram) || gram.contains(s)) { skip = true; break; }
                if (!skip) grams.add(gram);
            }
        }
        double best = 0;
        for (String gram : grams) {
            if (text.contains(gram)) best = Math.max(best, gram.length());
        }
        // Fall back to the coarsest task words when the question is short.
        for (String word : cleaned.split("")) {
            if (word.length() == 1) continue;
        }
        return best;
    }

    private static JsonObject whoAnswer(Connection c, int projectId, List<TaskEvidence> ranked, String q) throws SQLException {
        StringBuilder answer = new StringBuilder();
        JsonArray citations = new JsonArray();
        int added = 0;
        double topMatch = ranked.isEmpty() ? 0 : ranked.get(0).bestMatch();
        for (TaskEvidence te : ranked) {
            if (te.bestMatch() <= 0 || te.bestMatch() < topMatch) continue;
            Tasks.TaskRow task = te.task();
            if (te.score() == 1) {
                Integer submissionId = null;
                Integer version = null;
                String acceptedAt = null;
                int points = 0;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT ts.points,ts.accepted_at,s.id,s.version FROM task_scores ts " +
                                "JOIN submissions s ON s.id=ts.submission_id WHERE ts.task_id=?")) {
                    ps.setInt(1, task.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            points = rs.getInt(1);
                            acceptedAt = rs.getString(2);
                            submissionId = rs.getInt(3);
                            version = rs.getInt(4);
                        }
                    }
                }
                answer.append("根据已验收记录，「").append(task.title()).append("」由 ");
                JsonArray authors = submissionAuthors(c, submissionId);
                for (int i = 0; i < authors.size(); i++) {
                    if (i > 0) answer.append("、");
                    JsonObject a = authors.get(i).getAsJsonObject();
                    answer.append(a.get("name").getAsString()).append("（")
                            .append(fmt(a.get("sharePct").getAsDouble())).append("%）");
                }
                answer.append(" 完成；验收版本 v").append(version).append("，").append(points)
                        .append(" 分，验收日期 ").append(acceptedAt).append("。");
                answer.append(" 这是已验收记录，不是对未验收工作的评价。\n");
                citations.add(citation("E" + (citations.size() + 1), task.title() + " · 已验收版本 v" + version,
                        projectId, task.id(), submissionId, null, null));
                added++;
            } else if (te.bestMatch() > 0) {
                Integer submissionId = null;
                Integer version = null;
                String status = null;
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id,version,status FROM submissions WHERE task_id=? ORDER BY version DESC LIMIT 1")) {
                    ps.setInt(1, task.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            submissionId = rs.getInt(1);
                            version = rs.getInt(2);
                            status = rs.getString(3);
                        }
                    }
                }
                if (submissionId != null) {
                    answer.append("「").append(task.title()).append("」已有版本 v").append(version)
                            .append("，当前状态：").append(statusLabel(status))
                            .append("；尚无以该版本为依据的计分。\n");
                    citations.add(citation("E" + (citations.size() + 1), task.title() + " · 版本 v" + version,
                            projectId, task.id(), submissionId, null, null));
                } else {
                    answer.append("「").append(task.title()).append("」目前没有提交物，无法据此判断谁完成了该工作。\n");
                    citations.add(citation("E" + (citations.size() + 1), task.title() + " · 任务分工",
                            projectId, task.id(), null, null, null));
                }
                added++;
            }
            if (added >= 3) break;
        }
        if (added == 0) {
            answer.append("当前项目材料中没有找到与该问题对应、且已验收的成果。缺少：可对应的任务标题或提交物。请打开任务列表确认，或补充成果后再问。");
            citations.add(citation("E1", "项目任务列表", projectId, 0, null, null, null));
        }
        return result(answer.toString(), citations);
    }

    private static JsonObject criteriaAnswer(Connection c, int projectId, Tasks.TaskRow task, int selectedSubmission)
            throws SQLException {
        StringBuilder answer = new StringBuilder();
        JsonArray citations = new JsonArray();
        int submissionId = 0;
        int version = 0;
        String artifact = "";
        String criteria = task.criteria();
        String status = null;
        boolean pdf = false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id,version,artifact_text,criteria_snapshot,status,pdf IS NOT NULL FROM submissions " +
                        "WHERE task_id=? AND (?=0 OR id=?) ORDER BY version DESC LIMIT 1")) {
            ps.setInt(1, task.id());
            ps.setInt(2, selectedSubmission);
            ps.setInt(3, selectedSubmission);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    submissionId = rs.getInt(1);
                    version = rs.getInt(2);
                    artifact = nullSafe(rs.getString(3));
                    criteria = rs.getString(4);
                    status = rs.getString(5);
                    pdf = rs.getBoolean(6);
                }
            }
        }
        answer.append("按「").append(task.title()).append("」")
                .append(submissionId == 0 ? "约定" : "v" + version + " 冻结")
                .append("的验收标准逐条核对：\n");
        if (pdf) answer.append("请打开该版 PDF 查看附件原文。问答只核对随版本保存的正文文本。\n");
        String[] lines = criteria.split("\\r?\\n");
        int index = 1;
        for (String line : lines) {
            String criterion = line.trim().replaceFirst("^[-*\\d.、) ]+", "");
            if (criterion.isEmpty()) continue;
            String evidence = findEvidence(criterion, artifact);
            answer.append(index).append(". 标准：").append(criterion).append("\n");
            if (evidence == null) {
                answer.append("   提交物中未找到明确对应内容。缺少：能说明该标准的材料。\n");
            } else {
                answer.append("   提交物依据：").append(evidence).append("\n");
            }
            index++;
        }
        if (submissionId == 0) {
            answer.append("当前没有提交物，因此无法判断是否达到上述标准。");
            citations.add(citation("E1", task.title() + " · 任务验收标准", projectId, task.id(), null, null, null));
        } else {
            answer.append("验收记录：版本 v").append(version).append(" 当前状态为「").append(statusLabel(status))
                    .append("」。只依据上述提交物与冻结标准，不评价群聊内容。");
            citations.add(citation("E1", task.title() + " · 冻结验收标准 v" + version, projectId, task.id(), submissionId, null, null));
            citations.add(citation("E2", task.title() + " · 提交物 v" + version, projectId, task.id(), submissionId, null, null));
            citations.add(citation("E3", task.title() + " · 验收记录 v" + version + " · " + statusLabel(status),
                    projectId, task.id(), submissionId, null, null));
        }
        return result(answer.toString(), citations);
    }

    private static String findEvidence(String criterion, String artifact) {
        String source = artifact.trim();
        if (source.isEmpty()) return null;
        String cleaned = criterion.replaceAll("[\\p{Punct}\\s]+", "");
        String best = null;
        for (int len = Math.min(6, cleaned.length()); len >= 2; len--) {
            for (int i = 0; i + len <= cleaned.length(); i++) {
                String gram = cleaned.substring(i, i + len);
                int at = source.indexOf(gram);
                if (at >= 0) {
                    int start = Math.max(0, at - 25);
                    int end = Math.min(source.length(), at + gram.length() + 45);
                    best = (start > 0 ? "…" : "") + source.substring(start, end).replace("\n", " ") + (end < source.length() ? "…" : "");
                    break;
                }
            }
            if (best != null) break;
        }
        return best;
    }

    private static JsonObject delayAnswer(Connection c, int projectId, List<TaskEvidence> ranked, String q) throws SQLException {
        StringBuilder answer = new StringBuilder();
        JsonArray citations = new JsonArray();
        Integer taskId = ranked.isEmpty() ? null : ranked.get(0).task().id();
        int added = 0;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT d.id,d.task_id,d.old_deadline,d.new_deadline,d.reason,d.status,d.created_at,d.decided_at," +
                        "d.decision_note,u.name AS requester,t.title,t.checkpoint_at FROM date_changes d " +
                        "JOIN users u ON u.id=d.requested_by JOIN tasks t ON t.id=d.task_id WHERE d.project_id=? " +
                        "AND (? IS NULL OR d.task_id=?) ORDER BY d.id")) {
            ps.setInt(1, projectId);
            if (taskId == null) ps.setNull(2, java.sql.Types.INTEGER);
            else ps.setInt(2, taskId);
            if (taskId == null) ps.setNull(3, java.sql.Types.INTEGER);
            else ps.setInt(3, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    answer.append("「").append(rs.getString("title")).append("」的记录显示：原截止 ")
                            .append(rs.getString("old_deadline")).append("，申请改为 ")
                            .append(rs.getString("new_deadline")).append("；原因：")
                            .append(rs.getString("reason")).append("；结果：")
                            .append("approved".equals(rs.getString("status")) ? "已同意" : "rejected".equals(rs.getString("status")) ? "未同意" : "待处理")
                            .append("（").append(rs.getString("requester")).append(" 于 ")
                            .append(rs.getString("created_at")).append(" 提出")
                            .append(rs.getString("decided_at") == null ? "" : "，" + rs.getString("decided_at") + " 处理")
                            .append("）。");
                    if (!rs.getString("decision_note").isEmpty()) {
                        answer.append("处理说明：").append(rs.getString("decision_note"));
                    }
                    answer.append("\n");
                    citations.add(citation("E" + (citations.size() + 1), "延期记录 · " + rs.getString("title"),
                            projectId, rs.getInt("task_id"), null, rs.getInt("id"), null));
                    added++;
                }
            }
        }
        if (added == 0 && taskId != null) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT s.id,s.kind,s.body,s.needed_support,s.created_at,u.name,t.title FROM task_support s " +
                            "JOIN users u ON u.id=s.user_id JOIN tasks t ON t.id=s.task_id " +
                            "WHERE s.task_id=? ORDER BY s.id")) {
                ps.setInt(1, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        answer.append("没有找到正式延期记录；但「").append(rs.getString("title"))
                                .append("」有已记录的").append("blocker".equals(rs.getString("kind")) ? "阻碍" : "求助")
                                .append("：").append(rs.getString("body")).append("；所需支持：")
                                .append(rs.getString("needed_support")).append("（")
                                .append(rs.getString("name")).append("，").append(rs.getString("created_at"))
                                .append("）。\n");
                        citations.add(citation("E" + (citations.size() + 1), "阻碍/求助记录 · " + rs.getString("title"),
                                projectId, taskId, null, null, rs.getInt("id")));
                        added++;
                    }
                }
            }
        }
        if (added == 0) {
            answer.append("当前项目没有与该问题对应的已记录延期事件，也没有可引用的阻碍记录。缺少：正式延期申请或阻碍说明。");
            if (taskId != null) {
                citations.add(citation("E1", "任务记录", projectId, taskId, null, null, null));
            } else {
                citations.add(citation("E1", "项目任务列表", projectId, 0, null, null, null));
            }
        }
        answer.append("贡献数字不由模型猜测；如需计算，请查看结项报告中的系统复算。");
        return result(answer.toString(), citations);
    }

    private static JsonObject contributionAnswer(Connection c, int projectId, Contribution.Result contribution)
            throws SQLException {
        StringBuilder answer = new StringBuilder();
        JsonArray citations = new JsonArray();
        answer.append("贡献数字由系统从已验收记录计算，不由模型推断。项目完成度 ")
                .append(fmt(contribution.completionPct())).append("%（").append(contribution.acceptedPoints())
                .append(" / ").append(contribution.plannedPoints()).append(" 分）。\n");
        if (!contribution.hasAccepted()) {
            answer.append("暂无已验收贡献。");
        } else {
            for (Auth.User member : contribution.members()) {
                answer.append("- ").append(member.name()).append("：")
                        .append(fmt(contribution.memberPoints().getOrDefault(member.id(), 0.0))).append(" 分，占已验收贡献 ")
                        .append(fmt(contribution.memberSharePct(member.id()))).append("%\n");
            }
            answer.append("公式：个人分值 = 每项已验收任务分 × 冻结署名比例；个人占比 = 个人已验收分值 ÷ 全组已验收分值。");
        }
        for (Contribution.AcceptedTask task : contribution.acceptedTasks()) {
            citations.add(citation("E" + (citations.size() + 1), task.title() + " · 已验收版本 v" + task.version(),
                    projectId, task.taskId(), task.submissionId(), null, null));
        }
        if (citations.isEmpty()) citations.add(citation("E1", "项目任务列表", projectId, 0, null, null, null));
        return result(answer.toString(), citations);
    }

    private static JsonObject generalAnswer(Connection c, int projectId, List<TaskEvidence> ranked) throws SQLException {
        StringBuilder answer = new StringBuilder();
        JsonArray citations = new JsonArray();
        int added = 0;
        double topMatch = ranked.isEmpty() ? 0 : ranked.get(0).bestMatch();
        for (TaskEvidence te : ranked) {
            if (te.bestMatch() <= 0 || te.bestMatch() < topMatch) continue;
            Tasks.TaskRow task = te.task();
            answer.append("「").append(task.title()).append("」（").append(task.branchName()).append("）：负责人 ")
                    .append(task.assigneeName()).append("，计划 ").append(task.points()).append(" 分，检查点 ")
                    .append(task.checkpointAt()).append("。");
            Integer submissionId = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM submissions WHERE task_id=? ORDER BY version DESC LIMIT 1")) {
                ps.setInt(1, task.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) submissionId = rs.getInt(1);
                }
            }
            if (te.score() == 1) answer.append(" 该任务已有通过并计分的版本。");
            else if (submissionId != null) answer.append(" 已有提交物，可打开版本查看验收状态。");
            else answer.append(" 尚无提交物。");
            answer.append("\n");
            citations.add(citation("E" + (citations.size() + 1), task.title(), projectId, task.id(), submissionId, null, null));
            added++;
            if (added >= 4) break;
        }
        if (added == 0) {
            answer.append("当前项目材料中没有找到与问题相关的任务或提交物。缺少：能对应问题的任务、成果版本或事件记录。");
            citations.add(citation("E1", "项目任务列表", projectId, 0, null, null, null));
        }
        return result(answer.toString(), citations);
    }

    private static JsonArray submissionAuthors(Connection c, Integer submissionId) throws SQLException {
        JsonArray arr = new JsonArray();
        if (submissionId == null) return arr;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT name_snapshot,share_pct FROM submission_authors WHERE submission_id=? ORDER BY share_pct DESC")) {
            ps.setInt(1, submissionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject a = new JsonObject();
                    a.addProperty("name", rs.getString(1));
                    a.addProperty("sharePct", rs.getDouble(2));
                    arr.add(a);
                }
            }
        }
        return arr;
    }

    private static JsonObject result(String answer, JsonArray citations) {
        JsonObject o = new JsonObject();
        o.addProperty("answer", answer);
        o.add("citations", citations);
        return o;
    }

    private static JsonObject citation(String id, String label, int projectId, int taskId, Integer submissionId,
                                       Integer dateChangeId, Integer supportId) {
        StringBuilder url = new StringBuilder("/dashboard.html?project=").append(projectId);
        if (taskId > 0) url.append("&task=").append(taskId);
        if (submissionId != null) url.append("&submission=").append(submissionId).append("#submission-").append(submissionId);
        if (dateChangeId != null) url.append("&dateChange=").append(dateChangeId);
        if (supportId != null) url.append("&support=").append(supportId);
        return new Citation(id, label, projectId, taskId, submissionId, dateChangeId, supportId, url.toString()).json();
    }

    private static String citationLines(JsonArray citations) {
        StringBuilder sb = new StringBuilder();
        for (var e : citations) {
            JsonObject c = e.getAsJsonObject();
            sb.append("- [").append(c.get("id").getAsString()).append("] ")
                    .append(c.get("label").getAsString()).append("：").append(c.get("url").getAsString()).append("\n");
        }
        return sb.toString();
    }

    private static String callLocalModel(String question, JsonObject fallback) throws Exception {
        String endpoint = env("AF_AI_ENDPOINT");
        if (endpoint == null || !isLoopback(endpoint)) throw new IllegalStateException("model endpoint is not loopback");
        String model = env("AF_AI_MODEL");
        if (model == null || model.isBlank()) throw new IllegalStateException("AF_AI_MODEL is not configured");
        JsonObject payload = new JsonObject();
        payload.addProperty("model", model);
        JsonArray messages = new JsonArray();
        JsonObject system = new JsonObject();
        system.addProperty("role", "system");
        system.addProperty("content", "You explain only the supplied evidence. Cite evidence as [E1], [E2]. "
                + "Never invent facts, never mention votes, vote counts, voter identities or voting times. "
                + "If evidence is missing, say what is missing. Reply in the language of the question.");
        messages.add(system);
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", "Question: " + question + "\nEvidence JSON: " + fallback);
        messages.add(user);
        payload.add("messages", messages);
        payload.addProperty("temperature", 0.1);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString()));
        String key = env("AF_AI_API_KEY");
        if (key != null && !key.isBlank()) builder.header("Authorization", "Bearer " + key);
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
        return json.getAsJsonArray("choices").get(0).getAsJsonObject()
                .getAsJsonObject("message").get("content").getAsString();
    }

    private static boolean isLoopback(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            return "http".equalsIgnoreCase(uri.getScheme())
                    && ("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host)
                    || "[::1]".equals(host));
        } catch (Exception ex) {
            return false;
        }
    }

    private static String env(String key) {
        return System.getenv(key);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String fmt(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001) return String.valueOf((long) Math.rint(value));
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String statusLabel(String status) {
        if (status == null) return "无提交";
        return switch (status) {
            case "pending" -> "待确认";
            case "accepted" -> "已通过";
            case "rejected" -> "未通过";
            case "withdrawn" -> "已撤回";
            case "superseded" -> "已被新版取代";
            default -> status;
        };
    }
}
