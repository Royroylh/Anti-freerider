package af;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Projects, branches, membership and the assembled workspace board. */
final class Projects {
    private Projects() {}

    record Budget(int planned, int assigned) {
        int remaining() { return planned - assigned; }

        void addTo(JsonObject target) {
            target.addProperty("plannedPoints", planned);
            target.addProperty("assignedPoints", assigned);
            target.addProperty("remainingPoints", remaining());
        }

        String shortfall(String label, int points) {
            return points <= remaining() ? "" : label + "剩余 " + remaining() + " 分，超出 " + (points - remaining()) + " 分";
        }
    }

    static Budget budget(Connection c, int projectId, Integer branchId) throws SQLException {
        String sql = branchId == null
                ? "SELECT planned_points,(SELECT COALESCE(SUM(t.planned_points),0) FROM tasks t WHERE t.project_id=p.id) " +
                  "FROM projects p WHERE id=?"
                : "SELECT planned_points,(SELECT COALESCE(SUM(t.planned_points),0) FROM tasks t WHERE t.branch_id=b.id) " +
                  "FROM branches b WHERE project_id=? AND id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, projectId);
            if (branchId != null) ps.setInt(2, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApiException(404, "Project or branch not found");
                return new Budget(rs.getInt(1), rs.getInt(2));
            }
        }
    }

    static void requireAvailablePoints(Connection c, int projectId, int branchId, int points) throws SQLException {
        String project = budget(c, projectId, null).shortfall("项目", points);
        String branch = budget(c, projectId, branchId).shortfall("分支", points);
        if (!project.isEmpty() || !branch.isEmpty()) {
            String gap = String.join("；", java.util.stream.Stream.of(project, branch).filter(s -> !s.isEmpty()).toList());
            throw new ApiException(409, gap + "。请降低任务分，或先修订计划分。");
        }
    }

    static JsonObject revisePlan(Auth.User user, int projectId, JsonObject body) throws Exception {
        String reason = Util.required(body, "reason", 2000);
        int planned = Util.integer(body, "plannedPoints", -1, 1, 10000);
        Integer branchId = body.has("branchId") ? Util.integer(body, "branchId", -1, 1, Integer.MAX_VALUE) : null;
        return Db.write(c -> {
            Auth.requireOwner(c, projectId, user.id());
            Budget old = budget(c, projectId, branchId);
            if (old.planned() == planned) throw new ApiException(400, "计划分没有变化。");
            if (planned < old.assigned()) {
                throw new ApiException(400, "新计划至少须覆盖已分配的 " + old.assigned() + " 分。");
            }
            try (PreparedStatement ps = c.prepareStatement(
                    branchId == null ? "UPDATE projects SET planned_points=? WHERE id=?"
                            : "UPDATE branches SET planned_points=? WHERE id=?")) {
                ps.setInt(1, planned);
                ps.setInt(2, branchId == null ? projectId : branchId);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO plan_changes(project_id,branch_id,old_points,new_points,reason,actor_id,created_at)
                    VALUES(?,?,?,?,?,?,?)
                    """)) {
                ps.setInt(1, projectId);
                ps.setObject(2, branchId);
                ps.setInt(3, old.planned());
                ps.setInt(4, planned);
                ps.setString(5, reason);
                ps.setInt(6, user.id());
                ps.setString(7, Clock.nowIso());
                ps.executeUpdate();
            }
            JsonObject out = new JsonObject();
            budget(c, projectId, branchId).addTo(out);
            return out;
        });
    }

    static JsonArray planHistory(Connection c, int projectId) throws SQLException {
        JsonArray changes = new JsonArray();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT pc.*,b.name AS branch_name,u.name AS actor FROM plan_changes pc
                JOIN users u ON u.id=pc.actor_id LEFT JOIN branches b ON b.id=pc.branch_id
                WHERE pc.project_id=? ORDER BY pc.id DESC
                """)) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    JsonObject change = new JsonObject();
                    change.addProperty("branchName", rs.getString("branch_name"));
                    change.addProperty("oldPoints", rs.getInt("old_points"));
                    change.addProperty("newPoints", rs.getInt("new_points"));
                    change.addProperty("reason", rs.getString("reason"));
                    change.addProperty("actor", rs.getString("actor"));
                    change.addProperty("createdAt", rs.getString("created_at"));
                    changes.add(change);
                }
            }
        }
        return changes;
    }

    static JsonObject leave(Auth.User user, int projectId) throws Exception {
        return Db.write(c -> {
            Auth.requireMember(c, projectId, user.id());
            if (Auth.isOwner(c, projectId, user.id())) throw new ApiException(403, "项目负责人不能直接退出项目。");
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM project_members WHERE project_id=? AND user_id=?")) {
                ps.setInt(1, projectId);
                ps.setInt(2, user.id());
                ps.executeUpdate();
            }
            return new JsonObject();
        });
    }

    static JsonArray list(int userId) throws Exception {
        JsonArray out = new JsonArray();
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT p.id,p.name,p.description,p.planned_points,p.owner_id,p.invite_code,p.created_at,m.role " +
                             "FROM projects p JOIN project_members m ON m.project_id=p.id " +
                             "WHERE m.user_id=? ORDER BY p.created_at,p.id")) {
            ps.setInt(1, userId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int projectId = rs.getInt("id");
                    Contribution.Result contribution = Contribution.calculate(c, projectId);
                    JsonObject o = new JsonObject();
                    o.addProperty("id", projectId);
                    o.addProperty("name", rs.getString("name"));
                    o.addProperty("description", rs.getString("description"));
                    o.addProperty("plannedPoints", rs.getInt("planned_points"));
                    o.addProperty("ownerId", rs.getInt("owner_id"));
                    o.addProperty("role", rs.getString("role"));
                    o.addProperty("inviteCode", rs.getString("invite_code"));
                    o.addProperty("createdAt", rs.getString("created_at"));
                    o.addProperty("memberCount", Auth.members(c, projectId).size());
                    o.addProperty("acceptedPoints", contribution.acceptedPoints());
                    o.addProperty("completionPct", contribution.completionPct());
                    o.addProperty("hasAccepted", contribution.hasAccepted());
                    o.addProperty("isOwner", rs.getInt("owner_id") == userId);
                    o.add("branches", contributionBranches(c, projectId));
                    out.add(o);
                }
            }
        }
        return out;
    }

    static JsonArray contributionBranches(Connection c, int projectId) throws SQLException {
        JsonArray arr = new JsonArray();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id,name,planned_points FROM branches WHERE project_id=? ORDER BY position,id")) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    int id = rs.getInt("id");
                    JsonObject o = new JsonObject();
                    o.addProperty("id", id);
                    o.addProperty("name", rs.getString("name"));
                    budget(c, projectId, id).addTo(o);
                    int accepted = acceptedForBranch(c, id);
                    o.addProperty("acceptedPoints", accepted);
                    o.addProperty("completionPct", Contribution.percent(accepted, rs.getInt("planned_points")));
                    o.addProperty("hasAccepted", accepted > 0);
                    arr.add(o);
                }
            }
        }
        return arr;
    }

    private static int acceptedForBranch(Connection c, int branchId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(ts.points),0) FROM task_scores ts JOIN tasks t ON t.id=ts.task_id " +
                        "WHERE t.branch_id=?")) {
            ps.setInt(1, branchId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    static JsonObject create(Auth.User user, JsonObject body) throws Exception {
        String name = Util.required(body, "name", 120);
        String description = Util.optional(body, "description", 2000);
        int planned = Util.integer(body, "plannedPoints", 100, 1, 10000);
        String branchName = Util.optional(body, "branchName", 120);
        if (branchName.isEmpty()) branchName = "General";
        String code = uniqueCode();
        int projectId;
        try (Connection c = Db.open()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO projects(name,description,planned_points,invite_code,owner_id,created_at) " +
                            "VALUES(?,?,?,?,?,?)", PreparedStatement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name);
                ps.setString(2, description);
                ps.setInt(3, planned);
                ps.setString(4, code);
                ps.setInt(5, user.id());
                ps.setString(6, Clock.nowIso());
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    projectId = keys.getInt(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO project_members(project_id,user_id,role,joined_at) VALUES(?,?,'owner',?)")) {
                ps.setInt(1, projectId);
                ps.setInt(2, user.id());
                ps.setString(3, Clock.nowIso());
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO branches(project_id,name,planned_points,position,created_at) VALUES(?,?,?,0,?)")) {
                ps.setInt(1, projectId);
                ps.setString(2, branchName);
                ps.setInt(3, planned);
                ps.setString(4, Clock.nowIso());
                ps.executeUpdate();
            }
            c.commit();
        }
        return get(user, projectId);
    }

    static JsonObject previewInvite(Auth.User user, String rawCode) throws Exception {
        String code = rawCode == null ? "" : rawCode.trim().toUpperCase();
        if (code.length() < 4 || code.length() > 32) throw new ApiException(400, "Invalid invite code");
        try (Connection c = Db.open()) {
            JsonObject project = new JsonObject();
            int projectId;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,name,description,planned_points FROM projects WHERE invite_code=?")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ApiException(404, "Invite code not found");
                    projectId = rs.getInt("id");
                    project.addProperty("id", projectId);
                    project.addProperty("name", rs.getString("name"));
                    project.addProperty("description", rs.getString("description"));
                    project.addProperty("plannedPoints", rs.getInt("planned_points"));
                }
            }
            project.addProperty("memberCount", Auth.members(c, projectId).size());
            boolean alreadyMember = false;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT 1 FROM project_members WHERE project_id=? AND user_id=?")) {
                ps.setInt(1, projectId);
                ps.setInt(2, user.id());
                try (ResultSet rs = ps.executeQuery()) {
                    alreadyMember = rs.next();
                }
            }
            JsonObject out = new JsonObject();
            out.add("project", project);
            out.addProperty("alreadyMember", alreadyMember);
            return out;
        }
    }

    static JsonObject join(Auth.User user, JsonObject body) throws Exception {
        String code = Util.required(body, "inviteCode", 32).toUpperCase();
        try (Connection c = Db.open()) {
            int projectId;
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM projects WHERE invite_code=?")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new ApiException(404, "Invite code not found");
                    projectId = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT OR IGNORE INTO project_members(project_id,user_id,role,joined_at) VALUES(?,?,'member',?)")) {
                ps.setInt(1, projectId);
                ps.setInt(2, user.id());
                ps.setString(3, Clock.nowIso());
                ps.executeUpdate();
            }
            return get(user, projectId);
        }
    }

    static JsonObject get(Auth.User user, int projectId) throws Exception {
        try (Connection c = Db.open()) {
            Auth.requireMember(c, projectId, user.id());
            return projectJson(c, projectId, user.id());
        }
    }

    private static JsonObject projectJson(Connection c, int projectId, int userId) throws SQLException {
        JsonObject o = new JsonObject();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT p.id,p.name,p.description,p.planned_points,p.owner_id,p.invite_code,p.created_at,m.role " +
                        "FROM projects p JOIN project_members m ON m.project_id=p.id WHERE p.id=? AND m.user_id=?")) {
            ps.setInt(1, projectId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApiException(403, "You are not a member of this project");
                o.addProperty("id", rs.getInt("id"));
                o.addProperty("name", rs.getString("name"));
                o.addProperty("description", rs.getString("description"));
                o.addProperty("plannedPoints", rs.getInt("planned_points"));
                o.addProperty("ownerId", rs.getInt("owner_id"));
                o.addProperty("role", rs.getString("role"));
                o.addProperty("inviteCode", rs.getString("invite_code"));
                o.addProperty("createdAt", rs.getString("created_at"));
                o.addProperty("isOwner", rs.getInt("owner_id") == userId);
            }
        }
        budget(c, projectId, null).addTo(o);
        return o;
    }

    static JsonArray membersJson(Connection c, int projectId, int currentUserId) throws SQLException {
        JsonArray arr = new JsonArray();
        for (Auth.User u : Auth.members(c, projectId)) {
            JsonObject m = new JsonObject();
            m.addProperty("id", u.id());
            m.addProperty("name", u.name());
            m.addProperty("initials", Util.initials(u.name()));
            m.addProperty("isSelf", u.id() == currentUserId);
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT role FROM project_members WHERE project_id=? AND user_id=?")) {
                ps.setInt(1, projectId);
                ps.setInt(2, u.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) m.addProperty("role", rs.getString(1));
                }
            }
            arr.add(m);
        }
        return arr;
    }

    static JsonObject board(Auth.User user, int projectId) throws Exception {
        try (Connection c = Db.open()) {
            Auth.requireMember(c, projectId, user.id());
            Reminders.evaluateProject(projectId);
            JsonObject out = new JsonObject();
            out.add("project", projectJson(c, projectId, user.id()));
            List<Auth.User> members = Auth.members(c, projectId);
            out.add("members", membersJson(c, projectId, user.id()));
            out.addProperty("memberCount", members.size());
            out.add("branches", contributionBranches(c, projectId));
            out.add("planChanges", planHistory(c, projectId));
            Contribution.Result contribution = Contribution.calculate(c, projectId);
            out.add("contribution", Contribution.toJson(contribution));

            JsonArray tasks = new JsonArray();
            List<Integer> taskIds = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id FROM tasks WHERE project_id=? ORDER BY deadline_at,id")) {
                ps.setInt(1, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) taskIds.add(rs.getInt(1));
                }
            }
            List<JsonObject> taskObjects = new ArrayList<>();
            for (int taskId : taskIds) {
                Tasks.TaskRow row = Tasks.load(c, taskId);
                taskObjects.add(Tasks.summary(c, row, user, projectId));
            }
            for (JsonObject task : taskObjects) tasks.add(task);
            out.add("tasks", tasks);

            JsonArray memberRows = new JsonArray();
            for (Auth.User member : members) memberRows.add(memberRow(c, projectId, member, user, taskObjects, contribution));
            out.add("memberRows", memberRows);

            JsonArray timeline = new JsonArray();
            for (Contribution.AcceptedTask accepted : contribution.acceptedTasks()) {
                JsonObject t = new JsonObject();
                t.addProperty("taskId", accepted.taskId());
                t.addProperty("title", accepted.title());
                t.addProperty("branchId", accepted.branchId());
                t.addProperty("branchName", accepted.branchName());
                t.addProperty("version", accepted.version());
                t.addProperty("submissionId", accepted.submissionId());
                t.addProperty("acceptedAt", accepted.acceptedAt());
                t.addProperty("points", accepted.points());
                JsonArray authors = new JsonArray();
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT sa.user_id,sa.name_snapshot,sa.share_pct FROM submission_authors sa " +
                                "WHERE sa.submission_id=? ORDER BY sa.share_pct DESC,sa.user_id")) {
                    ps.setInt(1, accepted.submissionId());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            JsonObject a = new JsonObject();
                            a.addProperty("id", rs.getInt("user_id"));
                            a.addProperty("name", rs.getString("name_snapshot"));
                            a.addProperty("sharePct", rs.getDouble("share_pct"));
                            a.addProperty("points", Util.round1(accepted.points() * rs.getDouble("share_pct") / 100.0));
                            authors.add(a);
                        }
                    }
                }
                t.add("authors", authors);
                timeline.add(t);
            }
            out.add("timeline", timeline);

            JsonArray messages = new JsonArray();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT m.id,m.body,m.created_at,m.task_id,u.name,t.title FROM messages m " +
                            "JOIN users u ON u.id=m.author_id LEFT JOIN tasks t ON t.id=m.task_id " +
                            "WHERE m.project_id=? ORDER BY m.created_at DESC,m.id DESC LIMIT 60")) {
                ps.setInt(1, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject m = new JsonObject();
                        m.addProperty("id", rs.getInt("id"));
                        m.addProperty("body", rs.getString("body"));
                        m.addProperty("createdAt", rs.getString("created_at"));
                        m.addProperty("taskId", rs.getObject("task_id") == null ? null : rs.getInt("task_id"));
                        m.addProperty("taskTitle", rs.getString("title"));
                        m.addProperty("authorName", rs.getString("name"));
                        messages.add(m);
                    }
                }
            }
            out.add("messages", messages);
            out.add("reports", Reports.projectSummaries(c, projectId, user.id()));
            out.addProperty("serverTime", Clock.nowIso());
            out.addProperty("anonymousVoting", true);
            out.add("aiStatus", Evidence.status());
            out.addProperty("inferenceWarning", members.size() == 2
                    ? "This is a two-person group. Before voting, note that the submitter may infer your choice from the result."
                    : "");
            return out;
        }
    }

    private static JsonObject memberRow(Connection c, int projectId, Auth.User member, Auth.User viewer,
                                        List<JsonObject> taskObjects, Contribution.Result contribution) throws SQLException {
        JsonObject row = new JsonObject();
        row.addProperty("id", member.id());
        row.addProperty("name", member.name());
        row.addProperty("initials", Util.initials(member.name()));
        List<JsonObject> active = new ArrayList<>();
        for (JsonObject task : taskObjects) {
            if (task.get("accepted").getAsBoolean()) continue;
            boolean participates = task.get("assigneeId").getAsInt() == member.id();
            if (!participates) {
                for (var ae : task.getAsJsonArray("planAuthors")) {
                    if (ae.getAsJsonObject().get("id").getAsInt() == member.id()) {
                        participates = true;
                        break;
                    }
                }
            }
            if (participates) active.add(task);
        }
        JsonObject current = active.isEmpty() ? null : active.get(0);
        JsonArray activeTasks = new JsonArray();
        JsonObject supportSource = null;
        for (JsonObject task : active) {
            JsonObject item = new JsonObject();
            item.addProperty("id", task.get("id").getAsInt());
            item.addProperty("title", task.get("title").getAsString());
            item.addProperty("state", task.get("state").getAsString());
            item.addProperty("branchName", task.get("branchName").getAsString());
            item.addProperty("points", task.get("points").getAsInt());
            item.addProperty("checkpointAt", task.get("checkpointAt").getAsString());
            item.addProperty("deadlineAt", task.get("deadlineAt").getAsString());
            if (task.has("support") && task.get("support").isJsonObject()) {
                item.add("support", task.getAsJsonObject("support").deepCopy());
                if (supportSource == null || task == current) supportSource = task;
            }
            activeTasks.add(item);
        }
        row.add("activeTasks", activeTasks);
        row.addProperty("activeTaskCount", active.size());
        if (current != null) {
            row.addProperty("currentTaskId", current.get("id").getAsInt());
            row.addProperty("currentTaskTitle", current.get("title").getAsString());
            row.addProperty("currentTaskState", current.get("state").getAsString());
            row.addProperty("nextCheckpoint", current.get("checkpointAt").getAsString());
            row.addProperty("deadlineAt", current.get("deadlineAt").getAsString());
        } else {
            row.add("currentTaskId", null);
            row.addProperty("currentTaskTitle", "");
            row.addProperty("currentTaskState", "idle");
            row.add("nextCheckpoint", null);
            row.add("deadlineAt", null);
        }
        if (supportSource != null) {
            JsonObject support = supportSource.getAsJsonObject("support");
            row.addProperty("neededSupport", support.get("neededSupport").getAsString());
            row.addProperty("supportKind", support.get("kind").getAsString());
            row.addProperty("supportBody", support.get("body").getAsString());
            row.addProperty("supportTaskId", supportSource.get("id").getAsInt());
            row.addProperty("supportTaskTitle", supportSource.get("title").getAsString());
        }
        String status;
        if (active.isEmpty()) {
            status = contribution.hasAccepted() ? "done" : "idle";
        } else {
            status = "working";
            boolean followUp = false;
            boolean pending = false;
            for (JsonObject task : active) {
                String state = task.get("state").getAsString();
                if ("blocked".equals(state)) {
                    status = "blocked";
                    break;
                }
                if ("follow_up".equals(state)) followUp = true;
                if ("pending_review".equals(state)) pending = true;
            }
            if ("working".equals(status)) {
                if (followUp) status = "follow_up";
                else if (pending) status = "waiting_review";
            }
        }
        row.addProperty("status", status);
        row.addProperty("points", Util.round1(contribution.memberPoints().getOrDefault(member.id(), 0.0)));
        row.addProperty("sharePct", contribution.hasAccepted() ? contribution.memberSharePct(member.id()) : 0);
        row.addProperty("hasAccepted", contribution.hasAccepted());

        JsonObject reminder = null;
        if (member.id() == viewer.id()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT r.created_at,r.checkpoint_at,r.follow_up_at,r.resolved_at,t.id AS task_id,t.title " +
                            "FROM reminders r JOIN tasks t ON t.id=r.task_id WHERE r.project_id=? " +
                            "AND r.assignee_id=? AND r.resolved_at IS NULL " +
                            "AND NOT EXISTS (SELECT 1 FROM task_scores s WHERE s.task_id=r.task_id) " +
                            "ORDER BY r.created_at DESC LIMIT 1")) {
                ps.setInt(1, projectId);
                ps.setInt(2, member.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        reminder = new JsonObject();
                        reminder.addProperty("taskId", rs.getInt("task_id"));
                        reminder.addProperty("taskTitle", rs.getString("title"));
                        reminder.addProperty("createdAt", rs.getString("created_at"));
                        reminder.addProperty("checkpointAt", rs.getString("checkpoint_at"));
                        reminder.addProperty("followUpAt", rs.getString("follow_up_at"));
                        reminder.addProperty("dueAt", Instant.parse(rs.getString("created_at"))
                                .plusSeconds(24 * 3600).toString());
                    }
                }
            }
        }
        row.add("privateReminder", reminder);
        return row;
    }

    static JsonObject addBranch(Auth.User user, int projectId, JsonObject body) throws Exception {
        String name = Util.required(body, "name", 120);
        int planned = Util.integer(body, "plannedPoints", -1, 1, 10000);
        try (Connection c = Db.open()) {
            Auth.requireMember(c, projectId, user.id());
            int position;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(MAX(position),-1)+1 FROM branches WHERE project_id=?")) {
                ps.setInt(1, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    position = rs.getInt(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO branches(project_id,name,planned_points,position,created_at) VALUES(?,?,?,?,?)")) {
                ps.setInt(1, projectId);
                ps.setString(2, name);
                ps.setInt(3, planned);
                ps.setInt(4, position);
                ps.setString(5, Clock.nowIso());
                ps.executeUpdate();
            }
            JsonObject out = new JsonObject();
            out.addProperty("ok", true);
            return out;
        }
    }

    static JsonArray messages(Auth.User user, int projectId) throws Exception {
        JsonArray arr = new JsonArray();
        try (Connection c = Db.open()) {
            Auth.requireMember(c, projectId, user.id());
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT m.id,m.body,m.created_at,m.task_id,u.name,t.title FROM messages m " +
                            "JOIN users u ON u.id=m.author_id LEFT JOIN tasks t ON t.id=m.task_id " +
                            "WHERE m.project_id=? ORDER BY m.created_at DESC,m.id DESC LIMIT 100")) {
                ps.setInt(1, projectId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        JsonObject m = new JsonObject();
                        m.addProperty("id", rs.getInt("id"));
                        m.addProperty("body", rs.getString("body"));
                        m.addProperty("createdAt", rs.getString("created_at"));
                        m.addProperty("taskId", rs.getObject("task_id") == null ? null : rs.getInt("task_id"));
                        m.addProperty("taskTitle", rs.getString("title"));
                        m.addProperty("authorName", rs.getString("name"));
                        arr.add(m);
                    }
                }
            }
        }
        return arr;
    }

    static JsonObject postMessage(Auth.User user, int projectId, JsonObject body) throws Exception {
        String text = Util.required(body, "body", 4000);
        int taskId = Util.integer(body, "taskId", 0, 0, Integer.MAX_VALUE);
        try (Connection c = Db.open()) {
            Auth.requireMember(c, projectId, user.id());
            if (taskId > 0) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT 1 FROM tasks WHERE id=? AND project_id=?")) {
                    ps.setInt(1, taskId);
                    ps.setInt(2, projectId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new ApiException(400, "Task does not belong to this project");
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO messages(project_id,task_id,author_id,body,created_at) VALUES(?,?,?,?,?)")) {
                ps.setInt(1, projectId);
                if (taskId > 0) ps.setInt(2, taskId);
                else ps.setNull(2, java.sql.Types.INTEGER);
                ps.setInt(3, user.id());
                ps.setString(4, text);
                ps.setString(5, Clock.nowIso());
                ps.executeUpdate();
            }
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            return o;
        }
    }

    private static String uniqueCode() throws Exception {
        for (int i = 0; i < 20; i++) {
            String code = Util.inviteCode();
            try (Connection c = Db.open();
                 PreparedStatement ps = c.prepareStatement("SELECT 1 FROM projects WHERE invite_code=?")) {
                ps.setString(1, code);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return code;
                }
            }
        }
        throw new ApiException(500, "Could not allocate an invite code");
    }
}
