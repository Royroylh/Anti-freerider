package af;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Task planning, submissions, confirmation, anonymous voting and scoring. */
final class Tasks {
    private Tasks() {}

    record TaskRow(int id, int projectId, int branchId, String branchName, String title, String description,
                   int assigneeId, String assigneeName, int points, String checkpointAt, String deadlineAt,
                   String expectedOutput, String criteria, String createdAt, String updatedAt) {}

    record Member(int id, String name) {}

    static TaskRow load(Connection c, int taskId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT t.id,t.project_id,t.branch_id,b.name AS branch_name,t.title,t.description,t.assignee_id," +
                        "u.name AS assignee_name,t.planned_points,t.checkpoint_at,t.deadline_at,t.expected_output," +
                        "t.acceptance_criteria,t.created_at,t.updated_at FROM tasks t " +
                        "JOIN branches b ON b.id=t.branch_id JOIN users u ON u.id=t.assignee_id WHERE t.id=?")) {
            ps.setInt(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApiException(404, "Task not found");
                return new TaskRow(rs.getInt("id"), rs.getInt("project_id"), rs.getInt("branch_id"),
                        rs.getString("branch_name"), rs.getString("title"), rs.getString("description"),
                        rs.getInt("assignee_id"), rs.getString("assignee_name"), rs.getInt("planned_points"),
                        rs.getString("checkpoint_at"), rs.getString("deadline_at"),
                        rs.getString("expected_output"), rs.getString("acceptance_criteria"),
                        rs.getString("created_at"), rs.getString("updated_at"));
            }
        }
    }

    static JsonObject create(Auth.User user, int projectId, JsonObject body) throws Exception {
        return Db.write(c -> {
            Auth.requireMember(c, projectId, user.id());
            int branchId = Util.integer(body, "branchId", -1, -1, Integer.MAX_VALUE);
            if (branchId <= 0) throw new ApiException(400, "Choose a branch");
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM branches WHERE id=? AND project_id=?")) {
                ps.setInt(1, branchId);
                ps.setInt(2, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ApiException(400, "Branch does not belong to this project");
                }
            }
            String title = Util.required(body, "title", 160);
            String description = Util.optional(body, "description", 4000);
            int assigneeId = Util.integer(body, "assigneeId", -1, -1, Integer.MAX_VALUE);
            if (!isMember(c, projectId, assigneeId)) throw new ApiException(400, "Assignee must be a project member");
            int points = Util.integer(body, "points", -1, 1, 1000);
            Instant checkpoint = Util.parseInstantInput(Util.str(body, "checkpointAt"), null);
            Instant deadline = Util.parseInstantInput(Util.str(body, "deadlineAt"), null);
            if (checkpoint == null || deadline == null) throw new ApiException(400, "Checkpoint and deadline are required");
            if (checkpoint.isAfter(deadline)) throw new ApiException(400, "Checkpoint must be on or before the deadline");
            String expected = Util.required(body, "expectedOutput", 2000);
            String criteria = Util.required(body, "acceptanceCriteria", 4000);
            Projects.requireAvailablePoints(c, projectId, branchId, points);
            List<JsonObject> authors = parseAuthors(body, c, projectId, assigneeId);
            JsonArray plan = new JsonArray();
            for (JsonObject author : authors) plan.add(author.deepCopy());

            int taskId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO tasks(project_id,branch_id,title,description,assignee_id,planned_points,checkpoint_at," +
                            "deadline_at,expected_output,acceptance_criteria,created_by,original_plan,created_at,updated_at) " +
                            "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, projectId);
                ps.setInt(2, branchId);
                ps.setString(3, title);
                ps.setString(4, description);
                ps.setInt(5, assigneeId);
                ps.setInt(6, points);
                ps.setString(7, checkpoint.toString());
                ps.setString(8, deadline.toString());
                ps.setString(9, expected);
                ps.setString(10, criteria);
                ps.setInt(11, user.id());
                ps.setString(12, plan.toString());
                ps.setString(13, Clock.nowIso());
                ps.setString(14, Clock.nowIso());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    taskId = keys.getInt(1);
                }
            }
            saveAuthors(c, taskId, authors);
            event(c, projectId, taskId, "created", user.id(), "project",
                    "创建了任务「" + title + "」，约定 " + points + " 分，负责人 " + nameOf(c, assigneeId) + "。",
                    new JsonObject().toString());
            return detailInTransaction(c, user, projectId, taskId);
        });
    }

    private static List<JsonObject> parseAuthors(JsonObject body, Connection c, int projectId, int assigneeId)
            throws SQLException {
        List<JsonObject> out = new ArrayList<>();
        double total = 0;
        if (body.has("authors") && !body.get("authors").isJsonArray()) {
            throw new ApiException(400, "Choose valid authors");
        }
        if (body.has("authors")) {
            for (var element : body.getAsJsonArray("authors")) {
                if (!element.isJsonObject()) throw new ApiException(400, "Invalid author entry");
                JsonObject author = element.getAsJsonObject();
                int userId = Util.integer(author, "userId", -1, -1, Integer.MAX_VALUE);
                double share = Util.decimal(author, "sharePct", -1, 0.01, 100);
                if (!isMember(c, projectId, userId)) throw new ApiException(400, "Authors must be project members");
                if (out.stream().anyMatch(o -> o.get("userId").getAsInt() == userId)) {
                    throw new ApiException(400, "Duplicate author");
                }
                total += share;
                JsonObject o = new JsonObject();
                o.addProperty("userId", userId);
                o.addProperty("name", nameOf(c, userId));
                o.addProperty("sharePct", share);
                out.add(o);
            }
        }
        if (out.isEmpty()) {
            if (assigneeId < 0) throw new ApiException(400, "Choose at least one author");
            JsonObject o = new JsonObject();
            o.addProperty("userId", assigneeId);
            o.addProperty("name", nameOf(c, assigneeId));
            o.addProperty("sharePct", 100.0);
            out.add(o);
            total = 100;
        }
        if (Math.abs(total - 100.0) > 0.0000001) {
            throw new ApiException(400, "Author shares must add up to 100%");
        }
        return out;
    }

    private static void saveAuthors(Connection c, int taskId, List<JsonObject> authors) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO task_authors(task_id,user_id,share_pct) VALUES(?,?,?)")) {
            for (JsonObject author : authors) {
                ps.setInt(1, taskId);
                ps.setInt(2, author.get("userId").getAsInt());
                ps.setDouble(3, author.get("sharePct").getAsDouble());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static JsonObject detail(Auth.User user, int projectId, int taskId) throws Exception {
        try (Connection c = Db.open()) {
            Auth.requireMember(c, projectId, user.id());
            TaskRow task = load(c, taskId);
            if (task.projectId() != projectId) throw new ApiException(404, "Task not found");
            JsonObject out = summary(c, task, user, projectId);
            JsonArray submissions = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM submissions WHERE task_id=? ORDER BY version DESC")) {
                ps.setInt(1, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) submissions.add(submissionJson(c, rs.getInt(1), user, projectId));
                }
            }
            out.add("submissions", submissions);
            out.add("events", events(c, projectId, taskId, user.id()));
            out.add("supports", supports(c, taskId));
            out.add("dateChanges", dateChanges(c, taskId));
            out.add("reassignments", reassignments(c, taskId));
            out.add("reports", Reports.summaries(c, projectId, taskId, user.id()));
            out.addProperty("memberCount", Auth.members(c, projectId).size());
            return out;
        }
    }

    static JsonObject summary(Auth.User user, int projectId, int taskId) throws Exception {
        try (Connection c = Db.open()) {
            Auth.requireMember(c, projectId, user.id());
            TaskRow task = load(c, taskId);
            if (task.projectId() != projectId) throw new ApiException(404, "Task not found");
            return summary(c, task, user, projectId);
        }
    }

    static JsonObject summary(Connection c, TaskRow t, Auth.User user, int projectId) throws SQLException {
        JsonObject o = new JsonObject();
        o.addProperty("id", t.id());
        o.addProperty("projectId", t.projectId());
        o.addProperty("branchId", t.branchId());
        o.addProperty("branchName", t.branchName());
        o.addProperty("title", t.title());
        o.addProperty("description", t.description());
        o.addProperty("assigneeId", t.assigneeId());
        o.addProperty("assigneeName", t.assigneeName());
        o.addProperty("points", t.points());
        o.addProperty("checkpointAt", t.checkpointAt());
        o.addProperty("deadlineAt", t.deadlineAt());
        o.addProperty("expectedOutput", t.expectedOutput());
        o.addProperty("acceptanceCriteria", t.criteria());
        o.addProperty("createdAt", t.createdAt());
        o.addProperty("updatedAt", t.updatedAt());

        JsonArray authors = new JsonArray();
        Set<Integer> authorIds = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ta.user_id,u.name,ta.share_pct FROM task_authors ta JOIN users u ON u.id=ta.user_id " +
                        "WHERE ta.task_id=? ORDER BY ta.share_pct DESC,ta.user_id")) {
            ps.setInt(1, t.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject a = new JsonObject();
                    a.addProperty("id", rs.getInt("user_id"));
                    a.addProperty("name", rs.getString("name"));
                    a.addProperty("sharePct", rs.getDouble("share_pct"));
                    authors.add(a);
                    authorIds.add(rs.getInt("user_id"));
                }
            }
        }
        o.add("planAuthors", authors);

        JsonObject score = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ts.points,ts.accepted_at,s.version,s.id AS sid FROM task_scores ts " +
                        "JOIN submissions s ON s.id=ts.submission_id WHERE ts.task_id=?")) {
            ps.setInt(1, t.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    score = new JsonObject();
                    score.addProperty("points", rs.getInt("points"));
                    score.addProperty("acceptedAt", rs.getString("accepted_at"));
                    score.addProperty("version", rs.getInt("version"));
                    score.addProperty("submissionId", rs.getInt("sid"));
                    o.add("score", score);
                }
            }
        }
        o.addProperty("accepted", score != null);

        int latestSubmissionId = 0;
        String latestStatus = null;
        int latestVersion = 0;
        String latestCreated = null;
        String latestSubmittedBy = null;
        String latestFinalized = null;
        String latestWithdrawalReason = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT s.id,s.status,s.version,s.created_at,s.finalized_at,s.withdrawal_reason,u.name FROM submissions s JOIN users u ON u.id=s.created_by " +
                        "WHERE s.task_id=? ORDER BY s.version DESC LIMIT 1")) {
            ps.setInt(1, t.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    latestSubmissionId = rs.getInt("id");
                    latestStatus = rs.getString("status");
                    latestVersion = rs.getInt("version");
                    latestCreated = rs.getString("created_at");
                    latestSubmittedBy = rs.getString("name");
                    latestFinalized = rs.getString("finalized_at");
                    latestWithdrawalReason = rs.getString("withdrawal_reason");
                }
            }
        }
        if (latestSubmissionId > 0) {
            JsonObject latest = new JsonObject();
            latest.addProperty("id", latestSubmissionId);
            latest.addProperty("version", latestVersion);
            latest.addProperty("status", latestStatus);
            latest.addProperty("createdAt", latestCreated);
            latest.addProperty("submittedBy", latestSubmittedBy);
            latest.addProperty("finalizedAt", latestFinalized);
            latest.addProperty("withdrawalReason", latestWithdrawalReason);
            if ("pending".equals(latestStatus)) {
                JsonObject latestActions = actions(c, latestSubmissionId, user.id(), projectId);
                latest.add("actions", latestActions);
                o.add("latestActions", latestActions.deepCopy());
            }
            o.add("latestSubmission", latest);
        }

        JsonObject activeSupport = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT s.id,s.kind,s.body,s.needed_support,s.created_at,u.name FROM task_support s " +
                        "JOIN users u ON u.id=s.user_id WHERE s.task_id=? AND s.resolved_at IS NULL " +
                        "ORDER BY s.created_at DESC LIMIT 1")) {
            ps.setInt(1, t.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    activeSupport = new JsonObject();
                    activeSupport.addProperty("id", rs.getInt("id"));
                    activeSupport.addProperty("kind", rs.getString("kind"));
                    activeSupport.addProperty("body", rs.getString("body"));
                    activeSupport.addProperty("neededSupport", rs.getString("needed_support"));
                    activeSupport.addProperty("createdAt", rs.getString("created_at"));
                    activeSupport.addProperty("userName", rs.getString("name"));
                    o.add("support", activeSupport);
                }
            }
        }
        o.addProperty("blocked", activeSupport != null);

        JsonObject followUp = null;
        if (!"accepted".equals(latestStatus)) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT r.follow_up_at,r.follow_up_resolved_at,r.assignee_id,r.created_at,u.name FROM reminders r " +
                            "JOIN users u ON u.id=r.assignee_id WHERE r.task_id=? ORDER BY r.created_at DESC LIMIT 1")) {
                ps.setInt(1, t.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getString("follow_up_at") != null
                            && rs.getString("follow_up_resolved_at") == null) {
                        followUp = new JsonObject();
                        followUp.addProperty("at", rs.getString("follow_up_at"));
                        followUp.addProperty("assigneeName", rs.getString("name"));
                        o.add("followUpData", followUp);
                    }
                }
            }
        }
        o.addProperty("followUp", followUp != null);

        boolean canSubmit = !"accepted".equals(latestStatus) && score == null
                && (t.assigneeId() == user.id() || authorIds.contains(user.id()));
        o.addProperty("canSubmit", canSubmit);

        String state;
        if (score != null) state = "accepted";
        else if (activeSupport != null) state = "blocked";
        else if ("pending".equals(latestStatus)) state = "pending_review";
        else if (followUp != null) state = "follow_up";
        else if ("rejected".equals(latestStatus) || "superseded".equals(latestStatus) || "withdrawn".equals(latestStatus)) state = "needs_revision";
        else state = "todo";
        o.addProperty("state", state);

        JsonObject pendingDate = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id,old_deadline,new_deadline,reason,created_at,status FROM date_changes " +
                        "WHERE task_id=? ORDER BY id DESC LIMIT 1")) {
            ps.setInt(1, t.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    pendingDate = new JsonObject();
                    pendingDate.addProperty("id", rs.getInt("id"));
                    pendingDate.addProperty("oldDeadline", rs.getString("old_deadline"));
                    pendingDate.addProperty("newDeadline", rs.getString("new_deadline"));
                    pendingDate.addProperty("reason", rs.getString("reason"));
                    pendingDate.addProperty("createdAt", rs.getString("created_at"));
                    pendingDate.addProperty("status", rs.getString("status"));
                    o.add("latestDateChange", pendingDate);
                }
            }
        }
        return o;
    }

    private static JsonObject actions(Connection c, int submissionId, int userId, int projectId) throws SQLException {
        JsonObject a = new JsonObject();
        String role = null;
        String confirmed = null;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT role FROM submission_eligible WHERE submission_id=? AND user_id=?")) {
            ps.setInt(1, submissionId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) role = rs.getString(1);
            }
        }
        boolean author = false;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT confirmed_at FROM submission_authors WHERE submission_id=? AND user_id=?")) {
            ps.setInt(1, submissionId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    author = true;
                    confirmed = rs.getString(1);
                }
            }
        }
        boolean voter = "voter".equals(role);
        int authors = count(c, "SELECT COUNT(*) FROM submission_eligible WHERE submission_id=? AND role='author'", submissionId);
        int voters = count(c, "SELECT COUNT(*) FROM submission_eligible WHERE submission_id=? AND role='voter'", submissionId);
        boolean allAuthors = authors == 0 && voters > 0;
        boolean voted = false;
        if (voter) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM votes WHERE submission_id=? AND voter_id=?")) {
                ps.setInt(1, submissionId);
                ps.setInt(2, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    voted = rs.next();
                }
            }
        }
        a.addProperty("role", role);
        a.addProperty("canConfirm", author && confirmed == null
                && ("author".equals(role) || (allAuthors && extraConfirmation(c, submissionId))));
        a.addProperty("canVote", voter && !voted);
        a.addProperty("canWithdraw", author);
        a.addProperty("confirmed", confirmed != null);
        a.addProperty("allAuthors", allAuthors);
        a.addProperty("inferenceWarning", authors == 1 && voters == 1
                ? "This is a two-person group. If you are the only non-author voter, the submitter may infer how you voted from the outcome."
                : "");
        return a;
    }

    private static boolean extraConfirmation(Connection c, int submissionId) throws SQLException {
        return count(c, "SELECT all_authors_confirm FROM submissions WHERE id=?", submissionId) == 1;
    }

    private static int count(Connection c, String sql, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    static JsonObject submissionJson(Connection c, int submissionId, Auth.User user, int projectId) throws SQLException {
        JsonObject o = new JsonObject();
        int taskId;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT s.id,s.task_id,s.version,s.content,s.artifact_name,s.artifact_text,s.criteria_snapshot," +
                        "s.status,s.created_at,s.finalized_at,s.points_snapshot,s.withdrawal_reason,s.pdf IS NOT NULL AS has_pdf,u.name FROM submissions s JOIN users u ON u.id=s.created_by " +
                        "WHERE s.id=?")) {
            ps.setInt(1, submissionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApiException(404, "Submission not found");
                taskId = rs.getInt("task_id");
                o.addProperty("id", rs.getInt("id"));
                o.addProperty("taskId", taskId);
                o.addProperty("version", rs.getInt("version"));
                o.addProperty("content", rs.getString("content"));
                o.addProperty("artifactName", rs.getString("artifact_name"));
                o.addProperty("artifactText", rs.getString("artifact_text"));
                o.addProperty("criteriaSnapshot", rs.getString("criteria_snapshot"));
                o.addProperty("status", rs.getString("status"));
                o.addProperty("createdAt", rs.getString("created_at"));
                o.addProperty("finalizedAt", rs.getString("finalized_at"));
                o.addProperty("submittedBy", rs.getString("name"));
                o.addProperty("points", rs.getInt("points_snapshot"));
                o.addProperty("withdrawalReason", rs.getString("withdrawal_reason"));
                if (rs.getBoolean("has_pdf")) o.addProperty("attachmentUrl", attachmentPath(projectId, taskId, submissionId));
            }
        }
        JsonArray authors = new JsonArray();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT sa.user_id,sa.name_snapshot,sa.share_pct,sa.confirmed_at FROM submission_authors sa " +
                        "WHERE sa.submission_id=? ORDER BY sa.share_pct DESC,sa.user_id")) {
            ps.setInt(1, submissionId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject a = new JsonObject();
                    a.addProperty("id", rs.getInt("user_id"));
                    a.addProperty("name", rs.getString("name_snapshot"));
                    a.addProperty("sharePct", rs.getDouble("share_pct"));
                    a.addProperty("confirmed", rs.getString("confirmed_at") != null);
                    authors.add(a);
                }
            }
        }
        o.add("authors", authors);
        if ("pending".equals(o.get("status").getAsString())) {
            o.add("actions", actions(c, submissionId, user.id(), projectId));
        }
        return o;
    }

    static JsonObject submit(Auth.User user, int projectId, int taskId, JsonObject body) throws Exception {
        String content = Util.optional(body, "content", 8000);
        String artifactName = Util.optional(body, "artifactName", 200);
        String artifactText = Util.optional(body, "artifactText", 20000);
        return Db.write(c -> {
            Auth.requireMember(c, projectId, user.id());
            TaskRow task = load(c, taskId);
            if (task.projectId() != projectId) throw new ApiException(404, "Task not found");
            if (scored(c, taskId)) throw new ApiException(409, "This task already has an accepted, scored version");
            List<JsonObject> authors = planAuthors(c, taskId);
            boolean isAuthor = authors.stream().anyMatch(a -> a.get("userId").getAsInt() == user.id());
            if (!isAuthor && task.assigneeId() != user.id()) {
                throw new ApiException(403, "Only the assignee or a co-author can submit this task");
            }
            if (count(c, "SELECT COUNT(*) FROM submissions WHERE task_id=? AND status='pending'", taskId) > 0) {
                throw new ApiException(409, "本任务已有待确认版本，请本版署名作者先撤回整轮验收。");
            }
            for (JsonObject author : authors) {
                if (!isMember(c, projectId, author.get("userId").getAsInt())) {
                    throw new ApiException(409, "请先更新新版署名，包含的作者须仍在项目中。");
                }
            }
            if (authors.isEmpty()) throw new ApiException(409, "请先为新版安排署名作者。");
            Instant now = Clock.now();
            int version;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(MAX(version),0)+1 FROM submissions WHERE task_id=?")) {
                ps.setInt(1, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    version = rs.getInt(1);
                }
            }
            byte[] pdf = submissionPdf(Util.str(body, "pdfBase64"), artifactName);
            if (pdf == null && !hasArtifactText(artifactText)) {
                throw new ApiException(400, "请粘贴实际成果正文或上传有效 PDF。说明、文件名和链接仅作辅助。");
            }
            int submissionId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO submissions(project_id,task_id,version,content,artifact_name,artifact_text," +
                            "criteria_snapshot,status,created_by,created_at,points_snapshot,pdf) VALUES(?,?,?,?,?,?,?,'pending',?,?,?,?)",
                    PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setInt(1, projectId);
                ps.setInt(2, taskId);
                ps.setInt(3, version);
                ps.setString(4, content);
                ps.setString(5, artifactName);
                ps.setString(6, artifactText);
                ps.setString(7, task.criteria());
                ps.setInt(8, user.id());
                ps.setString(9, now.toString());
                ps.setInt(10, task.points());
                ps.setBytes(11, pdf);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    submissionId = keys.getInt(1);
                }
            }
            List<Auth.User> members = Auth.members(c, projectId);
            Set<Integer> authorIds = new HashSet<>();
            for (JsonObject author : authors) authorIds.add(author.get("userId").getAsInt());
            boolean allAuthors = members.size() == authors.size()
                    && members.stream().allMatch(m -> authorIds.contains(m.id()));
            for (JsonObject author : authors) {
                int authorId = author.get("userId").getAsInt();
                String confirmedAt = (authorId == user.id()) ? now.toString() : null;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO submission_authors(submission_id,user_id,name_snapshot,share_pct,confirmed_at) " +
                                "VALUES(?,?,?,?,?)")) {
                    ps.setInt(1, submissionId);
                    ps.setInt(2, authorId);
                    ps.setString(3, author.get("name").getAsString());
                    ps.setDouble(4, author.get("sharePct").getAsDouble());
                    ps.setString(5, confirmedAt);
                    ps.executeUpdate();
                }
            }
            if (allAuthors) {
                for (Auth.User member : members) addEligible(c, submissionId, member.id(), "voter");
            } else {
                for (JsonObject author : authors) {
                    addEligible(c, submissionId, author.get("userId").getAsInt(), "author");
                }
                for (Auth.User member : members) {
                    if (!authorIds.contains(member.id())) addEligible(c, submissionId, member.id(), "voter");
                }
            }
            event(c, projectId, taskId, "submitted", user.id(), "project",
                    "提交了版本 v" + version + "，验收标准与署名已冻结。",
                    "{\"submissionId\":" + submissionId + ",\"version\":" + version + "}");
            if (!allAuthors) finalizeIfComplete(c, submissionId, projectId);
            return detailInTransaction(c, user, projectId, taskId);
        });
    }

    private static void addEligible(Connection c, int submissionId, int userId, String role) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO submission_eligible(submission_id,user_id,role) VALUES(?,?,?)")) {
            ps.setInt(1, submissionId);
            ps.setInt(2, userId);
            ps.setString(3, role);
            ps.executeUpdate();
        }
    }

    static List<JsonObject> planAuthors(Connection c, int taskId) throws SQLException {
        List<JsonObject> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT ta.user_id,u.name,ta.share_pct FROM task_authors ta JOIN users u ON u.id=ta.user_id " +
                        "WHERE ta.task_id=? ORDER BY ta.share_pct DESC,ta.user_id")) {
            ps.setInt(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject a = new JsonObject();
                    a.addProperty("userId", rs.getInt("user_id"));
                    a.addProperty("name", rs.getString("name"));
                    a.addProperty("sharePct", rs.getDouble("share_pct"));
                    out.add(a);
                }
            }
        }
        return out;
    }

    static JsonObject confirm(Auth.User user, int projectId, int taskId, int submissionId) throws Exception {
        return Db.write(c -> {
            Auth.requireMember(c, projectId, user.id());
            TaskRow task = load(c, taskId);
            if (task.projectId() != projectId) throw new ApiException(404, "Task not found");
            SubmissionState state = submissionState(c, submissionId, taskId);
            if (state == null) throw new ApiException(404, "Submission not found");
            if (!"pending".equals(state.status())) throw new ApiException(409, "This submission is no longer open");
            String role = roleOf(c, submissionId, user.id());
            if (!"author".equals(role) && (!"voter".equals(role) || !extraConfirmation(c, submissionId)
                    || !isFrozenAuthor(c, submissionId, user.id()))) {
                throw new ApiException(403, "You are not a frozen author of this version");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE submission_authors SET confirmed_at=? WHERE submission_id=? AND user_id=? AND confirmed_at IS NULL")) {
                ps.setString(1, Clock.nowIso());
                ps.setInt(2, submissionId);
                ps.setInt(3, user.id());
                if (ps.executeUpdate() == 0) throw new ApiException(409, "You already confirmed this version");
            }
            event(c, projectId, taskId, "author_confirmed", user.id(), "project",
                    user.name() + " 确认了版本 v" + state.version() + "。",
                    "{\"submissionId\":" + submissionId + ",\"version\":" + state.version() + "}");
            finalizeIfComplete(c, submissionId, projectId);
            return detailInTransaction(c, user, projectId, taskId);
        });
    }

    static JsonObject vote(Auth.User user, int projectId, int taskId, int submissionId, JsonObject body) throws Exception {
        String choice = Util.str(body, "choice");
        if (!"agree".equals(choice) && !"disagree".equals(choice)) {
            throw new ApiException(400, "Choice must be agree or disagree");
        }
        return Db.write(c -> {
            Auth.requireMember(c, projectId, user.id());
            TaskRow task = load(c, taskId);
            if (task.projectId() != projectId) throw new ApiException(404, "Task not found");
            SubmissionState state = submissionState(c, submissionId, taskId);
            if (state == null) throw new ApiException(404, "Submission not found");
            if (!"pending".equals(state.status())) throw new ApiException(409, "This submission is no longer open");
            String role = roleOf(c, submissionId, user.id());
            if (!"voter".equals(role)) throw new ApiException(403, "Only frozen non-author voters can vote on this version");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO votes(submission_id,voter_id,choice,created_at) VALUES(?,?,?,?)")) {
                ps.setInt(1, submissionId);
                ps.setInt(2, user.id());
                ps.setString(3, choice);
                ps.setString(4, Clock.nowIso());
                ps.executeUpdate();
            } catch (SQLException ex) {
                if (String.valueOf(ex.getMessage()).contains("UNIQUE")) {
                    throw new ApiException(409, "You have already voted on this version");
                }
                throw ex;
            }
            finalizeIfComplete(c, submissionId, projectId);
            return detailInTransaction(c, user, projectId, taskId);
        });
    }

    static JsonObject withdraw(Auth.User user, int projectId, int taskId, int submissionId, JsonObject body)
            throws Exception {
        String reason = Util.required(body, "reason", 2000);
        return Db.write(c -> {
            Auth.requireMember(c, projectId, user.id());
            if (load(c, taskId).projectId() != projectId) throw new ApiException(404, "Task not found");
            SubmissionState state = submissionState(c, submissionId, taskId);
            if (state == null) throw new ApiException(404, "Submission not found");
            if (!isFrozenAuthor(c, submissionId, user.id())) {
                throw new ApiException(403, "只有仍在组内的本版署名作者可以撤回验收。");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE submissions SET status='withdrawn',finalized_at=?,withdrawal_reason=? " +
                            "WHERE id=? AND status='pending'")) {
                ps.setString(1, Clock.nowIso());
                ps.setString(2, reason);
                ps.setInt(3, submissionId);
                if (ps.executeUpdate() == 0) throw new ApiException(409, "本轮验收已经结束。");
            }
            event(c, projectId, taskId, "withdrawn", user.id(), "project",
                    "版本 v" + state.version() + " 已撤回。原因：" + reason,
                    "{\"submissionId\":" + submissionId + "}");
            return detailInTransaction(c, user, projectId, taskId);
        });
    }

    private static boolean isFrozenAuthor(Connection c, int submissionId, int userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM submission_authors WHERE submission_id=? AND user_id=?")) {
            ps.setInt(1, submissionId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static String attachmentPath(int projectId, int taskId, int submissionId) {
        return "/api/projects/" + projectId + "/tasks/" + taskId + "/submissions/" + submissionId + "/attachment";
    }

    record Attachment(String filename, byte[] pdf) {}

    static Attachment attachment(Auth.User user, int projectId, int taskId, int submissionId) throws SQLException {
        try (Connection c = Db.open(); PreparedStatement ps = c.prepareStatement(
                "SELECT artifact_name,pdf FROM submissions WHERE id=? AND task_id=? AND project_id=? AND pdf IS NOT NULL")) {
            Auth.requireMember(c, projectId, user.id());
            ps.setInt(1, submissionId);
            ps.setInt(2, taskId);
            ps.setInt(3, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApiException(404, "Attachment not found");
                return new Attachment(rs.getString("artifact_name"), rs.getBytes("pdf"));
            }
        }
    }

    private static boolean hasArtifactText(String text) {
        String withoutLinks = text.replaceAll("(?i)(?:https?://|www\\.)\\S+", "").strip();
        return !withoutLinks.isBlank()
                && !withoutLinks.matches("(?is).{1,180}\\.(pdf|docx?|md|txt|xlsx?|png|jpe?g|zip)")
                && !withoutLinks.matches("(?i)(已?完成了?|已?提交|做完了?|见附件|done|finished|completed|see attached)[。.!！\\s]*");
    }

    private static byte[] submissionPdf(String encoded, String filename) {
        if (encoded == null || encoded.isEmpty()) return null;
        if (encoded.length() > 1_400_000) throw new ApiException(413, "PDF 附件不能超过 1 MiB。");
        byte[] pdf;
        try {
            pdf = java.util.Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(400, "PDF 附件编码无效。");
        }
        if (pdf.length > 1_048_576) throw new ApiException(413, "PDF 附件不能超过 1 MiB。");
        if (!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf") || !validPdf(pdf)) {
            throw new ApiException(400, "请选择有效的 PDF 文件。");
        }
        return pdf;
    }

    private static boolean validPdf(byte[] pdf) {
        String file = new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (!file.startsWith("%PDF-") || !file.stripTrailing().endsWith("%%EOF")) return false;
        int start = file.lastIndexOf("startxref");
        if (start < 0) return false;
        try {
            int offset = Integer.parseInt(file.substring(start + 9, file.lastIndexOf("%%EOF")).strip());
            if (offset < 0 || offset >= start) return false;
            String reference = file.substring(offset, Math.min(start, offset + 128));
            return reference.startsWith("xref") || reference.matches("(?s)^\\d+\\s+\\d+\\s+obj\\b.*");
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    record SubmissionState(int id, int taskId, int version, String status) {}

    private static SubmissionState submissionState(Connection c, int submissionId, int taskId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id,task_id,version,status FROM submissions WHERE id=? AND task_id=?")) {
            ps.setInt(1, submissionId);
            ps.setInt(2, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new SubmissionState(rs.getInt("id"), rs.getInt("task_id"),
                        rs.getInt("version"), rs.getString("status"));
            }
        }
    }

    private static String roleOf(Connection c, int submissionId, int userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT role FROM submission_eligible WHERE submission_id=? AND user_id=?")) {
            ps.setInt(1, submissionId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Finalizes only when every frozen author has confirmed and every frozen
     * voter has voted. Until then the submission remains "pending", including
     * when a disagreement already exists.
     */
    private static void finalizeIfComplete(Connection c, int submissionId, int projectId) throws SQLException {
        SubmissionState state = submissionStateLoose(c, submissionId);
        if (state == null || !"pending".equals(state.status())) return;
        int unconfirmedAuthors;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM submission_eligible se JOIN submission_authors sa " +
                        "ON sa.submission_id=se.submission_id AND sa.user_id=se.user_id " +
                        "WHERE se.submission_id=? AND sa.confirmed_at IS NULL " +
                        "AND (se.role='author' OR EXISTS (SELECT 1 FROM submissions s " +
                        "WHERE s.id=se.submission_id AND s.all_authors_confirm=1))")) {
            ps.setInt(1, submissionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                unconfirmedAuthors = rs.getInt(1);
            }
        }
        String status;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT COUNT(*), COUNT(v.id), COALESCE(SUM(v.choice='disagree'),0)
                FROM submission_eligible se LEFT JOIN votes v
                    ON v.submission_id=se.submission_id AND v.voter_id=se.user_id
                WHERE se.submission_id=? AND se.role='voter'
                """)) {
            ps.setInt(1, submissionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                status = collectionStatus(unconfirmedAuthors, rs.getInt(1), rs.getInt(2), rs.getInt(3));
            }
        }
        if ("pending".equals(status)) return;
        boolean accepted = "accepted".equals(status);
        String now = Clock.nowIso();
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE submissions SET status=?,finalized_at=? WHERE id=? AND status='pending'")) {
            ps.setString(1, status);
            ps.setString(2, now);
            ps.setInt(3, submissionId);
            if (ps.executeUpdate() == 0) return;
        }
        int taskId = state.taskId();
        if (accepted) {
            int points = count(c, "SELECT points_snapshot FROM submissions WHERE id=?", submissionId);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO task_scores(task_id,submission_id,points,accepted_at) VALUES(?,?,?,?)")) {
                ps.setInt(1, taskId);
                ps.setInt(2, submissionId);
                ps.setInt(3, points);
                ps.setString(4, now);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE reminders SET resolved_at=COALESCE(resolved_at,?), " +
                            "follow_up_resolved_at=COALESCE(follow_up_resolved_at,?) WHERE task_id=?")) {
                ps.setString(1, now);
                ps.setString(2, now);
                ps.setInt(3, taskId);
                ps.executeUpdate();
            }
            event(c, projectId, taskId, "accepted", null, "project",
                    "版本 v" + state.version() + " 已收齐确认，全票同意，验收通过并计分一次。",
                    "{\"submissionId\":" + submissionId + ",\"version\":" + state.version() + "}");
        } else {
            event(c, projectId, taskId, "rejected", null, "project",
                    "版本 v" + state.version() + " 已收齐确认，未获全票同意，本版不计分。",
                    "{\"submissionId\":" + submissionId + ",\"version\":" + state.version() + "}");
        }
    }

    static String collectionStatus(int unconfirmedAuthors, int eligibleVoters, int ballots, int dissent) {
        if (unconfirmedAuthors > 0 || eligibleVoters == 0 || ballots < eligibleVoters) return "pending";
        return dissent == 0 ? "accepted" : "rejected";
    }

    private static SubmissionState submissionStateLoose(Connection c, int submissionId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id,task_id,version,status FROM submissions WHERE id=?")) {
            ps.setInt(1, submissionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new SubmissionState(rs.getInt("id"), rs.getInt("task_id"),
                        rs.getInt("version"), rs.getString("status"));
            }
        }
    }

    private static boolean scored(Connection c, int taskId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM task_scores WHERE task_id=?")) {
            ps.setInt(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static JsonObject addSupport(Auth.User user, int projectId, int taskId, JsonObject body) throws Exception {
        String kind = Util.str(body, "kind");
        if (!"help".equals(kind) && !"blocker".equals(kind)) throw new ApiException(400, "Kind must be help or blocker");
        String text = Util.required(body, "body", 2000);
        String needed = Util.required(body, "neededSupport", 1000);
        return Db.write(c -> {
            Auth.requireMember(c, projectId, user.id());
            TaskRow task = load(c, taskId);
            if (task.projectId() != projectId) throw new ApiException(404, "Task not found");
            if (scored(c, taskId)) throw new ApiException(409, "This task is already accepted");
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO task_support(project_id,task_id,user_id,kind,body,needed_support,created_at) " +
                            "VALUES(?,?,?,?,?,?,?)")) {
                ps.setInt(1, projectId);
                ps.setInt(2, taskId);
                ps.setInt(3, user.id());
                ps.setString(4, kind);
                ps.setString(5, text);
                ps.setString(6, needed);
                ps.setString(7, Clock.nowIso());
                ps.executeUpdate();
            }
            event(c, projectId, taskId, kind.equals("help") ? "help_requested" : "blocker_recorded", user.id(),
                    "project", kind.equals("help") ? "请求组内帮助：" + text : "记录阻碍：" + text,
                    new JsonObject().toString());
            return detailInTransaction(c, user, projectId, taskId);
        });
    }

    static JsonObject resolveSupport(Auth.User user, int projectId, int taskId, int supportId) throws Exception {
        return Db.write(c -> {
            Auth.requireMember(c, projectId, user.id());
            TaskRow task = load(c, taskId);
            if (task.projectId() != projectId) throw new ApiException(404, "Task not found");
            int owner;
            try (PreparedStatement ps = c.prepareStatement("SELECT user_id FROM task_support WHERE id=? AND task_id=?")) {
                ps.setInt(1, supportId);
                ps.setInt(2, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ApiException(404, "Support record not found");
                    owner = rs.getInt(1);
                }
            }
            if (owner != user.id() && !Auth.isOwner(c, projectId, user.id())) {
                throw new ApiException(403, "Only the person who asked or the project owner can resolve this");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE task_support SET resolved_at=?,resolved_by=? WHERE id=? AND resolved_at IS NULL")) {
                ps.setString(1, Clock.nowIso());
                ps.setInt(2, user.id());
                ps.setInt(3, supportId);
                ps.executeUpdate();
            }
            event(c, projectId, taskId, "support_resolved", user.id(), "project",
                    "所需支持已处理，解除受阻状态。", new JsonObject().toString());
            return detailInTransaction(c, user, projectId, taskId);
        });
    }

    static JsonObject note(Auth.User user, int projectId, int taskId, JsonObject body) throws Exception {
        String text = Util.required(body, "body", 2000);
        String type = Util.bool(body, "ack", false) ? "ack" : "note";
        return Db.write(c -> {
            Auth.requireMember(c, projectId, user.id());
            TaskRow task = load(c, taskId);
            if (task.projectId() != projectId) throw new ApiException(404, "Task not found");
            event(c, projectId, taskId, type, user.id(), "project", text, new JsonObject().toString());
            return detailInTransaction(c, user, projectId, taskId);
        });
    }

    static JsonObject requestExtension(Auth.User user, int projectId, int taskId, JsonObject body) throws Exception {
        Instant newDeadline = Util.parseInstantInput(Util.str(body, "newDeadline"), null);
        if (newDeadline == null) throw new ApiException(400, "New deadline is required");
        String reason = Util.required(body, "reason", 2000);
        return Db.write(c -> {
            Auth.requireMember(c, projectId, user.id());
            TaskRow task = load(c, taskId);
            if (task.projectId() != projectId) throw new ApiException(404, "Task not found");
            if (scored(c, taskId)) throw new ApiException(409, "This task is already accepted");
            if (!newDeadline.isAfter(Instant.parse(task.deadlineAt()))) {
                throw new ApiException(400, "The proposed deadline must be after the current deadline");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO date_changes(project_id,task_id,old_deadline,new_deadline,reason,requested_by,created_at) " +
                            "VALUES(?,?,?,?,?,?,?)")) {
                ps.setInt(1, projectId);
                ps.setInt(2, taskId);
                ps.setString(3, task.deadlineAt());
                ps.setString(4, newDeadline.toString());
                ps.setString(5, reason);
                ps.setInt(6, user.id());
                ps.setString(7, Clock.nowIso());
                ps.executeUpdate();
            }
            event(c, projectId, taskId, "extension_requested", user.id(), "project",
                    "申请延期至 " + newDeadline + "，原因：" + reason, new JsonObject().toString());
            return detailInTransaction(c, user, projectId, taskId);
        });
    }

    static JsonObject decideExtension(Auth.User user, int projectId, int taskId, int changeId, JsonObject body)
            throws Exception {
        boolean approve = Util.bool(body, "approve", false);
        String decisionNote = Util.optional(body, "decisionNote", 1000);
        return Db.write(c -> {
            Auth.requireMember(c, projectId, user.id());
            Auth.requireOwner(c, projectId, user.id());
            TaskRow task = load(c, taskId);
            if (task.projectId() != projectId) throw new ApiException(404, "Task not found");
            String oldDeadline;
            String newDeadline;
            String status;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT old_deadline,new_deadline,status FROM date_changes WHERE id=? AND task_id=?")) {
                ps.setInt(1, changeId);
                ps.setInt(2, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ApiException(404, "Extension request not found");
                    oldDeadline = rs.getString("old_deadline");
                    newDeadline = rs.getString("new_deadline");
                    status = rs.getString("status");
                }
            }
            if (!"pending".equals(status)) throw new ApiException(409, "This extension request was already decided");
            String newStatus = approve ? "approved" : "rejected";
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE date_changes SET status=?,decided_by=?,decided_at=?,decision_note=? WHERE id=?")) {
                ps.setString(1, newStatus);
                ps.setInt(2, user.id());
                ps.setString(3, Clock.nowIso());
                ps.setString(4, decisionNote);
                ps.setInt(5, changeId);
                ps.executeUpdate();
            }
            if (approve) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE tasks SET deadline_at=?,updated_at=? WHERE id=?")) {
                    ps.setString(1, newDeadline);
                    ps.setString(2, Clock.nowIso());
                    ps.setInt(3, taskId);
                    ps.executeUpdate();
                }
            }
            event(c, projectId, taskId, approve ? "extension_approved" : "extension_rejected", user.id(), "project",
                    (approve ? "同意延期，原截止 " + oldDeadline + " 改为 " + newDeadline
                            : "未同意延期，保留原截止 " + oldDeadline) + (decisionNote.isEmpty() ? "" : "；说明：" + decisionNote),
                    new JsonObject().toString());
            return detailInTransaction(c, user, projectId, taskId);
        });
    }

    static JsonObject reassign(Auth.User user, int projectId, int taskId, JsonObject body) throws Exception {
        int newAssignee = Util.integer(body, "assigneeId", -1, -1, Integer.MAX_VALUE);
        String reason = Util.required(body, "reason", 2000);
        return Db.write(c -> {
            Auth.requireMember(c, projectId, user.id());
            Auth.requireOwner(c, projectId, user.id());
            TaskRow task = load(c, taskId);
            if (task.projectId() != projectId) throw new ApiException(404, "Task not found");
            if (scored(c, taskId)) throw new ApiException(409, "This task is already accepted");
            if (!isMember(c, projectId, newAssignee)) throw new ApiException(400, "New assignee must be a member");
            if (newAssignee == task.assigneeId()) throw new ApiException(400, "Task is already assigned to this member");
            String original;
            try (PreparedStatement ps = c.prepareStatement("SELECT original_plan FROM tasks WHERE id=?")) {
                ps.setInt(1, taskId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    original = rs.getString(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO reassignments(project_id,task_id,from_user_id,to_user_id,actor_id,reason,original_plan,created_at) " +
                            "VALUES(?,?,?,?,?,?,?,?)")) {
                ps.setInt(1, projectId);
                ps.setInt(2, taskId);
                ps.setInt(3, task.assigneeId());
                ps.setInt(4, newAssignee);
                ps.setInt(5, user.id());
                ps.setString(6, reason);
                ps.setString(7, original);
                ps.setString(8, Clock.nowIso());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE tasks SET assignee_id=?,updated_at=? WHERE id=?")) {
                ps.setInt(1, newAssignee);
                ps.setString(2, Clock.nowIso());
                ps.setInt(3, taskId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE reminders SET resolved_at=COALESCE(resolved_at,?), " +
                            "follow_up_resolved_at=COALESCE(follow_up_resolved_at,?) WHERE task_id=?")) {
                ps.setString(1, Clock.nowIso());
                ps.setString(2, Clock.nowIso());
                ps.setInt(3, taskId);
                ps.executeUpdate();
            }
            event(c, projectId, taskId, "reassigned", user.id(), "project",
                    "重新分工：" + nameOf(c, task.assigneeId()) + " → " + nameOf(c, newAssignee) + "。原因：" + reason
                            + "。原约定保留在历史记录中。", new JsonObject().toString());
            return detailInTransaction(c, user, projectId, taskId);
        });
    }

    static JsonObject editAuthors(Auth.User user, int projectId, int taskId, JsonObject body) throws Exception {
        String reason = Util.required(body, "reason", 2000);
        return Db.write(c -> {
            Auth.requireOwner(c, projectId, user.id());
            TaskRow task = load(c, taskId);
            if (task.projectId() != projectId) throw new ApiException(404, "Task not found");
            if (scored(c, taskId)) throw new ApiException(409, "This task is already accepted");
            List<JsonObject> before = planAuthors(c, taskId);
            List<JsonObject> after = parseAuthors(body, c, projectId, -1);
            if (new HashSet<>(before).equals(new HashSet<>(after))) {
                throw new ApiException(400, "署名和比例没有变化。");
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM task_authors WHERE task_id=?")) {
                ps.setInt(1, taskId);
                ps.executeUpdate();
            }
            saveAuthors(c, taskId, after);
            JsonObject change = new JsonObject();
            change.add("before", Util.GSON.toJsonTree(before));
            change.add("after", Util.GSON.toJsonTree(after));
            change.addProperty("reason", reason);
            event(c, projectId, taskId, "authors_changed", user.id(), "project",
                    "新版署名：" + describeShares(before) + " → " + describeShares(after) + "。原因：" + reason,
                    change.toString());
            return detailInTransaction(c, user, projectId, taskId);
        });
    }

    private static String describeShares(List<JsonObject> authors) {
        List<String> shares = new ArrayList<>();
        for (JsonObject author : authors) {
            shares.add(author.get("name").getAsString() + " " + author.get("sharePct").getAsString() + "%");
        }
        return String.join("、", shares);
    }

    static JsonObject detailInTransaction(Connection c, Auth.User user, int projectId, int taskId) throws SQLException {
        TaskRow task = load(c, taskId);
        JsonObject out = summary(c, task, user, projectId);
        JsonArray submissions = new JsonArray();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM submissions WHERE task_id=? ORDER BY version DESC")) {
            ps.setInt(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) submissions.add(submissionJson(c, rs.getInt(1), user, projectId));
            }
        }
        out.add("submissions", submissions);
        out.add("events", events(c, projectId, taskId, user.id()));
        out.add("supports", supports(c, taskId));
        out.add("dateChanges", dateChanges(c, taskId));
        out.add("reassignments", reassignments(c, taskId));
        out.add("reports", Reports.summaries(c, projectId, taskId, user.id()));
        out.addProperty("memberCount", Auth.members(c, projectId).size());
        return out;
    }

    private static JsonArray events(Connection c, int projectId, int taskId, int viewerId) throws SQLException {
        JsonArray arr = new JsonArray();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT e.id,e.type,e.visibility,e.body,e.created_at,u.name FROM task_events e
                LEFT JOIN users u ON u.id=e.actor_id
                WHERE e.project_id=? AND e.task_id=? AND (
                    e.visibility='project' OR (e.type='reminder_private' AND EXISTS (
                        SELECT 1 FROM reminders r WHERE r.task_id=e.task_id
                        AND r.created_at=e.created_at AND r.assignee_id=?)))
                ORDER BY e.created_at,e.id
                """)) {
            ps.setInt(1, projectId);
            ps.setInt(2, taskId);
            ps.setInt(3, viewerId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject e = new JsonObject();
                    e.addProperty("id", rs.getInt("id"));
                    e.addProperty("type", rs.getString("type"));
                    e.addProperty("visibility", rs.getString("visibility"));
                    e.addProperty("body", rs.getString("body"));
                    e.addProperty("createdAt", rs.getString("created_at"));
                    e.addProperty("actorName", rs.getString("name") == null ? "System" : rs.getString("name"));
                    arr.add(e);
                }
            }
        }
        return arr;
    }

    static JsonArray supports(Connection c, int taskId) throws SQLException {
        JsonArray arr = new JsonArray();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT s.id,s.kind,s.body,s.needed_support,s.created_at,s.resolved_at,u.name FROM task_support s " +
                        "JOIN users u ON u.id=s.user_id WHERE s.task_id=? ORDER BY s.created_at DESC")) {
            ps.setInt(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", rs.getInt("id"));
                    o.addProperty("kind", rs.getString("kind"));
                    o.addProperty("body", rs.getString("body"));
                    o.addProperty("neededSupport", rs.getString("needed_support"));
                    o.addProperty("createdAt", rs.getString("created_at"));
                    o.addProperty("resolvedAt", rs.getString("resolved_at"));
                    o.addProperty("userName", rs.getString("name"));
                    arr.add(o);
                }
            }
        }
        return arr;
    }

    static JsonArray dateChanges(Connection c, int taskId) throws SQLException {
        JsonArray arr = new JsonArray();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT d.id,d.old_deadline,d.new_deadline,d.reason,d.status,d.created_at,d.decided_at,d.decision_note," +
                        "u.name AS requester,d2.name AS decider FROM date_changes d " +
                        "JOIN users u ON u.id=d.requested_by LEFT JOIN users d2 ON d2.id=d.decided_by " +
                        "WHERE d.task_id=? ORDER BY d.id DESC")) {
            ps.setInt(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", rs.getInt("id"));
                    o.addProperty("oldDeadline", rs.getString("old_deadline"));
                    o.addProperty("newDeadline", rs.getString("new_deadline"));
                    o.addProperty("reason", rs.getString("reason"));
                    o.addProperty("status", rs.getString("status"));
                    o.addProperty("createdAt", rs.getString("created_at"));
                    o.addProperty("decidedAt", rs.getString("decided_at"));
                    o.addProperty("decisionNote", rs.getString("decision_note"));
                    o.addProperty("requester", rs.getString("requester"));
                    o.addProperty("decider", rs.getString("decider"));
                    arr.add(o);
                }
            }
        }
        return arr;
    }

    static JsonArray reassignments(Connection c, int taskId) throws SQLException {
        JsonArray arr = new JsonArray();
        java.util.List<Object[]> raw = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT r.id,r.reason,r.created_at,r.original_plan,f.name AS from_name,t.name AS to_name,a.name AS actor_name " +
                        "FROM reassignments r JOIN users f ON f.id=r.from_user_id JOIN users t ON t.id=r.to_user_id " +
                        "JOIN users a ON a.id=r.actor_id WHERE r.task_id=? ORDER BY r.id DESC")) {
            ps.setInt(1, taskId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    raw.add(new Object[]{rs.getInt("id"), rs.getString("reason"), rs.getString("created_at"),
                            rs.getString("from_name"), rs.getString("to_name"), rs.getString("actor_name"),
                            rs.getString("original_plan")});
                }
            }
        }
        for (Object[] row : raw) {
            JsonObject o = new JsonObject();
            o.addProperty("id", (Integer) row[0]);
            o.addProperty("reason", (String) row[1]);
            o.addProperty("createdAt", (String) row[2]);
            o.addProperty("fromName", (String) row[3]);
            o.addProperty("toName", (String) row[4]);
            o.addProperty("actorName", (String) row[5]);
            String planRaw = (String) row[6];
            o.addProperty("originalPlan", planRaw);
            JsonArray readable = new JsonArray();
            try {
                var plan = JsonParser.parseString(planRaw);
                if (plan.isJsonArray()) {
                    for (var element : plan.getAsJsonArray()) {
                        if (!element.isJsonObject()) continue;
                        JsonObject entry = element.getAsJsonObject();
                        JsonObject item = new JsonObject();
                        int userId = entry.get("userId").getAsInt();
                        item.addProperty("userId", userId);
                        item.addProperty("name", nameOf(c, userId));
                        item.addProperty("sharePct", entry.get("sharePct").getAsDouble());
                        readable.add(item);
                    }
                }
            } catch (Exception ignored) {
                // Keep the raw snapshot even if an old row cannot be parsed.
            }
            o.add("originalPlanAuthors", readable);
            arr.add(o);
        }
        return arr;
    }

    static void event(Connection c, int projectId, int taskId, String type, Integer actorId, String visibility,
                      String body, String payload) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO task_events(project_id,task_id,type,actor_id,visibility,body,payload,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setInt(1, projectId);
            ps.setInt(2, taskId);
            ps.setString(3, type);
            if (actorId == null) ps.setNull(4, java.sql.Types.INTEGER);
            else ps.setInt(4, actorId);
            ps.setString(5, visibility);
            ps.setString(6, body);
            ps.setString(7, payload);
            ps.setString(8, Clock.nowIso());
            ps.executeUpdate();
        }
    }

    static boolean isMember(Connection c, int projectId, int userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM project_members WHERE project_id=? AND user_id=?")) {
            ps.setInt(1, projectId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static String nameOf(Connection c, int userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name FROM users WHERE id=?")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : "Unknown";
            }
        }
    }
}
