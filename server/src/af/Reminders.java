package af;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Checkpoint reminders. A missed checkpoint first creates a private reminder
 * for the assignee. Only after 24 hours without any recorded response does the
 * project see a "needs follow-up" flag. A unique (task, checkpoint) key keeps
 * repeated board loads or restarts from creating duplicate reminders.
 */
final class Reminders {
    private Reminders() {}

    record TaskCheck(int id, int projectId, int assigneeId, String checkpointAt) {}

    static synchronized int evaluateProject(int projectId) throws Exception {
        List<TaskCheck> tasks = new ArrayList<>();
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT t.id,t.project_id,t.assignee_id,t.checkpoint_at FROM tasks t " +
                             "LEFT JOIN task_scores s ON s.task_id=t.id " +
                             "WHERE t.project_id=? AND s.id IS NULL")) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tasks.add(new TaskCheck(rs.getInt("id"), rs.getInt("project_id"),
                            rs.getInt("assignee_id"), rs.getString("checkpoint_at")));
                }
            }
        }
        int changes = 0;
        for (TaskCheck task : tasks) changes += evaluateTask(task);
        return changes;
    }

    static synchronized int resolveAcceptedReminders() throws Exception {
        String now = Clock.nowIso();
        try (Connection c = Db.open(); PreparedStatement ps = c.prepareStatement(
                "UPDATE reminders SET resolved_at=COALESCE(resolved_at,?), " +
                        "follow_up_resolved_at=COALESCE(follow_up_resolved_at,?) " +
                        "WHERE resolved_at IS NULL AND task_id IN (SELECT task_id FROM task_scores)")) {
            ps.setString(1, now);
            ps.setString(2, now);
            return ps.executeUpdate();
        }
    }

    static synchronized int evaluateAll() throws Exception {
        List<TaskCheck> tasks = new ArrayList<>();
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT t.id,t.project_id,t.assignee_id,t.checkpoint_at FROM tasks t " +
                             "LEFT JOIN task_scores s ON s.task_id=t.id WHERE s.id IS NULL")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    tasks.add(new TaskCheck(rs.getInt("id"), rs.getInt("project_id"),
                            rs.getInt("assignee_id"), rs.getString("checkpoint_at")));
                }
            }
        }
        int changes = 0;
        for (TaskCheck task : tasks) changes += evaluateTask(task);
        return changes;
    }

    private static int evaluateTask(TaskCheck task) throws Exception {
        Instant now = Clock.now();
        Instant checkpoint = Instant.parse(task.checkpointAt());
        if (checkpoint.isAfter(now)) return 0;
        return Db.write(c -> {
            int changes = 0;
            Integer reminderId = null;
            String reminderCreated = null;
            String resolvedAt = null;
            String followUpAt = null;
            String followUpResolved = null;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id,created_at,resolved_at,follow_up_at,follow_up_resolved_at FROM reminders " +
                            "WHERE task_id=? AND checkpoint_at=?")) {
                ps.setInt(1, task.id());
                ps.setString(2, task.checkpointAt());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        reminderId = rs.getInt("id");
                        reminderCreated = rs.getString("created_at");
                        resolvedAt = rs.getString("resolved_at");
                        followUpAt = rs.getString("follow_up_at");
                        followUpResolved = rs.getString("follow_up_resolved_at");
                    }
                }
            }
            if (reminderId == null) {
                if (hasProgress(c, task.id())) return 0;
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO reminders(project_id,task_id,assignee_id,checkpoint_at,created_at) " +
                                "VALUES(?,?,?,?,?)", PreparedStatement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, task.projectId());
                    ps.setInt(2, task.id());
                    ps.setInt(3, task.assigneeId());
                    ps.setString(4, task.checkpointAt());
                    ps.setString(5, now.toString());
                    ps.executeUpdate();
                }
                insertEvent(c, task, "reminder_private", "private",
                        "检查点已到，负责人尚未提交成果、说明或求助。系统已私下提醒负责人。", "{}", now);
                return 1;
            }
            if (resolvedAt != null) return 0;

            boolean responded = hasResponseSince(c, task, Instant.parse(reminderCreated));
            if (responded) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE reminders SET resolved_at=?, follow_up_resolved_at=COALESCE(follow_up_resolved_at,?) WHERE id=?")) {
                    ps.setString(1, now.toString());
                    ps.setString(2, now.toString());
                    ps.setInt(3, reminderId);
                    ps.executeUpdate();
                }
                return 1;
            }
            if (followUpAt == null && followUpDue(Instant.parse(reminderCreated), now)) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE reminders SET follow_up_at=? WHERE id=?")) {
                    ps.setString(1, now.toString());
                    ps.setInt(2, reminderId);
                    ps.executeUpdate();
                }
                insertEvent(c, task, "follow_up", "project",
                        "检查点后满 24 小时仍无成果、说明或求助，组内标记为待跟进。", "{}", now);
                changes++;
            }
            return changes;
        });
    }

    static boolean followUpDue(Instant firstPrivateReminder, Instant now) {
        return !firstPrivateReminder.plus(Duration.ofHours(24)).isAfter(now);
    }

    private static boolean hasProgress(Connection c, int taskId) throws SQLException {
        if (exists(c, "SELECT 1 FROM submissions WHERE task_id=? LIMIT 1", taskId)) return true;
        if (exists(c, "SELECT 1 FROM task_support WHERE task_id=? AND resolved_at IS NULL LIMIT 1", taskId)) return true;
        if (exists(c, "SELECT 1 FROM date_changes WHERE task_id=? LIMIT 1", taskId)) return true;
        if (exists(c, "SELECT 1 FROM task_events WHERE task_id=? AND type IN ('note','ack') LIMIT 1", taskId)) return true;
        return false;
    }

    private static boolean hasResponseSince(Connection c, TaskCheck task, Instant since) throws SQLException {
        String at = since.toString();
        if (exists2(c, "SELECT 1 FROM submissions WHERE task_id=? AND created_at>=? LIMIT 1", task.id(), at)) return true;
        if (exists2(c, "SELECT 1 FROM task_support WHERE task_id=? AND created_at>=? LIMIT 1", task.id(), at)) return true;
        if (exists2(c, "SELECT 1 FROM date_changes WHERE task_id=? AND created_at>=? LIMIT 1", task.id(), at)) return true;
        if (existsActor(c, "SELECT 1 FROM task_events WHERE task_id=? AND actor_id=? AND created_at>=? " +
                "AND type IN ('note','ack') LIMIT 1", task.id(), task.assigneeId(), at)) return true;
        return false;
    }

    private static boolean exists(Connection c, String sql, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static boolean exists2(Connection c, String sql, int id, String at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setString(2, at);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    static void insertEvent(Connection c, TaskCheck task, String type, String visibility,
                            String body, String payload, Instant at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO task_events(project_id,task_id,type,actor_id,visibility,body,payload,created_at) " +
                        "VALUES(?,?,?,?,?,?,?,?)")) {
            ps.setInt(1, task.projectId());
            ps.setInt(2, task.id());
            ps.setString(3, type);
            ps.setNull(4, java.sql.Types.INTEGER);
            ps.setString(5, visibility);
            ps.setString(6, body);
            ps.setString(7, payload);
            ps.setString(8, at.toString());
            ps.executeUpdate();
        }
    }

    private static boolean existsActor(Connection c, String sql, int id, int actor, String at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, id);
            ps.setInt(2, actor);
            ps.setString(3, at);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}
