package af;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Human reports/disputes and the member-selected closing report export. */
final class Reports {
    private Reports() {}

    static JsonArray projectSummaries(Connection c, int projectId, int userId) throws SQLException {
        Auth.requireMember(c, projectId, userId);
        JsonArray arr = new JsonArray();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT r.id,r.task_id,r.reason,r.status,r.created_at,u.name AS reporter,t.title " +
                        "FROM reports r JOIN users u ON u.id=r.reporter_id JOIN tasks t ON t.id=r.task_id " +
                        "WHERE r.project_id=? ORDER BY r.id DESC")) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", rs.getInt("id"));
                    o.addProperty("taskId", rs.getInt("task_id"));
                    o.addProperty("taskTitle", rs.getString("title"));
                    o.addProperty("reason", rs.getString("reason"));
                    o.addProperty("status", rs.getString("status"));
                    o.addProperty("createdAt", rs.getString("created_at"));
                    o.addProperty("reporter", rs.getString("reporter"));
                    o.addProperty("statementCount", statementCount(c, rs.getInt("id")));
                    arr.add(o);
                }
            }
        }
        return arr;
    }

    static JsonArray summaries(Connection c, int projectId, int taskId, int userId) throws SQLException {
        JsonArray arr = new JsonArray();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT r.id,r.reason,r.status,r.created_at,u.name AS reporter FROM reports r " +
                        "JOIN users u ON u.id=r.reporter_id WHERE r.project_id=? AND r.task_id=? ORDER BY r.id DESC")) {
            ps.setInt(1, projectId);
            ps.setInt(2, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", rs.getInt("id"));
                    o.addProperty("reason", rs.getString("reason"));
                    o.addProperty("status", rs.getString("status"));
                    o.addProperty("createdAt", rs.getString("created_at"));
                    o.addProperty("reporter", rs.getString("reporter"));
                    o.addProperty("statementCount", statementCount(c, rs.getInt("id")));
                    arr.add(o);
                }
            }
        }
        return arr;
    }

    private static int statementCount(Connection c, int reportId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM report_statements WHERE report_id=?")) {
            ps.setInt(1, reportId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    static JsonObject create(Auth.User user, int projectId, int taskId, JsonObject body) throws Exception {
        String reason = Util.required(body, "reason", 4000);
        return Db.write(c -> {
            Auth.requireMember(c, projectId, user.id());
            Tasks.TaskRow task = Tasks.load(c, taskId);
            if (task.projectId() != projectId) throw new ApiException(404, "Task not found");
            int reportId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO reports(project_id,task_id,reporter_id,reason,created_at) VALUES(?,?,?,?,?)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, projectId);
                ps.setInt(2, taskId);
                ps.setInt(3, user.id());
                ps.setString(4, reason);
                ps.setString(5, Clock.nowIso());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    reportId = keys.getInt(1);
                }
            }
            JsonObject plan = taskPlan(task, task.assigneeId(), task.assigneeName(), task.deadlineAt(),
                    Tasks.planAuthors(c, taskId));
            plan.addProperty("reporterId", user.id());
            plan.addProperty("reporter", user.name());
            savePlan(c, reportId, plan);
            Tasks.event(c, projectId, taskId, "report_created", user.id(), "project",
                    "从该任务发起举报，等待成员陈述与复核。", "{\"reportId\":" + reportId + "}");
            return reportDetail(c, projectId, reportId, user.id());
        });
    }

    static JsonObject respond(Auth.User user, int projectId, int reportId, JsonObject body) throws Exception {
        String text = Util.required(body, "body", 4000);
        return Db.write(c -> {
            Auth.requireMember(c, projectId, user.id());
            ReportRow report = loadReport(c, projectId, reportId);
            Involved involved = involved(c, report);
            if (!involved.userIds().contains(user.id())) {
                throw new ApiException(403, "Only people involved in this report can add a statement");
            }
            if (report.status().equals("closed")) throw new ApiException(409, "This report is closed");
            statement(c, reportId, user.id(), "response", text);
            Tasks.event(c, projectId, report.taskId(), "report_response", user.id(), "project",
                    "对举报补充了成员陈述。", "{\"reportId\":" + reportId + "}");
            return reportDetail(c, projectId, reportId, user.id());
        });
    }

    static JsonObject review(Auth.User user, int projectId, int reportId, JsonObject body) throws Exception {
        String text = Util.required(body, "body", 4000);
        return Db.write(c -> {
            Auth.requireMember(c, projectId, user.id());
            ReportRow report = loadReport(c, projectId, reportId);
            Involved involved = involved(c, report);
            if (!involved.complete() || involved.userIds().contains(user.id())) {
                throw new ApiException(403, involved.complete() ? "Only members not involved in the report can review it"
                        : "历史当事人资料缺失，暂无法确认复核资格。");
            }
            if (report.status().equals("closed")) throw new ApiException(409, "This report is closed");
            statement(c, reportId, user.id(), "review", text);
            Tasks.event(c, projectId, report.taskId(), "report_review", user.id(), "project",
                    "以未涉事成员身份提交了复核意见。", "{\"reportId\":" + reportId + "}");
            return reportDetail(c, projectId, reportId, user.id());
        });
    }

    static JsonObject close(Auth.User user, int projectId, int reportId) throws Exception {
        return Db.write(c -> {
            Auth.requireMember(c, projectId, user.id());
            ReportRow report = loadReport(c, projectId, reportId);
            if (report.reporterId() != user.id() && !Auth.isOwner(c, projectId, user.id())) {
                throw new ApiException(403, "Only the reporter or project owner can close this report");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE reports SET status='closed',closed_at=? WHERE id=?")) {
                ps.setString(1, Clock.nowIso());
                ps.setInt(2, reportId);
                ps.executeUpdate();
            }
            return reportDetail(c, projectId, reportId, user.id());
        });
    }

    private static void statement(Connection c, int reportId, int userId, String kind, String text) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO report_statements(report_id,author_id,kind,body,created_at) VALUES(?,?,?,?,?)")) {
            ps.setInt(1, reportId);
            ps.setInt(2, userId);
            ps.setString(3, kind);
            ps.setString(4, text);
            ps.setString(5, Clock.nowIso());
            ps.executeUpdate();
        }
    }

    record ReportRow(int id, int projectId, int taskId, int reporterId, String reason, String status, String createdAt) {}

    private static ReportRow loadReport(Connection c, int projectId, int reportId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id,project_id,task_id,reporter_id,reason,status,created_at FROM reports WHERE id=? AND project_id=?")) {
            ps.setInt(1, reportId);
            ps.setInt(2, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApiException(404, "Report not found");
                return new ReportRow(rs.getInt("id"), rs.getInt("project_id"), rs.getInt("task_id"),
                        rs.getInt("reporter_id"), rs.getString("reason"), rs.getString("status"),
                        rs.getString("created_at"));
            }
        }
    }

    record Involved(Set<Integer> userIds, boolean complete) {}

    private static Involved involved(Connection c, ReportRow report) throws SQLException {
        JsonObject plan = loadPlan(c, report.id());
        Set<Integer> ids = new HashSet<>();
        ids.add(report.reporterId());
        if (!plan.get("assigneeId").isJsonNull()) ids.add(plan.get("assigneeId").getAsInt());
        for (var author : plan.getAsJsonArray("authors")) ids.add(author.getAsJsonObject().get("userId").getAsInt());
        return new Involved(ids, plan.get("partiesComplete").getAsBoolean());
    }

    private static JsonObject loadPlan(Connection c, int reportId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT snapshot FROM report_plans WHERE report_id=?")) {
            ps.setInt(1, reportId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("Missing report plan snapshot");
                return Util.parseObject(rs.getString(1));
            }
        }
    }

    private static void savePlan(Connection c, int reportId, JsonObject plan) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO report_plans(report_id,snapshot) VALUES(?,?)")) {
            ps.setInt(1, reportId);
            ps.setString(2, plan.toString());
            ps.executeUpdate();
        }
    }

    private static JsonObject taskPlan(Tasks.TaskRow task, Integer assignee, String assigneeName, String deadline,
                                       List<JsonObject> authors) {
        JsonObject plan = new JsonObject();
        plan.addProperty("title", task.title());
        plan.addProperty("assigneeId", assignee);
        plan.addProperty("assignee", assigneeName);
        plan.addProperty("points", task.points());
        plan.addProperty("createdAt", task.createdAt());
        plan.addProperty("checkpointAt", task.checkpointAt());
        plan.addProperty("currentDeadlineAt", deadline);
        plan.addProperty("expectedOutput", task.expectedOutput());
        plan.addProperty("acceptanceCriteria", task.criteria());
        plan.add("authors", Util.GSON.toJsonTree(authors));
        plan.addProperty("partiesComplete", assignee != null && !authors.isEmpty());
        return plan;
    }

    static void migratePlans(Connection c) throws SQLException {
        List<ReportRow> missing = new ArrayList<>();
        try (var st = c.createStatement(); var rs = st.executeQuery("""
                SELECT r.* FROM reports r LEFT JOIN report_plans p ON p.report_id=r.id WHERE p.report_id IS NULL
                """)) {
            while (rs.next()) {
                missing.add(new ReportRow(rs.getInt("id"), rs.getInt("project_id"), rs.getInt("task_id"),
                        rs.getInt("reporter_id"), rs.getString("reason"), rs.getString("status"), rs.getString("created_at")));
            }
        }
        for (ReportRow report : missing) savePlan(c, report.id(), recoverPlan(c, report));
    }

    private static JsonObject recoverPlan(Connection c, ReportRow report) throws SQLException {
        Tasks.TaskRow task = Tasks.load(c, report.taskId());
        long reportEvent = 0;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id FROM task_events WHERE task_id=? AND type='report_created'
                AND json_extract(payload,'$.reportId')=? ORDER BY id LIMIT 1
                """)) {
            ps.setInt(1, task.id());
            ps.setInt(2, report.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) reportEvent = rs.getLong(1);
            }
        }
        Integer owner = null;
        Integer reassignedAuthor = null;
        boolean ambiguous = false;
        try (PreparedStatement ps = c.prepareStatement("""
                WITH changes AS (
                    SELECT *,row_number() OVER (ORDER BY id) AS sequence FROM reassignments WHERE task_id=?
                ), events AS (
                    SELECT id,row_number() OVER (ORDER BY id) AS sequence FROM task_events
                    WHERE task_id=? AND type='reassigned'
                )
                SELECT changes.*, CASE WHEN (SELECT COUNT(*) FROM changes)=(SELECT COUNT(*) FROM events)
                    THEN events.id ELSE NULL END AS event_id
                FROM changes LEFT JOIN events USING(sequence) ORDER BY changes.id
                """)) {
            ps.setInt(1, task.id());
            ps.setInt(2, task.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if (owner == null) owner = rs.getInt("from_user_id");
                    int order = java.time.Instant.parse(rs.getString("created_at"))
                            .compareTo(java.time.Instant.parse(report.createdAt()));
                    long event = rs.getLong("event_id");
                    if (order == 0 && (event == 0 || reportEvent == 0)) ambiguous = true;
                    if (order < 0 || (order == 0 && event != 0 && event < reportEvent)) {
                        owner = rs.getInt("to_user_id");
                        reassignedAuthor = owner;
                    }
                }
            }
        }
        // Before this migration, a handoff also reset the live authors to the new owner at 100%.
        List<JsonObject> authors = new ArrayList<>();
        if (!ambiguous && reassignedAuthor != null) {
            authors.add(planAuthor(c, reassignedAuthor, 100));
        } else if (!ambiguous) {
            try (PreparedStatement ps = c.prepareStatement("SELECT original_plan FROM tasks WHERE id=?")) {
                ps.setInt(1, task.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        try {
                            Set<Integer> authorIds = new HashSet<>();
                            double total = 0;
                            for (var entry : com.google.gson.JsonParser.parseString(rs.getString(1)).getAsJsonArray()) {
                                JsonObject author = entry.getAsJsonObject();
                                int id = author.get("userId").getAsInt();
                                double share = author.get("sharePct").getAsDouble();
                                if (id <= 0 || !Double.isFinite(share) || share <= 0 || share > 100 || !authorIds.add(id)) {
                                    throw new IllegalArgumentException("Invalid historical share");
                                }
                                authors.add(planAuthor(c, id, share));
                                total += share;
                            }
                            if (Math.abs(total - 100) > 0.01) authors.clear();
                        } catch (RuntimeException malformed) {
                            authors.clear();
                        }
                    }
                }
            }
        }
        // Without a recorded handoff the owner was immutable under the old schema.
        Integer atReport = owner;
        if (ambiguous) atReport = null;
        if (owner == null && authors.stream().anyMatch(a -> a.get("userId").getAsInt() == task.assigneeId())
                && !hasHandoffEvent(c, task.id())) {
            atReport = task.assigneeId();
        }
        String deadline = recoverDeadline(c, task, report, reportEvent);
        JsonObject plan = taskPlan(task, atReport, atReport == null ? null : Tasks.nameOf(c, atReport), deadline, authors);
        plan.addProperty("reporterId", report.reporterId());
        plan.addProperty("reporter", Tasks.nameOf(c, report.reporterId()));
        plan.addProperty("historyNote", !plan.get("partiesComplete").getAsBoolean() || deadline == null
                ? "部分举报当时的资料缺失，按现存历史记录显示。" : "依据当时的分工及日期记录还原。");
        return plan;
    }

    private static boolean hasHandoffEvent(Connection c, int taskId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM task_events WHERE task_id=? AND type='reassigned' LIMIT 1")) {
            ps.setInt(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String recoverDeadline(Connection c, Tasks.TaskRow task, ReportRow report, long reportEvent)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT new_deadline,decided_at,
                   (SELECT MIN(e.id) FROM task_events e WHERE e.task_id=d.task_id AND e.type='extension_approved'
                    AND e.created_at=d.decided_at AND instr(e.body,d.new_deadline)>0) AS event_id
                FROM date_changes d WHERE task_id=? AND status='approved' ORDER BY decided_at DESC,id DESC
                """)) {
            ps.setInt(1, task.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int order = java.time.Instant.parse(rs.getString("decided_at"))
                            .compareTo(java.time.Instant.parse(report.createdAt()));
                    long event = rs.getLong("event_id");
                    if (order == 0 && (reportEvent == 0 || event == 0)) return null;
                    if (order < 0 || (order == 0 && event < reportEvent)) return rs.getString("new_deadline");
                }
            }
        }
        // The first approval records the initial deadline. A later request may have been made
        // against an already outdated deadline, so only the first decision is used here.
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT old_deadline FROM date_changes WHERE task_id=? AND status='approved'
                ORDER BY decided_at,id LIMIT 1
                """)) {
            ps.setInt(1, task.id());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : task.deadlineAt();
            }
        }
    }

    private static JsonObject planAuthor(Connection c, int id, double share) throws SQLException {
        JsonObject author = new JsonObject();
        author.addProperty("userId", id);
        author.addProperty("name", Tasks.nameOf(c, id));
        author.addProperty("sharePct", share);
        return author;
    }

    static JsonObject reportDetail(Connection c, int projectId, int reportId, int userId) throws SQLException {
        Auth.requireMember(c, projectId, userId);
        ReportRow report = loadReport(c, projectId, reportId);
        Tasks.TaskRow task = Tasks.load(c, report.taskId());
        Involved involved = involved(c, report);
        JsonObject o = new JsonObject();
        o.addProperty("id", report.id());
        o.addProperty("status", report.status());
        o.addProperty("reason", report.reason());
        o.addProperty("createdAt", report.createdAt());
        o.addProperty("projectId", projectId);
        o.addProperty("taskId", report.taskId());
        o.addProperty("taskTitle", task.title());
        o.add("reporter", loadPlan(c, reportId).get("reporter"));
        o.addProperty("canRespond", involved.userIds().contains(userId) && "open".equals(report.status()));
        o.addProperty("canReview", involved.complete() && !involved.userIds().contains(userId) && "open".equals(report.status()));
        o.addProperty("canClose", report.reporterId() == userId || Auth.isOwner(c, projectId, userId));
        o.add("facts", facts(c, task, report));
        JsonArray statements = new JsonArray();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT s.id,s.kind,s.body,s.created_at,u.name FROM report_statements s " +
                        "JOIN users u ON u.id=s.author_id WHERE s.report_id=? ORDER BY s.id")) {
            ps.setInt(1, reportId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject s = new JsonObject();
                    s.addProperty("id", rs.getInt("id"));
                    s.addProperty("kind", rs.getString("kind"));
                    s.addProperty("body", rs.getString("body"));
                    s.addProperty("createdAt", rs.getString("created_at"));
                    s.addProperty("author", rs.getString("name"));
                    statements.add(s);
                }
            }
        }
        o.add("statements", statements);
        o.add("involved", intArray(involved.userIds()));
        return o;
    }

    private static JsonArray intArray(Set<Integer> ids) {
        JsonArray arr = new JsonArray();
        for (int id : ids) arr.add(id);
        return arr;
    }

    private static JsonObject facts(Connection c, Tasks.TaskRow task, ReportRow report) throws SQLException {
        JsonObject f = new JsonObject();
        f.add("plan", loadPlan(c, report.id()));
        f.add("reassignments", Tasks.reassignments(c, task.id()));
        f.add("dateChanges", Tasks.dateChanges(c, task.id()));
        f.add("supports", Tasks.supports(c, task.id()));

        JsonArray reminders = new JsonArray();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT checkpoint_at,created_at,follow_up_at,follow_up_resolved_at FROM reminders " +
                        "WHERE task_id=? AND follow_up_at IS NOT NULL ORDER BY created_at")) {
            ps.setInt(1, task.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject r = new JsonObject();
                    r.addProperty("checkpointAt", rs.getString("checkpoint_at"));
                    r.addProperty("firstPrivateReminderAt", rs.getString("created_at"));
                    r.addProperty("groupFollowUpAt", rs.getString("follow_up_at"));
                    r.addProperty("followUpResolvedAt", rs.getString("follow_up_resolved_at"));
                    reminders.add(r);
                }
            }
        }
        f.add("reminders", reminders);

        f.add("versions", exportVersions(c, task));

        Integer scorePoints = null;
        String scoreAt = null;
        Integer scoreVersion = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ts.points,ts.accepted_at,s.version FROM task_scores ts JOIN submissions s ON s.id=ts.submission_id " +
                        "WHERE ts.task_id=?")) {
            ps.setInt(1, task.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    scorePoints = rs.getInt("points");
                    scoreAt = rs.getString("accepted_at");
                    scoreVersion = rs.getInt("version");
                }
            }
        }
        if (scorePoints != null) {
            JsonObject score = new JsonObject();
            score.addProperty("points", scorePoints);
            score.addProperty("acceptedAt", scoreAt);
            score.addProperty("version", scoreVersion);
            f.add("acceptance", score);
        } else {
            f.add("acceptance", null);
        }
        f.addProperty("reportCreatedAt", report.createdAt());
        return f;
    }

    private static JsonArray exportVersions(Connection c, Tasks.TaskRow task) throws SQLException {
        JsonArray versions = new JsonArray();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT id,version,status,created_at,finalized_at,content,artifact_name,artifact_text,criteria_snapshot,
                    points_snapshot,withdrawal_reason,pdf IS NOT NULL AS has_pdf
                FROM submissions WHERE task_id=? ORDER BY version
                """)) {
            ps.setInt(1, task.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    JsonObject version = new JsonObject();
                    version.addProperty("id", id);
                    version.addProperty("submissionId", id);
                    version.addProperty("version", rs.getInt("version"));
                    version.addProperty("status", rs.getString("status"));
                    version.addProperty("createdAt", rs.getString("created_at"));
                    version.addProperty("finalizedAt", rs.getString("finalized_at"));
                    version.addProperty("points", rs.getInt("points_snapshot"));
                    version.addProperty("withdrawalReason", rs.getString("withdrawal_reason"));
                    version.addProperty("criteriaSnapshot", rs.getString("criteria_snapshot"));
                    version.addProperty("content", rs.getString("content"));
                    version.addProperty("artifactName", rs.getString("artifact_name"));
                    version.addProperty("artifactText", rs.getString("artifact_text"));
                    if (rs.getBoolean("has_pdf")) {
                        version.addProperty("attachmentUrl", Tasks.attachmentPath(task.projectId(), task.id(), id));
                    }
                    JsonArray authors = new JsonArray();
                    try (PreparedStatement shares = c.prepareStatement(
                            "SELECT user_id,name_snapshot,share_pct FROM submission_authors WHERE submission_id=?")) {
                        shares.setInt(1, id);
                        try (ResultSet a = shares.executeQuery()) {
                            while (a.next()) {
                                JsonObject author = new JsonObject();
                                author.addProperty("id", a.getInt(1));
                                author.addProperty("name", a.getString(2));
                                author.addProperty("sharePct", a.getDouble(3));
                                authors.add(author);
                            }
                        }
                    }
                    version.add("authors", authors);
                    versions.add(version);
                }
            }
        }
        return versions;
    }

    static JsonObject buildReportData(int projectId, int userId, boolean includeTasks, boolean includeTimeline,
                                      boolean includeDisputes, boolean includeReminders) throws Exception {
        try (Connection c = Db.open()) {
            Auth.requireMember(c, projectId, userId);
            JsonObject out = new JsonObject();
            out.addProperty("generatedAt", Clock.nowIso());
            out.add("project", projectJsonForReport(c, projectId));
            Contribution.Result contribution = Contribution.calculate(c, projectId);
            out.add("contribution", Contribution.toJson(contribution));
            JsonArray memberAchievements = new JsonArray();
            for (Auth.User member : contribution.members()) {
                JsonObject memberJson = new JsonObject();
                memberJson.addProperty("id", member.id());
                memberJson.addProperty("name", member.name());
                JsonArray items = new JsonArray();
                for (Contribution.AcceptedTask accepted : contribution.acceptedTasks()) {
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT share_pct FROM submission_authors WHERE submission_id=? AND user_id=?")) {
                        ps.setInt(1, accepted.submissionId());
                        ps.setInt(2, member.id());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                double share = rs.getDouble("share_pct");
                                JsonObject item = new JsonObject();
                                item.addProperty("taskId", accepted.taskId());
                                item.addProperty("title", accepted.title());
                                item.addProperty("version", accepted.version());
                                item.addProperty("taskPoints", accepted.points());
                                item.addProperty("sharePct", share);
                                item.addProperty("memberPoints", Util.round1(accepted.points() * share / 100.0));
                                items.add(item);
                            }
                        }
                    }
                }
                memberJson.add("items", items);
                memberAchievements.add(memberJson);
            }
            out.add("memberAchievements", memberAchievements);
            JsonObject formulas = new JsonObject();
            formulas.addProperty("projectCompletion", "accepted task points ÷ project planned points");
            formulas.addProperty("branchCompletion", "accepted points in branch ÷ branch planned points");
            formulas.addProperty("memberShare", "member accepted points ÷ all accepted points");
            formulas.addProperty("memberPoints", "each accepted task's planned points × frozen author share");
            formulas.addProperty("pending", "a version stays pending until every frozen author confirms and every eligible member completes an anonymous ballot; ballot details are never exported");
            out.add("formulas", formulas);

            JsonArray tasks = new JsonArray();
            if (includeTasks) {
                List<Integer> ids = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id FROM tasks WHERE project_id=? ORDER BY branch_id,deadline_at,id")) {
                    ps.setInt(1, projectId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) ids.add(rs.getInt(1));
                    }
                }
                for (int id : ids) {
                    Tasks.TaskRow task = Tasks.load(c, id);
                    JsonObject t = new JsonObject();
                    t.addProperty("id", task.id());
                    t.addProperty("title", task.title());
                    t.addProperty("branchId", task.branchId());
                    t.addProperty("branchName", task.branchName());
                    t.addProperty("assignee", task.assigneeName());
                    t.addProperty("points", task.points());
                    t.addProperty("checkpointAt", task.checkpointAt());
                    t.addProperty("deadlineAt", task.deadlineAt());
                    t.addProperty("expectedOutput", task.expectedOutput());
                    t.addProperty("acceptanceCriteria", task.criteria());
                    JsonObject score = new JsonObject();
                    Integer scorePoints = null;
                    String acceptedAt = null;
                    Integer version = null;
                    try (PreparedStatement ps = c.prepareStatement(
                            "SELECT ts.points,ts.accepted_at,s.version,s.id FROM task_scores ts " +
                                    "JOIN submissions s ON s.id=ts.submission_id WHERE ts.task_id=?")) {
                        ps.setInt(1, id);
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                scorePoints = rs.getInt("points");
                                acceptedAt = rs.getString("accepted_at");
                                version = rs.getInt("version");
                                score.addProperty("submissionId", rs.getInt("id"));
                            }
                        }
                    }
                    if (scorePoints != null) {
                        score.addProperty("points", scorePoints);
                        score.addProperty("acceptedAt", acceptedAt);
                        score.addProperty("version", version);
                        t.add("score", score);
                    } else {
                        t.add("score", null);
                    }
                    t.add("versions", exportVersions(c, task));
                    t.add("dateChanges", Tasks.dateChanges(c, id));
                    t.add("reassignments", Tasks.reassignments(c, id));
                    tasks.add(t);
                }
            }
            out.add("tasks", tasks);

            JsonArray branchTimelines = new JsonArray();
            if (includeTimeline) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id,name,planned_points FROM branches WHERE project_id=? ORDER BY position,id")) {
                    ps.setInt(1, projectId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            int branchId = rs.getInt("id");
                            JsonObject b = new JsonObject();
                            b.addProperty("id", branchId);
                            b.addProperty("name", rs.getString("name"));
                            b.addProperty("plannedPoints", rs.getInt("planned_points"));
                            int accepted = 0;
                            for (Contribution.AcceptedTask at : contribution.acceptedTasks()) {
                                if (at.branchId() == branchId) accepted += at.points();
                            }
                            b.addProperty("acceptedPoints", accepted);
                            b.addProperty("completionPct", Util.round1(rs.getInt("planned_points") == 0 ? 0
                                    : accepted * 100.0 / rs.getInt("planned_points")));
                            JsonArray entries = new JsonArray();
                            try (PreparedStatement ps2 = c.prepareStatement(
                                    "SELECT e.created_at,e.type,e.body,e.visibility,t.title,e.task_id FROM task_events e " +
                                            "JOIN tasks t ON t.id=e.task_id WHERE t.branch_id=? AND e.visibility='project' " +
                                            "ORDER BY e.created_at,e.id")) {
                                ps2.setInt(1, branchId);
                                try (ResultSet rs2 = ps2.executeQuery()) {
                                    while (rs2.next()) {
                                        JsonObject e = new JsonObject();
                                        e.addProperty("at", rs2.getString("created_at"));
                                        e.addProperty("type", rs2.getString("type"));
                                        e.addProperty("body", rs2.getString("body"));
                                        e.addProperty("taskId", rs2.getInt("task_id"));
                                        e.addProperty("taskTitle", rs2.getString("title"));
                                        entries.add(e);
                                    }
                                }
                            }
                            b.add("entries", entries);
                            branchTimelines.add(b);
                        }
                    }
                }
            }
            out.add("branchTimelines", branchTimelines);
            out.add("planChanges", Projects.planHistory(c, projectId));

            JsonArray reminders = new JsonArray();
            if (includeReminders) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT r.checkpoint_at,r.created_at,r.follow_up_at,r.follow_up_resolved_at,t.title,t.id " +
                                "FROM reminders r JOIN tasks t ON t.id=r.task_id WHERE r.project_id=? " +
                                "AND r.follow_up_at IS NOT NULL ORDER BY r.created_at")) {
                    ps.setInt(1, projectId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            JsonObject r = new JsonObject();
                            r.addProperty("taskId", rs.getInt("id"));
                            r.addProperty("taskTitle", rs.getString("title"));
                            r.addProperty("checkpointAt", rs.getString("checkpoint_at"));
                            r.addProperty("firstPrivateReminderAt", rs.getString("created_at"));
                            r.addProperty("groupFollowUpAt", rs.getString("follow_up_at"));
                            r.addProperty("followUpResolvedAt", rs.getString("follow_up_resolved_at"));
                            reminders.add(r);
                        }
                    }
                }
            }
            out.add("reminders", reminders);

            JsonArray disputes = new JsonArray();
            if (includeDisputes) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id FROM reports WHERE project_id=? ORDER BY id")) {
                    ps.setInt(1, projectId);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) disputes.add(disputeForExport(c, rs.getInt(1), projectId));
                    }
                }
            }
            out.add("disputes", disputes);

            JsonObject appendix = new JsonObject();
            appendix.addProperty("projectPlannedPoints", contribution.plannedPoints());
            appendix.addProperty("acceptedPoints", contribution.acceptedPoints());
            JsonArray scores = new JsonArray();
            for (Contribution.AcceptedTask at : contribution.acceptedTasks()) {
                JsonObject s = new JsonObject();
                s.addProperty("taskId", at.taskId());
                s.addProperty("points", at.points());
                s.addProperty("acceptedAt", at.acceptedAt());
                s.addProperty("version", at.version());
                JsonArray shares = new JsonArray();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT sa.user_id,u.name,sa.share_pct FROM submission_authors sa JOIN users u ON u.id=sa.user_id " +
                                "WHERE sa.submission_id=? ORDER BY sa.user_id")) {
                    ps.setInt(1, at.submissionId());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            JsonObject share = new JsonObject();
                            share.addProperty("userId", rs.getInt(1));
                            share.addProperty("name", rs.getString(2));
                            share.addProperty("sharePct", rs.getDouble(3));
                            shares.add(share);
                        }
                    }
                }
                s.add("shares", shares);
                scores.add(s);
            }
            appendix.add("scores", scores);
            out.add("dataAppendix", appendix);
            return out;
        }
    }

    private static JsonObject projectJsonForReport(Connection c, int projectId) throws SQLException {
        JsonObject o = new JsonObject();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id,name,description,planned_points,created_at FROM projects WHERE id=?")) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApiException(404, "Project not found");
                o.addProperty("id", rs.getInt("id"));
                o.addProperty("name", rs.getString("name"));
                o.addProperty("description", rs.getString("description"));
                o.addProperty("plannedPoints", rs.getInt("planned_points"));
                o.addProperty("createdAt", rs.getString("created_at"));
            }
        }
        return o;
    }

    private static JsonObject disputeForExport(Connection c, int reportId, int projectId) throws SQLException {
        ReportRow report = loadReport(c, projectId, reportId);
        Tasks.TaskRow task = Tasks.load(c, report.taskId());
        JsonObject o = new JsonObject();
        o.addProperty("id", report.id());
        o.addProperty("status", report.status());
        o.addProperty("reason", report.reason());
        o.addProperty("createdAt", report.createdAt());
        o.add("reporter", loadPlan(c, reportId).get("reporter"));
        o.addProperty("taskTitle", task.title());
        o.add("facts", facts(c, task, report));
        JsonArray statements = new JsonArray();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT s.kind,s.body,s.created_at,u.name FROM report_statements s JOIN users u ON u.id=s.author_id " +
                        "WHERE s.report_id=? ORDER BY s.id")) {
            ps.setInt(1, reportId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject s = new JsonObject();
                    s.addProperty("kind", rs.getString("kind"));
                    s.addProperty("body", rs.getString("body"));
                    s.addProperty("createdAt", rs.getString("created_at"));
                    s.addProperty("author", rs.getString("name"));
                    statements.add(s);
                }
            }
        }
        o.add("statements", statements);
        JsonArray unresolved = new JsonArray();
        if ("open".equals(report.status())) {
            unresolved.add("举报尚未结案：" + report.reason());
            for (var e : statements) {
                JsonObject s = e.getAsJsonObject();
                if ("review".equals(s.get("kind").getAsString())) {
                    unresolved.add("未涉事成员复核意见：" + s.get("body").getAsString());
                }
            }
        }
        o.add("unresolved", unresolved);
        return o;
    }

    static String exportHtml(int projectId, int userId, boolean includeTasks, boolean includeTimeline,
                             boolean includeDisputes, boolean includeReminders) throws Exception {
        JsonObject data = buildReportData(projectId, userId, includeTasks, includeTimeline, includeDisputes,
                includeReminders);
        String projectName = data.getAsJsonObject("project").get("name").getAsString();
        String generated = data.get("generatedAt").getAsString();
        StringBuilder h = new StringBuilder();
        h.append("<!doctype html><html lang=\"zh-CN\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>").append(Util.escapeHtml(projectName)).append(" — 结项报告</title>")
                .append("<style>body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;max-width:1000px;")
                .append("margin:0 auto;padding:32px;color:#17212f;line-height:1.6}h1,h2,h3{line-height:1.25}")
                .append("h2{border-bottom:2px solid #0f766e;padding-bottom:6px;margin-top:36px}")
                .append("table{border-collapse:collapse;width:100%;margin:12px 0;font-size:14px}")
                .append("th,td{border:1px solid #cbd5e1;padding:8px;text-align:left;vertical-align:top}")
                .append("th{background:#f1f5f9}.muted{color:#64748b}.fact{background:#f8fafc;padding:12px;")
                .append("border-left:4px solid #0f766e}.statement{background:#fffbeb;padding:12px;border-left:4px solid #d97706}")
                .append(".unresolved{background:#fef2f2;padding:12px;border-left:4px solid #dc2626}")
                .append("code{background:#f1f5f9;padding:2px 4px;border-radius:4px}@media print{body{padding:0}}")
                .append("</style></head><body>");
        h.append("<h1>").append(Util.escapeHtml(projectName)).append("：结项报告</h1>");
        h.append("<p class=\"muted\">生成时间 ").append(Util.escapeHtml(generated))
                .append(" · 由项目成员在选择材料后自行导出，可直接交给老师。</p>");

        JsonObject contribution = data.getAsJsonObject("contribution");
        h.append("<h2>一、贡献汇总（由程序复算）</h2>");
        h.append("<p>项目完成度：<strong>").append(contribution.get("acceptedPoints").getAsInt())
                .append(" / ").append(contribution.get("plannedPoints").getAsInt()).append(" = ")
                .append(fmt(contribution.get("completionPct").getAsDouble()))
                .append("%</strong>（已验收任务分 / 项目计划总分）。</p>");
        appendBudget(h, contribution);
        if (!contribution.get("hasAccepted").getAsBoolean()) {
            h.append("<p class=\"muted\">暂无已验收贡献。</p>");
        }
        h.append("<h3>个人成果明细（点开个人分值查看版本）</h3>");
        h.append("<table><thead><tr><th>成员</th><th>已验收分值 / 全组已验收 / 占比（点开看版本）</th></tr></thead><tbody>");
        int acceptedPoints = contribution.get("acceptedPoints").getAsInt();
        java.util.Map<Integer, JsonArray> achievementsByMember = new java.util.LinkedHashMap<>();
        for (var ae : data.getAsJsonArray("memberAchievements")) {
            JsonObject member = ae.getAsJsonObject();
            achievementsByMember.put(member.get("id").getAsInt(), member.getAsJsonArray("items"));
        }
        for (var e : contribution.getAsJsonArray("members")) {
            JsonObject m = e.getAsJsonObject();
            double points = m.get("points").getAsDouble();
            double share = m.get("sharePct").getAsDouble();
            h.append("<tr><td>").append(Util.escapeHtml(m.get("name").getAsString())).append("</td><td>");
            if (!contribution.get("hasAccepted").getAsBoolean()) {
                h.append("暂无已验收贡献");
            } else {
                h.append("<details><summary><strong>").append(fmt(points)).append(" / ").append(acceptedPoints)
                        .append(" = ").append(fmt(share)).append("%</strong></summary><ul>");
                JsonArray items = achievementsByMember.getOrDefault(m.get("id").getAsInt(), new JsonArray());
                if (items.isEmpty()) {
                    h.append("<li>暂无已验收成果</li>");
                }
                for (var itemElement : items) {
                    JsonObject item = itemElement.getAsJsonObject();
                    h.append("<li>").append(Util.escapeHtml(item.get("title").getAsString()))
                            .append("（v").append(item.get("version").getAsInt()).append("：")
                            .append(item.get("taskPoints").getAsInt()).append(" 分 × ")
                            .append(fmt(item.get("sharePct").getAsDouble())).append("% = ")
                            .append(fmt(item.get("memberPoints").getAsDouble())).append(" 分）</li>");
                }
                h.append("</ul></details>");
            }
            h.append("</td></tr>");
        }
        h.append("</tbody></table>");
        h.append("<p class=\"muted\">公式：项目完成度 = 已验收任务分值 ÷ 项目计划总分；分支完成度 = 分支已验收分值 ÷ 分支计划分；")
                .append("个人分值 = 每项已验收任务分值 × 冻结的署名比例；个人占比 = 个人已验收分值 ÷ 全组已验收分值。</p>");

        h.append("<h2>二、分支完成度</h2><table><thead><tr><th>分支</th><th>计划分</th><th>已验收</th><th>完成度（已验收 / 计划）</th></tr></thead><tbody>");
        for (var e : contribution.getAsJsonArray("branches")) {
            JsonObject b = e.getAsJsonObject();
            h.append("<tr><td>").append(Util.escapeHtml(b.get("name").getAsString())).append("</td><td>")
                    .append(b.get("plannedPoints").getAsInt()).append("</td><td>").append(b.get("acceptedPoints").getAsInt())
                    .append("</td><td>").append(b.get("hasAccepted").getAsBoolean()
                            ? b.get("acceptedPoints").getAsInt() + " / " + b.get("plannedPoints").getAsInt()
                                + " = " + fmt(b.get("completionPct").getAsDouble()) + "%"
                            : b.get("plannedPoints").getAsInt() == 0 ? "计划分待设置"
                            : "0 / " + b.get("plannedPoints").getAsInt() + " = 0% · 暂无已验收贡献");
            appendBudget(h, b);
            h.append("</td></tr>");
        }
        h.append("</tbody></table>");

        if (includeTasks) {
            h.append("<h2>三、已记录的事实：任务、成果版本与验收</h2>");
            for (var e : data.getAsJsonArray("tasks")) {
                JsonObject t = e.getAsJsonObject();
                h.append("<h3>").append(Util.escapeHtml(t.get("title").getAsString())).append("</h3>");
                h.append("<p class=\"muted\">分支 ").append(Util.escapeHtml(t.get("branchName").getAsString()))
                        .append(" · 负责人 ").append(Util.escapeHtml(t.get("assignee").getAsString()))
                        .append(" · ").append(t.get("points").getAsInt()).append(" 分 · 截止 ")
                        .append(Util.escapeHtml(t.get("deadlineAt").getAsString())).append("</p>");
                h.append("<div class=\"fact\"><strong>原定验收标准：</strong>")
                        .append(Util.escapeHtml(t.get("acceptanceCriteria").getAsString()).replace("\n", "<br>"))
                        .append("</div>");
                if (t.get("score").isJsonObject()) {
                    JsonObject s = t.getAsJsonObject("score");
                    h.append("<p><strong>验收：</strong>版本 v").append(s.get("version").getAsInt())
                            .append(" 全票通过，计 ").append(s.get("points").getAsInt()).append(" 分（")
                            .append(Util.escapeHtml(s.get("acceptedAt").getAsString())).append("）。</p>");
                } else {
                    h.append("<p><strong>验收：</strong>尚无已通过版本，不计分。</p>");
                }
                h.append("<table><thead><tr><th>版本</th><th>状态</th><th>署名与比例</th><th>提交时间</th><th>最终处理</th></tr></thead><tbody>");
                for (var ve : t.getAsJsonArray("versions")) {
                    JsonObject v = ve.getAsJsonObject();
                    StringBuilder names = new StringBuilder();
                    for (var ae : v.getAsJsonArray("authors")) {
                        if (names.length() > 0) names.append("；");
                        names.append(ae.getAsJsonObject().get("name").getAsString()).append(" ")
                                .append(fmt(ae.getAsJsonObject().get("sharePct").getAsDouble())).append("%");
                    }
                    h.append("<tr><td>v").append(v.get("version").getAsInt()).append("</td><td>")
                            .append(statusLabel(v.get("status").getAsString())).append("</td><td>")
                            .append(Util.escapeHtml(names.toString())).append("</td><td>")
                            .append(Util.escapeHtml(v.get("createdAt").getAsString())).append("</td><td>")
                            .append(v.get("finalizedAt").isJsonNull() ? "—" : Util.escapeHtml(v.get("finalizedAt").getAsString()))
                            .append(v.get("withdrawalReason").isJsonNull() ? "" : " · 原因：" + escaped(v, "withdrawalReason"))
                            .append("</td></tr>");
                    appendExportArtifact(h, v, projectId, userId);
                }
                h.append("</tbody></table>");
                for (var de : t.getAsJsonArray("dateChanges")) {
                    JsonObject d = de.getAsJsonObject();
                    h.append("<div class=\"fact\"><strong>延期记录：</strong>原截止 ")
                            .append(Util.escapeHtml(d.get("oldDeadline").getAsString())).append(" → 新截止 ")
                            .append(Util.escapeHtml(d.get("newDeadline").getAsString())).append("；原因：")
                            .append(Util.escapeHtml(d.get("reason").getAsString())).append("；结果：")
                            .append(statusLabel(d.get("status").getAsString())).append("（")
                            .append(Util.escapeHtml(d.get("requester").getAsString())).append(" 提出，")
                            .append(d.get("decider").isJsonNull() ? "未处理" : Util.escapeHtml(d.get("decider").getAsString()) + " 处理")
                            .append("）。</div>");
                }
                for (var re : t.getAsJsonArray("reassignments")) {
                    JsonObject r = re.getAsJsonObject();
                    StringBuilder original = new StringBuilder();
                    for (var pe : r.getAsJsonArray("originalPlanAuthors")) {
                        JsonObject p = pe.getAsJsonObject();
                        if (original.length() > 0) original.append("；");
                        original.append(p.get("name").getAsString()).append(" ").append(fmt(p.get("sharePct").getAsDouble())).append("%");
                    }
                    h.append("<div class=\"fact\"><strong>重新分工记录：</strong>")
                            .append(Util.escapeHtml(r.get("fromName").getAsString())).append(" → ")
                            .append(Util.escapeHtml(r.get("toName").getAsString())).append("；原因：")
                            .append(Util.escapeHtml(r.get("reason").getAsString())).append("；日期：")
                            .append(Util.escapeHtml(r.get("createdAt").getAsString())).append("；原约定：")
                            .append(Util.escapeHtml(original.toString())).append("。</div>");
                }
            }
        }

        if (includeTimeline) {
            h.append("<h2>四、分支时间线</h2>");
            for (var changeElement : data.getAsJsonArray("planChanges")) {
                JsonObject change = changeElement.getAsJsonObject();
                h.append("<p>").append(escaped(change, "createdAt")).append(" · ")
                        .append(change.get("branchName").isJsonNull() ? "项目" : escaped(change, "branchName"))
                        .append("计划分 ").append(change.get("oldPoints").getAsInt()).append(" → ")
                        .append(change.get("newPoints").getAsInt()).append(" · ").append(escaped(change, "reason")).append("</p>");
            }
            for (var be : data.getAsJsonArray("branchTimelines")) {
                JsonObject b = be.getAsJsonObject();
                h.append("<h3>").append(Util.escapeHtml(b.get("name").getAsString())).append("</h3>");
                h.append("<p class=\"muted\">计划 ").append(b.get("plannedPoints").getAsInt()).append(" 分 · 已验收 ")
                        .append(b.get("acceptedPoints").getAsInt()).append(" 分 · 完成度 ");
                if (b.get("acceptedPoints").getAsInt() == 0) {
                    h.append("暂无已验收贡献");
                } else {
                    h.append(b.get("acceptedPoints").getAsInt()).append(" / ")
                            .append(b.get("plannedPoints").getAsInt()).append(" = ")
                            .append(b.get("completionPct").getAsDouble()).append("%");
                }
                h.append("</p>");
                for (var ee : b.getAsJsonArray("entries")) {
                    JsonObject e = ee.getAsJsonObject();
                    h.append("<p><strong>").append(Util.escapeHtml(e.get("at").getAsString())).append("</strong> · ")
                            .append(Util.escapeHtml(e.get("taskTitle").getAsString())).append("：")
                            .append(Util.escapeHtml(e.get("body").getAsString())).append("</p>");
                }
            }
        }

        if (includeReminders) {
            h.append("<h2>五、检查点提醒（仅组内可见的升级记录）</h2>");
            if (data.getAsJsonArray("reminders").isEmpty()) {
                h.append("<p class=\"muted\">没有已升级到组内的提醒。</p>");
            }
            for (var re : data.getAsJsonArray("reminders")) {
                JsonObject r = re.getAsJsonObject();
                h.append("<p><strong>").append(Util.escapeHtml(r.get("taskTitle").getAsString())).append("</strong>：检查点 ")
                        .append(Util.escapeHtml(r.get("checkpointAt").getAsString())).append("，私下提醒 ")
                        .append(Util.escapeHtml(r.get("firstPrivateReminderAt").getAsString())).append("，满 24 小时升级 ")
                        .append(Util.escapeHtml(r.get("groupFollowUpAt").getAsString()))
                        .append(r.get("followUpResolvedAt").isJsonNull() ? "；仍未回应。" : "；已回应。").append("</p>");
            }
        }

        if (includeDisputes) {
            h.append("<h2>六、举报材料：事实、陈述与待解决分歧</h2>");
            if (data.getAsJsonArray("disputes").isEmpty()) {
                h.append("<p class=\"muted\">没有举报材料。</p>");
            }
            for (var de : data.getAsJsonArray("disputes")) {
                JsonObject d = de.getAsJsonObject();
                h.append("<h3>举报 #").append(d.get("id").getAsInt()).append("：")
                        .append(Util.escapeHtml(d.get("taskTitle").getAsString())).append("</h3>");
                h.append("<div class=\"fact\"><strong>已记录的事实：</strong>发起人 ")
                        .append(Util.escapeHtml(d.get("reporter").getAsString())).append("，时间 ")
                        .append(Util.escapeHtml(d.get("createdAt").getAsString())).append("。</div>");
                JsonObject facts = d.getAsJsonObject("facts");
                JsonObject plan = facts.getAsJsonObject("plan");
                h.append("<div class=\"fact\"><strong>举报时的分工：</strong>负责人 ").append(escaped(plan, "assignee"))
                        .append(" · 署名 ");
                List<String> names = new ArrayList<>();
                for (var ae : plan.getAsJsonArray("authors")) {
                    JsonObject a = ae.getAsJsonObject();
                    names.add(escaped(a, "name") + " " + fmt(a.get("sharePct").getAsDouble()) + "%");
                }
                h.append(names.isEmpty() ? "资料缺失" : String.join("；", names)).append(" · 检查点 ")
                        .append(escaped(plan, "checkpointAt")).append(" · 截止 ").append(escaped(plan, "currentDeadlineAt"));
                if (plan.has("historyNote")) h.append(" · ").append(escaped(plan, "historyNote"));
                h.append("</div>");
                for (var re : facts.getAsJsonArray("reassignments")) {
                    JsonObject r = re.getAsJsonObject();
                    h.append("<p>交接 ").append(escaped(r, "createdAt")).append(" · ").append(escaped(r, "fromName"))
                            .append(" → ").append(escaped(r, "toName")).append(" · ").append(escaped(r, "reason")).append("</p>");
                }
                for (var ce : facts.getAsJsonArray("dateChanges")) {
                    JsonObject dChange = ce.getAsJsonObject();
                    h.append("<p>延期 ").append(escaped(dChange, "createdAt")).append(" · ")
                            .append(escaped(dChange, "oldDeadline")).append(" → ").append(escaped(dChange, "newDeadline"))
                            .append(" · ").append(statusLabel(dChange.get("status").getAsString())).append(" · ")
                            .append(escaped(dChange, "reason")).append("</p>");
                }
                h.append("<table><tbody>");
                for (var ve : facts.getAsJsonArray("versions")) {
                    JsonObject v = ve.getAsJsonObject();
                    h.append("<tr><td colspan=\"5\">v").append(v.get("version").getAsInt()).append(" · ")
                            .append(statusLabel(v.get("status").getAsString()));
                    if (!v.get("withdrawalReason").isJsonNull()) {
                        h.append(" · ").append(escaped(v, "finalizedAt")).append(" · ").append(escaped(v, "withdrawalReason"));
                    }
                    h.append("</td></tr>");
                    appendExportArtifact(h, v, projectId, userId);
                }
                h.append("</tbody></table>");
                h.append("<div class=\"statement\"><strong>成员陈述：</strong>")
                        .append(Util.escapeHtml(d.get("reason").getAsString())).append("（举报发起）</div>");
                for (var se : d.getAsJsonArray("statements")) {
                    JsonObject s = se.getAsJsonObject();
                    h.append("<div class=\"statement\"><strong>")
                            .append("response".equals(s.get("kind").getAsString()) ? "被涉及者回应" : "未涉事者复核")
                            .append(" · ").append(Util.escapeHtml(s.get("author").getAsString())).append("：</strong>")
                            .append(Util.escapeHtml(s.get("body").getAsString())).append("</div>");
                }
                if (d.getAsJsonArray("unresolved").isEmpty()) {
                    h.append("<p class=\"muted\">该举报已标记结案。</p>");
                } else {
                    h.append("<div class=\"unresolved\"><strong>待解决的分歧：</strong><ul>");
                    for (var ue : d.getAsJsonArray("unresolved")) {
                        h.append("<li>").append(Util.escapeHtml(ue.getAsString())).append("</li>");
                    }
                    h.append("</ul></div>");
                }
            }
        }

        h.append("<h2>附录：可独立复算的原始计分数据</h2>");
        h.append("<p class=\"muted\">下面的 JSON 只含已验收任务的分值、冻结署名比例和最终通过版本，不含任何个人投票选择、票数或投票时间。")
                .append("可用它独立重算上方百分比。</p>");
        h.append("<pre id=\"data-appendix\">").append(Util.escapeHtml(data.getAsJsonObject("dataAppendix").toString()))
                .append("</pre>");
        h.append("<script type=\"application/json\" id=\"report-data\">")
                .append(Util.escapeJsonForHtml(data.toString())).append("</script>");
        h.append("<p class=\"muted\">报告由 Anti-freerider 在本地生成。公开成果版本可以显示提交与最终验收日期；个人投票时间始终保密。</p>");
        h.append("</body></html>");
        return h.toString();
    }

    private static void appendBudget(StringBuilder html, JsonObject budget) {
        int remaining = budget.get("remainingPoints").getAsInt();
        html.append("<p class=\"muted\">计划 ").append(budget.get("plannedPoints").getAsInt())
                .append(" 分 · 已分配 ").append(budget.get("assignedPoints").getAsInt()).append(" 分 · 剩余 ")
                .append(remaining).append(" 分");
        if (remaining < 0) html.append(" · 已超额 ").append(-remaining).append(" 分，请修订计划分");
        html.append("</p>");
    }

    private static String escaped(JsonObject obj, String key) {
        return !obj.has(key) || obj.get(key).isJsonNull() ? "资料缺失" : Util.escapeHtml(obj.get(key).getAsString());
    }

    private static void appendExportArtifact(StringBuilder html, JsonObject version, int projectId, int userId)
            throws SQLException {
        html.append("<tr><td colspan=\"5\">");
        if (!version.get("content").getAsString().isBlank()) html.append("<p>").append(escaped(version, "content")).append("</p>");
        if (!version.get("artifactText").getAsString().isBlank()) {
            html.append("<pre>").append(escaped(version, "artifactText")).append("</pre>");
        }
        if (version.has("attachmentUrl")) {
            try (Connection c = Db.open(); PreparedStatement ps = c.prepareStatement(
                    "SELECT artifact_name,pdf FROM submissions WHERE id=? AND project_id=? AND pdf IS NOT NULL")) {
                Auth.requireMember(c, projectId, userId);
                ps.setInt(1, version.get("id").getAsInt());
                ps.setInt(2, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        html.append("<p><a download=\"").append(escaped(version, "artifactName"))
                                .append("\" href=\"data:application/pdf;base64,")
                                .append(java.util.Base64.getEncoder().encodeToString(rs.getBytes("pdf")))
                                .append("\">下载该版 PDF · ").append(escaped(version, "artifactName")).append("</a></p>");
                    }
                }
            }
        } else if (!version.get("artifactName").getAsString().isBlank()) {
            html.append("<p>文件名 ").append(escaped(version, "artifactName")).append("</p>");
        }
        html.append("</td></tr>");
    }

    private static String fmt(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001) return String.valueOf((long) Math.rint(value));
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private static String statusLabel(String status) {
        return switch (status) {
            case "pending" -> "待确认";
            case "accepted" -> "已通过";
            case "rejected" -> "未通过";
            case "withdrawn" -> "已撤回";
            case "superseded" -> "已被新版取代";
            case "approved" -> "已同意";
            case "rejected_extension" -> "未同意";
            case "open" -> "未结案";
            case "closed" -> "已结案";
            default -> status;
        };
    }
}
