package af;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;

/** Repeatable local demo fixture. Only runs on an empty database. */
final class Seed {
    private Seed() {}

    static final String DEMO_PASSWORD = "demo-pass-123";

    record DemoUser(String email, String name) {}

    static final DemoUser[] USERS = {
            new DemoUser("alice@demo.edu", "Alice Chen"),
            new DemoUser("ben@demo.edu", "Ben Wu"),
            new DemoUser("cara@demo.edu", "Cara Lin"),
            new DemoUser("dan@demo.edu", "Dan Zhou"),
            new DemoUser("eve@demo.edu", "Eve Park")
    };

    static boolean seed() throws Exception {
        try (Connection c = Db.open()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM users");
                 ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    System.out.println("[seed] database already has users; demo fixture skipped");
                    return false;
                }
            }
            c.setAutoCommit(false);
            try {
                int alice = user(c, "alice@demo.edu", "Alice Chen");
                int ben = user(c, "ben@demo.edu", "Ben Wu");
                int cara = user(c, "cara@demo.edu", "Cara Lin");
                int dan = user(c, "dan@demo.edu", "Dan Zhou");
                user(c, "eve@demo.edu", "Eve Park");
                Instant t0 = Clock.now();

                int main = project(c, "Research Methods 2026",
                        "Four-person course project with two branches. Use it to walk the full submission and review flow.",
                        120, alice);
                member(c, main, alice, "owner");
                member(c, main, ben, "member");
                member(c, main, cara, "member");
                member(c, main, dan, "member");
                int design = branch(c, main, "Questionnaire design", 50, 0);
                int data = branch(c, main, "Data collection & analysis", 90, 1);
                int t1 = task(c, main, design, "Questionnaire design & pilot / Survey design and pilot", alice,
                        "Draft the questionnaire, run a small pilot, and record the changes made after the pilot.",
                        30, t0.plus(Duration.ofHours(2)), t0.plus(Duration.ofDays(2)),
                        "Pilot questionnaire (v1) plus a short note on revisions.",
                        "All five sections are present.\nPilot includes at least three respondents.\nDocumentation explains what changed after the pilot.",
                        alice, ben, 60, 40);
                int t2 = task(c, main, data, "Interview data collection / Data collection", cara,
                        "Collect two interview groups and prepare the transcript pack.",
                        30, t0.plus(Duration.ofDays(1)), t0.plus(Duration.ofDays(3)),
                        "Two anonymised interview transcripts and a collection log.",
                        "Two groups are represented.\nConsent and anonymisation are documented.\nA collection log explains any deviation.",
                        cara, null, 100, 0);
                int t3 = task(c, main, data, "Questionnaire analysis report / Survey analysis report", ben,
                        "Analyse the pilot data and write the report used in the final submission.",
                        40, t0.plus(Duration.ofDays(2)), t0.plus(Duration.ofDays(4)),
                        "Analysis report with tables, limitations, and a methods note.",
                        "Report states the sample and method.\nEvery table is traceable to the dataset.\nLimitations are discussed.",
                        ben, dan, 70, 30);
                int t4 = task(c, main, data, "Integrated final report / Final integration report", alice,
                        "All four members jointly edit and sign the integrated course submission.",
                        20, t0.plus(Duration.ofDays(3)), t0.plus(Duration.ofDays(5)),
                        "Integrated report with a contribution appendix.",
                        "All sections are consistent.\nContribution appendix matches the system record.\nAll four authors sign off.",
                        alice, null, 100, 0, new int[]{alice, ben, cara, dan});

                message(c, main, t1, alice, "Welcome. The plan is frozen per task; please keep criteria and points visible when you submit.");
                message(c, main, t2, cara, "Still need two interview groups; I will record what support is missing.");
                message(c, main, t3, ben, "The methods note and tables belong to this task. Please review the version I submit.");

                int math = project(c, "Contribution Math 100",
                        "A deliberately small project with known numbers: 20 + 40 accepted, 40 not accepted.",
                        100, alice);
                member(c, math, alice, "owner");
                member(c, math, ben, "member");
                member(c, math, cara, "member");
                member(c, math, dan, "member");
                int delivered = branch(c, math, "Delivered work", 60, 0);
                int remaining = branch(c, math, "Not yet delivered", 40, 1);
                int m1 = task(c, math, delivered, "Widget specification / Specification", alice,
                        "Write the 20-point specification used in the contribution arithmetic check.",
                        20, t0.minus(Duration.ofDays(2)), t0.plus(Duration.ofDays(2)),
                        "Specification document.",
                        "Includes scope.\nIncludes acceptance examples.",
                        alice, ben, 75, 25);
                int m2 = task(c, math, delivered, "Dataset validation / Data validation", cara,
                        "Validate the dataset independently; this 40-point task is completed by Cara alone.",
                        40, t0.minus(Duration.ofDays(1)), t0.plus(Duration.ofDays(2)),
                        "Validation log.",
                        "Checks row counts and missing values.\nRecords the validation date.",
                        cara, null, 100, 0);
                task(c, math, remaining, "Remaining deliverable / Unfinished deliverable", dan,
                        "Deliberately not accepted yet, so 40 points stay outside the completion percentage.",
                        40, t0.plus(Duration.ofDays(3)), t0.plus(Duration.ofDays(6)),
                        "Not submitted yet.",
                        "Not yet reviewed.",
                        dan, null, 100, 0);
                message(c, math, m2, cara, "Dataset validation is tracked in this project only, not in Research Methods.");
                message(c, math, m1, alice, "Known-number check: 20 points at 75/25 plus 40 points solo = 60 accepted points.");

                c.commit();
                System.out.println("[seed] demo data created; demo credentials are documented in docs/RUNBOOK.md and are not logged");
            } catch (Exception ex) {
                c.rollback();
                throw ex;
            } finally {
                c.setAutoCommit(true);
            }
        }
        return true;
    }

    private static int user(Connection c, String email, String name) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users(email,name,password_hash,created_at) VALUES(?,?,?,?)",
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, email);
            ps.setString(2, name);
            ps.setString(3, Util.hashPassword(DEMO_PASSWORD));
            ps.setString(4, Clock.nowIso());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private static int project(Connection c, String name, String description, int points, int owner) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO projects(name,description,planned_points,invite_code,owner_id,created_at) VALUES(?,?,?,?,?,?)",
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, name);
            ps.setString(2, description);
            ps.setInt(3, points);
            ps.setString(4, Util.inviteCode());
            ps.setInt(5, owner);
            ps.setString(6, Clock.nowIso());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private static void member(Connection c, int projectId, int userId, String role) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO project_members(project_id,user_id,role,joined_at) VALUES(?,?,?,?)")) {
            ps.setInt(1, projectId);
            ps.setInt(2, userId);
            ps.setString(3, role);
            ps.setString(4, Clock.nowIso());
            ps.executeUpdate();
        }
    }

    private static int branch(Connection c, int projectId, String name, int points, int position) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO branches(project_id,name,planned_points,position,created_at) VALUES(?,?,?,?,?)",
                PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, projectId);
            ps.setString(2, name);
            ps.setInt(3, points);
            ps.setInt(4, position);
            ps.setString(5, Clock.nowIso());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return keys.getInt(1);
            }
        }
    }

    private static int task(Connection c, int projectId, int branchId, String title, int assignee, String description,
                            int points, Instant checkpoint, Instant deadline, String expected, String criteria,
                            int authorA, Integer authorB, double shareA, double shareB) throws Exception {
        return task(c, projectId, branchId, title, assignee, description, points, checkpoint, deadline, expected,
                criteria, authorA, authorB, shareA, shareB, null);
    }

    private static int task(Connection c, int projectId, int branchId, String title, int assignee, String description,
                            int points, Instant checkpoint, Instant deadline, String expected, String criteria,
                            int authorA, Integer authorB, double shareA, double shareB, int[] allAuthors) throws Exception {
        com.google.gson.JsonArray plan = new com.google.gson.JsonArray();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO tasks(project_id,branch_id,title,description,assignee_id,planned_points,checkpoint_at," +
                        "deadline_at,expected_output,acceptance_criteria,created_by,original_plan,created_at,updated_at) " +
                        "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)", PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, projectId);
            ps.setInt(2, branchId);
            ps.setString(3, title);
            ps.setString(4, description);
            ps.setInt(5, assignee);
            ps.setInt(6, points);
            ps.setString(7, checkpoint.toString());
            ps.setString(8, deadline.toString());
            ps.setString(9, expected);
            ps.setString(10, criteria);
            ps.setInt(11, assignee);
            ps.setString(12, "[]");
            ps.setString(13, Clock.nowIso());
            ps.setString(14, Clock.nowIso());
            ps.executeUpdate();
            int taskId;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                taskId = keys.getInt(1);
            }
            if (allAuthors != null) {
                double share = 100.0 / allAuthors.length;
                for (int id : allAuthors) {
                    insertAuthor(c, taskId, id, share);
                    var o = new com.google.gson.JsonObject();
                    o.addProperty("userId", id);
                    o.addProperty("sharePct", share);
                    plan.add(o);
                }
            } else {
                insertAuthor(c, taskId, authorA, shareA);
                var o = new com.google.gson.JsonObject();
                o.addProperty("userId", authorA);
                o.addProperty("sharePct", shareA);
                plan.add(o);
                if (authorB != null && shareB > 0) {
                    insertAuthor(c, taskId, authorB, shareB);
                    var o2 = new com.google.gson.JsonObject();
                    o2.addProperty("userId", authorB);
                    o2.addProperty("sharePct", shareB);
                    plan.add(o2);
                }
            }
            try (PreparedStatement up = c.prepareStatement("UPDATE tasks SET original_plan=? WHERE id=?")) {
                up.setString(1, plan.toString());
                up.setInt(2, taskId);
                up.executeUpdate();
            }
            return taskId;
        }
    }

    private static void insertAuthor(Connection c, int taskId, int userId, double share) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO task_authors(task_id,user_id,share_pct) VALUES(?,?,?)")) {
            ps.setInt(1, taskId);
            ps.setInt(2, userId);
            ps.setDouble(3, share);
            ps.executeUpdate();
        }
    }

    private static void message(Connection c, int projectId, int taskId, int author, String body) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO messages(project_id,task_id,author_id,body,created_at) VALUES(?,?,?,?,?)")) {
            ps.setInt(1, projectId);
            ps.setInt(2, taskId);
            ps.setInt(3, author);
            ps.setString(4, body);
            ps.setString(5, Clock.nowIso());
            ps.executeUpdate();
        }
    }
}
