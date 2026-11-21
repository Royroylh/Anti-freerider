package af;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Password, session, membership and role checks. */
final class Auth {
    private Auth() {}

    record User(int id, String email, String name) {
        JsonObject json() {
            JsonObject o = new JsonObject();
            o.addProperty("id", id);
            o.addProperty("email", email);
            o.addProperty("name", name);
            o.addProperty("initials", Util.initials(name));
            return o;
        }
    }

    record Membership(int projectId, int userId, String role) {}

    static User login(String email, String password) throws Exception {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id,email,name,password_hash FROM users WHERE email = ? COLLATE NOCASE")) {
            ps.setString(1, email);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // Spend roughly the same time as a real check to avoid trivial user enumeration.
                    Util.verifyPassword(password, "pbkdf2$120000$AAAAAAAAAAAAAAAAAAAAAA==$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=");
                    return null;
                }
                String stored = rs.getString("password_hash");
                if (!Util.verifyPassword(password, stored)) return null;
                return new User(rs.getInt("id"), rs.getString("email"), rs.getString("name"));
            }
        }
    }

    static User register(String name, String email, String password) throws Exception {
        if (name == null || name.trim().isEmpty() || name.trim().length() > 60) {
            throw new ApiException(400, "Name must be 1-60 characters");
        }
        String cleanEmail = email == null ? "" : email.trim().toLowerCase();
        if (!Util.validEmail(cleanEmail)) throw new ApiException(400, "Enter a valid email address");
        if (password == null || password.length() < 8 || password.length() > 200) {
            throw new ApiException(400, "Password must be 8-200 characters");
        }
        String hash = Util.hashPassword(password);
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO users(email,name,password_hash,created_at) VALUES(?,?,?,?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, cleanEmail);
            ps.setString(2, name.trim());
            ps.setString(3, hash);
            ps.setString(4, Clock.nowIso());
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                keys.next();
                return new User(keys.getInt(1), cleanEmail, name.trim());
            }
        } catch (SQLException ex) {
            if (String.valueOf(ex.getMessage()).contains("UNIQUE")) {
                throw new ApiException(409, "An account with this email already exists");
            }
            throw ex;
        }
    }

    static String createSession(int userId, boolean persistent) throws Exception {
        String token = Util.randomToken(32);
        String hash = Util.sha256(token);
        Instant now = Clock.now();
        Instant expires = now.plus(Duration.ofDays(30));
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO sessions(user_id,token_hash,created_at,expires_at,persistent) VALUES(?,?,?,?,?)")) {
            ps.setInt(1, userId);
            ps.setString(2, hash);
            ps.setString(3, now.toString());
            ps.setString(4, expires.toString());
            ps.setInt(5, persistent ? 1 : 0);
            ps.executeUpdate();
        }
        return token;
    }

    static User sessionUser(String token) throws Exception {
        if (token == null || token.isBlank()) return null;
        String hash = Util.sha256(token);
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT u.id,u.email,u.name,s.expires_at FROM sessions s JOIN users u ON u.id=s.user_id " +
                             "WHERE s.token_hash=?")) {
            ps.setString(1, hash);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                if (Instant.parse(rs.getString("expires_at")).isBefore(Clock.now())) {
                    destroySession(token);
                    return null;
                }
                return new User(rs.getInt("id"), rs.getString("email"), rs.getString("name"));
            }
        }
    }

    static void destroySession(String token) throws Exception {
        if (token == null) return;
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE token_hash=?")) {
            ps.setString(1, Util.sha256(token));
            ps.executeUpdate();
        }
    }

    static void purgeExpiredSessions() throws Exception {
        try (Connection c = Db.open();
             PreparedStatement ps = c.prepareStatement("DELETE FROM sessions WHERE expires_at < ?")) {
            ps.setString(1, Clock.nowIso());
            ps.executeUpdate();
        }
    }

    static Membership requireMember(Connection c, int projectId, int userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT m.project_id,m.user_id,m.role,p.owner_id FROM project_members m " +
                        "JOIN projects p ON p.id=m.project_id WHERE m.project_id=? AND m.user_id=?")) {
            ps.setInt(1, projectId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new ApiException(403, "You are not a member of this project");
                return new Membership(rs.getInt("project_id"), rs.getInt("user_id"), rs.getString("role"));
            }
        }
    }

    static boolean isOwner(Connection c, int projectId, int userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT owner_id FROM projects WHERE id=?")) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt("owner_id") == userId;
            }
        }
    }

    static void requireOwner(Connection c, int projectId, int userId) throws SQLException {
        if (!isOwner(c, projectId, userId)) throw new ApiException(403, "Only the project owner can do this");
    }

    static List<User> members(Connection c, int projectId) throws SQLException {
        List<User> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT u.id,u.email,u.name FROM project_members m JOIN users u ON u.id=m.user_id " +
                        "WHERE m.project_id=? ORDER BY m.joined_at, u.id")) {
            ps.setInt(1, projectId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.add(new User(rs.getInt("id"), rs.getString("email"), rs.getString("name")));
            }
        }
        return out;
    }

    static JsonArray memberIds(Connection c, int projectId) throws SQLException {
        JsonArray arr = new JsonArray();
        for (User u : members(c, projectId)) arr.add(u.id());
        return arr;
    }

    static String projectRole(Connection c, int projectId, int userId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT role FROM project_members WHERE project_id=? AND user_id=?")) {
            ps.setInt(1, projectId);
            ps.setInt(2, userId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }
}
