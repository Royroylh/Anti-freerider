package af;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Single source of truth for contribution arithmetic. All UI, export and AI
 * numbers come from here so a percentage can always be traced to accepted
 * task points and the frozen author shares of the accepted version.
 */
final class Contribution {
    private Contribution() {}

    record AcceptedTask(int taskId, String title, int branchId, String branchName,
                        int points, int submissionId, int version, String acceptedAt) {}

    record Result(int plannedPoints, int assignedPoints, int acceptedPoints, double completionPct,
                  List<Auth.User> members, Map<Integer, Double> memberPoints,
                  Map<Integer, Integer> branchAccepted, Map<Integer, Integer> branchPlanned,
                  Map<Integer, Integer> branchAssigned,
                  Map<Integer, String> branchNames, List<AcceptedTask> acceptedTasks) {

        boolean hasAccepted() {
            return acceptedPoints > 0;
        }

        double memberSharePct(int userId) {
            return percent(memberPoints.getOrDefault(userId, 0.0), acceptedPoints);
        }
    }

    static double allocatedPoints(int acceptedTaskPoints, double sharePct) {
        return acceptedTaskPoints * sharePct / 100.0;
    }

    static double percent(double points, int denominator) {
        return denominator == 0 ? 0 : Util.round1(points * 100.0 / denominator);
    }

    static Result calculate(Connection c, int projectId) throws SQLException {
        Projects.Budget project = Projects.budget(c, projectId, null);
        List<Auth.User> members = new java.util.ArrayList<>(Auth.members(c, projectId));
        // A departed author keeps their accepted contribution and remains in the totals.
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT DISTINCT u.id,u.email,u.name FROM users u
                JOIN submission_authors a ON a.user_id=u.id
                JOIN task_scores sc ON sc.submission_id=a.submission_id
                JOIN tasks t ON t.id=sc.task_id
                WHERE t.project_id=? AND NOT EXISTS (
                    SELECT 1 FROM project_members m WHERE m.project_id=t.project_id AND m.user_id=u.id)
                ORDER BY u.id
                """)) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) members.add(new Auth.User(rs.getInt(1), rs.getString(2), rs.getString(3)));
            }
        }
        Map<Integer, Double> points = new LinkedHashMap<>();
        for (Auth.User member : members) points.put(member.id(), 0.0);

        Map<Integer, Integer> branchAccepted = new LinkedHashMap<>();
        Map<Integer, Integer> branchPlanned = new LinkedHashMap<>();
        Map<Integer, Integer> branchAssigned = new LinkedHashMap<>();
        Map<Integer, String> branchNames = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                """
                SELECT b.id,b.name,b.planned_points,
                       (SELECT COALESCE(SUM(t.planned_points),0) FROM tasks t WHERE t.branch_id=b.id) AS assigned
                FROM branches b WHERE b.project_id=? ORDER BY b.position,b.id
                """)) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    branchPlanned.put(rs.getInt("id"), rs.getInt("planned_points"));
                    branchAssigned.put(rs.getInt("id"), rs.getInt("assigned"));
                    branchAccepted.put(rs.getInt("id"), 0);
                    branchNames.put(rs.getInt("id"), rs.getString("name"));
                }
            }
        }

        java.util.ArrayList<AcceptedTask> accepted = new java.util.ArrayList<>();
        int acceptedTotal = 0;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT t.id,t.title,t.branch_id,b.name,ts.points,ts.submission_id,ts.accepted_at,s.version " +
                        "FROM task_scores ts JOIN tasks t ON t.id=ts.task_id " +
                        "JOIN branches b ON b.id=t.branch_id JOIN submissions s ON s.id=ts.submission_id " +
                        "WHERE t.project_id=? ORDER BY ts.accepted_at, t.id")) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    AcceptedTask task = new AcceptedTask(rs.getInt("id"), rs.getString("title"),
                            rs.getInt("branch_id"), rs.getString("name"), rs.getInt("points"),
                            rs.getInt("submission_id"), rs.getInt("version"), rs.getString("accepted_at"));
                    accepted.add(task);
                    acceptedTotal += task.points();
                    branchAccepted.merge(task.branchId(), task.points(), Integer::sum);
                    try (PreparedStatement shares = c.prepareStatement(
                            "SELECT user_id,share_pct FROM submission_authors WHERE submission_id=?")) {
                        shares.setInt(1, task.submissionId());
                        try (ResultSet sr = shares.executeQuery()) {
                            while (sr.next()) {
                                int userId = sr.getInt("user_id");
                                double share = sr.getDouble("share_pct");
                                points.merge(userId, allocatedPoints(task.points(), share), Double::sum);
                            }
                        }
                    }
                }
            }
        }
        double pct = percent(acceptedTotal, project.planned());
        return new Result(project.planned(), project.assigned(), acceptedTotal, pct, members, points, branchAccepted, branchPlanned, branchAssigned, branchNames, accepted);
    }

    static JsonObject toJson(Result result) {
        JsonObject o = new JsonObject();
        new Projects.Budget(result.plannedPoints(), result.assignedPoints()).addTo(o);
        o.addProperty("acceptedPoints", result.acceptedPoints());
        o.addProperty("completionPct", result.completionPct());
        o.addProperty("hasAccepted", result.hasAccepted());
        JsonArray members = new JsonArray();
        for (Auth.User user : result.members()) {
            JsonObject m = new JsonObject();
            m.addProperty("id", user.id());
            m.addProperty("name", user.name());
            m.addProperty("initials", Util.initials(user.name()));
            m.addProperty("points", Util.round1(result.memberPoints().getOrDefault(user.id(), 0.0)));
            m.addProperty("sharePct", result.hasAccepted() ? result.memberSharePct(user.id()) : 0);
            m.addProperty("hasAccepted", result.hasAccepted());
            members.add(m);
        }
        o.add("members", members);
        JsonArray branches = new JsonArray();
        for (Map.Entry<Integer, Integer> entry : result.branchPlanned().entrySet()) {
            int branchId = entry.getKey();
            int plannedBranch = entry.getValue();
            int acceptedBranch = result.branchAccepted().getOrDefault(branchId, 0);
            JsonObject b = new JsonObject();
            b.addProperty("id", branchId);
            b.addProperty("name", result.branchNames().getOrDefault(branchId, "Branch"));
            new Projects.Budget(plannedBranch, result.branchAssigned().getOrDefault(branchId, 0)).addTo(b);
            b.addProperty("acceptedPoints", acceptedBranch);
            b.addProperty("completionPct", percent(acceptedBranch, plannedBranch));
            b.addProperty("hasAccepted", acceptedBranch > 0);
            branches.add(b);
        }
        o.add("branches", branches);
        return o;
    }
}
