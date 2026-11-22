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
            label = "Test double (simulated inference)";
            note = "AF_AI_MODE=test-double: Responses and citations come from a deterministic test double, no model is running.";
        } else if (configured && loopback) {
            mode = "local-model";
            label = "Local model (configured)";
            note = "AF_AI_ENDPOINT points to a local model; if the request fails, the service falls back to evidence retrieval.";
        } else if (configured && !loopback) {
            mode = "evidence-retrieval-fallback";
            label = "Evidence retrieval (model endpoint rejected)";
            note = "Only loopback endpoints are allowed. Project data stays on this device.";
        } else {
            mode = "evidence-retrieval-fallback";
            label = "Evidence retrieval (no model configured)";
            note = "No local model is configured; the system retrieves recorded evidence without running a model. All project data stays on this device.";
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
                answer = "[Test double]This deterministic response, checks the citation flow, without running a model.\n\n" + answer;
            } else if ("local-model".equals(mode) && target.error() == null && !"contribution".equals(intent)) {
                try {
                    String modelAnswer = callLocalModel(question, fallback);
                    if (modelAnswer != null && !modelAnswer.isBlank()) {
                        // The model explains; deterministic retrieval remains attached so every
                        // quality statement still has a task/version citation.
                        answer = modelAnswer.trim()
                                + "\n\nSystem evidence (deterministic retrieved without model inference): \n" + fallback.get("answer").getAsString()
                                + "\n\nCitations: \n" + citationLines(fallback.getAsJsonArray("citations"));
                    }
                } catch (Exception ex) {
                    modelError = "Local model request failed, switched to evidence retrieval: " + ex.getMessage();
                    mode = "evidence-retrieval-fallback";
                }
            }
            if ("local-model".equals(mode) && (target.error() != null || "contribution".equals(intent))) {
                mode = "evidence-retrieval-fallback";
            }
            JsonObject out = new JsonObject();
            out.addProperty("answer", answer);
            out.addProperty("mode", mode);
            out.addProperty("modeLabel", "local-model".equals(mode) ? "Local model" : "evidence-retrieval-fallback".equals(mode)
                    ? (modelError.isEmpty() ? "Evidence retrieval" : "Evidence retrieval (model request failed)") : "Test double");
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
            java.util.regex.Pattern.compile("[“‘\"]([^”’\"]+)[”’\"]");
    private static final java.util.regex.Pattern VERSION =
            java.util.regex.Pattern.compile("(?i)\\bv([0-9]{1,9})\\b");

    private static Target resolveTarget(Connection c, List<Tasks.TaskRow> tasks, String question, String intent,
                                        int openTask, int openSubmission) throws SQLException {
        var title = QUOTED_TITLE.matcher(question);
        boolean quoted = title.find();
        boolean criteria = "criteria".equals(intent);
        boolean demonstrative = question.matches("(?s).*\\bthis (report|deliverable|task|version)\\b.*");
        if (!quoted && !criteria) return new Target(null, 0, null);
        Tasks.TaskRow task = null;
        if (!quoted && demonstrative) {
            task = tasks.stream().filter(t -> t.id() == openTask).findFirst().orElse(null);
            if (task == null) return new Target(null, 0, "Open a specific deliverable and version, or include the title in the question.");
        } else {
            String named = quoted ? title.group(1) : criteriaSubject(question);
            if (named.isBlank()) {
                return new Target(null, 0, "Specify the deliverable to check, or open its task and version.");
            }
            String query = normalizeTitle(named);
            if (query.isEmpty()) return new Target(null, 0, "Specify the deliverable to check.");
            List<Tasks.TaskRow> matches = tasks.stream().filter(t -> titleMatches(t, query, true)).toList();
            if (matches.isEmpty() && query.length() >= 2) {
                matches = tasks.stream().filter(t -> titleMatches(t, query, false)).toList();
            }
            if (matches.isEmpty()) return new Target(null, 0, "No matching deliverable in this project: ‘" + named + "’.");
            if (matches.size() > 1) {
                String choices = String.join("; ", matches.stream()
                        .map(t -> t.title() + " (" + t.branchName() + " · Task #" + t.id() + ")").toList());
                return new Target(null, 0, "Multiple matches, open one and ask about that version: " + choices);
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
                    if (!rs.next()) return new Target(null, 0, "The requested version was not found, open the version and try again.");
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
                .replaceFirst("^(?:please check|(?:which|what) criteria (?:did|does|do)|does|did)\\s+", "")
                .replaceFirst("\\s+(?:met|meet|meets|satisfy|satisfies|criteria)\\b.*$", "")
                .replaceFirst("[?.!]+$", "").strip();
    }

    private static String intent(String q) {
        if (q.matches("(?s).*\\b(criteria|criterion|met)\\b.*")) return "criteria";
        if (q.matches("(?s).*\\bwho\\b.*")) return "who";
        if (q.matches("(?s).*\\b(delay|delayed|extension|late|postponed)\\b.*")) return "delay";
        if (q.matches("(?s).*\\b(contributions?|shares?|scores?|points?|percentages?|percent)\\b.*")) return "contribution";
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
                out.add(new TaskEvidence(task, score, 2 * similarity(q, task.title().toLowerCase(Locale.ROOT))
                        + similarity(q, text.toString().toLowerCase(Locale.ROOT))));
            }
        }
        out.sort(Comparator.comparingDouble(TaskEvidence::bestMatch).reversed()
                .thenComparing(te -> -te.score()));
        return out;
    }

    private static final java.util.Set<String> QUERY_STOP_WORDS = java.util.Set.of("a", "an", "the", "of", "was", "is", "does", "did", "who",
                "why", "what", "which", "completed", "delayed", "task", "this", "work", "please");

    private static double similarity(String q, String text) {
        var haystack = new java.util.HashSet<>(List.of(text.split("[^\\p{L}\\p{N}]+")));
        var terms = new java.util.HashSet<>(List.of(q.split("[^\\p{L}\\p{N}]+")));
        return terms.stream().filter(term -> !term.isEmpty() && !QUERY_STOP_WORDS.contains(term) && haystack.contains(term)).count();
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
                answer.append("According to the acceptance record, ‘").append(task.title()).append("’ was completed by ");
                JsonArray authors = submissionAuthors(c, submissionId);
                for (int i = 0; i < authors.size(); i++) {
                    if (i > 0) answer.append(", ");
                    JsonObject a = authors.get(i).getAsJsonObject();
                    answer.append(a.get("name").getAsString()).append(" (")
                            .append(fmt(a.get("sharePct").getAsDouble())).append("%)");
                }
                answer.append(". Accepted version v").append(version).append(", ").append(points)
                        .append(" points, accepted on ").append(acceptedAt).append(".");
                answer.append(" This record covers accepted work only.\n");
                citations.add(citation("E" + (citations.size() + 1), task.title() + " · Accepted version v" + version,
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
                    answer.append("‘").append(task.title()).append("’ has version v").append(version)
                            .append(", current status: ").append(statusLabel(status))
                            .append("; this version has earned no points.\n");
                    citations.add(citation("E" + (citations.size() + 1), task.title() + " · Version v" + version,
                            projectId, task.id(), submissionId, null, null));
                } else {
                    answer.append("‘").append(task.title()).append("’ has no submissions yet. There is no evidence of who completed the work.\n");
                    citations.add(citation("E" + (citations.size() + 1), task.title() + " · Task agreement",
                            projectId, task.id(), null, null, null));
                }
                added++;
            }
            if (added >= 3) break;
        }
        if (added == 0) {
            answer.append("No matching accepted deliverable was found in this project. Missing: a matching task title or submission. Check the task list, or add the deliverable and ask again.");
            citations.add(citation("E1", "Project tasks", projectId, 0, null, null, null));
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
        answer.append("Checking ‘").append(task.title()).append("’ ")
                .append(submissionId == 0 ? "agreed" : "v" + version + " frozen")
                .append(" acceptance criteria: \n");
        if (pdf) answer.append("Open this version’s PDF to read the original attachment. This check uses only the saved deliverable text.\n");
        String[] lines = criteria.split("\\r?\\n");
        int index = 1;
        for (String line : lines) {
            String criterion = line.trim().replaceFirst("^[-*\\d., ) ]+", "");
            if (criterion.isEmpty()) continue;
            String evidence = findEvidence(criterion, artifact);
            answer.append(index).append(". Criterion: ").append(criterion).append("\n");
            if (evidence == null) {
                answer.append("   No matching passage was found in the submission. Missing: evidence addressing this criterion.\n");
            } else {
                answer.append("   Submission evidence: ").append(evidence).append("\n");
            }
            index++;
        }
        if (submissionId == 0) {
            answer.append("No deliverable has been submitted. These criteria cannot yet be checked.");
            citations.add(citation("E1", task.title() + " · Task acceptance criteria", projectId, task.id(), null, null, null));
        } else {
            answer.append("Acceptance record: Version v").append(version).append(" is ‘").append(statusLabel(status))
                    .append("’. This check uses the submission and frozen criteria, team chat is excluded.");
            citations.add(citation("E1", task.title() + " · Frozen acceptance criteria v" + version, projectId, task.id(), submissionId, null, null));
            citations.add(citation("E2", task.title() + " · Deliverable v" + version, projectId, task.id(), submissionId, null, null));
            citations.add(citation("E3", task.title() + " · Acceptance record v" + version + " · " + statusLabel(status),
                    projectId, task.id(), submissionId, null, null));
        }
        return result(answer.toString(), citations);
    }

    private static String findEvidence(String criterion, String artifact) {
        String source = artifact.trim();
        if (source.isEmpty()) return null;
        var words = java.util.regex.Pattern.compile("[\\p{L}\\p{N}]+");
        var criterionTerms = new java.util.HashSet<String>();
        var terms = words.matcher(criterion.toLowerCase(Locale.ROOT));
        while (terms.find()) {
            String term = terms.group();
            if (term.length() >= 3 && !QUERY_STOP_WORDS.contains(term)) criterionTerms.add(term);
        }
        var passage = words.matcher(source);
        int bestStart = -1;
        int bestLength = 0;
        while (passage.find()) {
            String term = passage.group().toLowerCase(Locale.ROOT);
            if (term.length() > bestLength && criterionTerms.contains(term)) {
                bestStart = passage.start();
                bestLength = passage.end() - passage.start();
            }
        }
        if (bestStart < 0) return null;
        int start = Math.max(0, bestStart - 25);
        int end = Math.min(source.length(), bestStart + bestLength + 45);
        return (start > 0 ? "…" : "") + source.substring(start, end).replace("\n", " ")
                + (end < source.length() ? "…" : "");
    }

    private static JsonObject delayAnswer(Connection c, int projectId, List<TaskEvidence> ranked, String q) throws SQLException {
        StringBuilder answer = new StringBuilder();
        JsonArray citations = new JsonArray();
        Integer taskId = ranked.isEmpty() || ranked.get(0).bestMatch() <= 0 ? null : ranked.get(0).task().id();
        int added = 0;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT d.id,d.task_id,d.old_deadline,d.new_deadline,d.reason,d.status,d.created_at,d.decided_at," +
                        "d.decision_note,u.name AS requester,t.title,t.checkpoint_at FROM date_changes d " +
                        "JOIN users u ON u.id=d.requested_by JOIN tasks t ON t.id=d.task_id WHERE d.project_id=? " +
                        "AND d.task_id=? ORDER BY d.id")) {
            ps.setInt(1, projectId);
            if (taskId == null) ps.setNull(2, java.sql.Types.INTEGER);
            else ps.setInt(2, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    answer.append("‘").append(rs.getString("title")).append("’ — original deadline ")
                            .append(rs.getString("old_deadline")).append(", requested deadline ")
                            .append(rs.getString("new_deadline")).append("; reason: ")
                            .append(rs.getString("reason")).append("; outcome: ")
                            .append("approved".equals(rs.getString("status")) ? "Approved" : "rejected".equals(rs.getString("status")) ? "Declined" : "Pending")
                            .append(" (").append(rs.getString("requester")).append(" on ")
                            .append(rs.getString("created_at")).append(" requested")
                            .append(rs.getString("decided_at") == null ? "" : ", " + rs.getString("decided_at") + " decided")
                            .append(").");
                    if (!rs.getString("decision_note").isEmpty()) {
                        answer.append("Decision note: ").append(rs.getString("decision_note"));
                    }
                    answer.append("\n");
                    citations.add(citation("E" + (citations.size() + 1), "Deadline change · " + rs.getString("title"),
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
                        answer.append("No formal extension was found, but ‘").append(rs.getString("title"))
                                .append("’ has a recorded ").append("blocker".equals(rs.getString("kind")) ? "blocker" : "help request")
                                .append(": ").append(rs.getString("body")).append("; Support needed: ")
                                .append(rs.getString("needed_support")).append(" (")
                                .append(rs.getString("name")).append(", ").append(rs.getString("created_at"))
                                .append(").\n");
                        citations.add(citation("E" + (citations.size() + 1), "blocker/Help record · " + rs.getString("title"),
                                projectId, taskId, null, null, rs.getInt("id")));
                        added++;
                    }
                }
            }
        }
        if (added == 0) {
            answer.append("No matching deadline change was or blocker record was found in this project. Missing: a formal extension request or blocker note.");
            if (taskId != null) {
                citations.add(citation("E1", "Task record", projectId, taskId, null, null, null));
            } else {
                citations.add(citation("E1", "Project tasks", projectId, 0, null, null, null));
            }
        }
        answer.append(" Contribution totals come from accepted records; for the calculation, see the closing report.");
        return result(answer.toString(), citations);
    }

    private static JsonObject contributionAnswer(Connection c, int projectId, Contribution.Result contribution)
            throws SQLException {
        StringBuilder answer = new StringBuilder();
        JsonArray citations = new JsonArray();
        answer.append("The application calculates contributions from accepted records, independently of any model. Project completion ")
                .append(fmt(contribution.completionPct())).append("% (").append(contribution.acceptedPoints())
                .append(" / ").append(contribution.plannedPoints()).append(" points).\n");
        if (!contribution.hasAccepted()) {
            answer.append("No accepted contributions yet.");
        } else {
            for (Auth.User member : contribution.members()) {
                answer.append("- ").append(member.name()).append(": ")
                        .append(fmt(contribution.memberPoints().getOrDefault(member.id(), 0.0))).append(" points, share of accepted contributions ")
                        .append(fmt(contribution.memberSharePct(member.id()))).append("%\n");
            }
            answer.append("Formula: Individual points = accepted task points × frozen author share; Individual share = individual accepted points ÷ total accepted points.");
        }
        for (Contribution.AcceptedTask task : contribution.acceptedTasks()) {
            citations.add(citation("E" + (citations.size() + 1), task.title() + " · Accepted version v" + task.version(),
                    projectId, task.taskId(), task.submissionId(), null, null));
        }
        if (citations.isEmpty()) citations.add(citation("E1", "Project tasks", projectId, 0, null, null, null));
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
            answer.append("‘").append(task.title()).append("’ (").append(task.branchName()).append("): Assignee ")
                    .append(task.assigneeName()).append(", Plan ").append(task.points()).append(" points, Checkpoint ")
                    .append(task.checkpointAt()).append(".");
            Integer submissionId = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM submissions WHERE task_id=? ORDER BY version DESC LIMIT 1")) {
                ps.setInt(1, task.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) submissionId = rs.getInt(1);
                }
            }
            if (te.score() == 1) answer.append(" This task has an accepted, scored version.");
            else if (submissionId != null) answer.append(" A submission is available, open its version to see the review status.");
            else answer.append(" No submissions yet.");
            answer.append("\n");
            citations.add(citation("E" + (citations.size() + 1), task.title(), projectId, task.id(), submissionId, null, null));
            added++;
            if (added >= 4) break;
        }
        if (added == 0) {
            answer.append("No matching task or deliverable was found in this project. Missing: a matching task, version or event record.");
            citations.add(citation("E1", "Project tasks", projectId, 0, null, null, null));
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
                    .append(c.get("label").getAsString()).append(": ").append(c.get("url").getAsString()).append("\n");
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
        if (status == null) return "No submission";
        return switch (status) {
            case "pending" -> "Pending review";
            case "accepted" -> "Accepted";
            case "rejected" -> "Rejected";
            case "withdrawn" -> "Withdrawn";
            case "superseded" -> "Superseded";
            default -> status;
        };
    }
}
