package af;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/** SQLite storage. One short-lived connection per request, WAL enabled. */
public final class Db {
    private static String url;

    private Db() {}

    public static synchronized void init(Path databaseFile) throws Exception {
        Files.createDirectories(databaseFile.toAbsolutePath().getParent());
        url = "jdbc:sqlite:" + databaseFile.toAbsolutePath();
        Class.forName("org.sqlite.JDBC");
        try (Connection c = open(); Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=10000");
            migrateSubmissions(c, st);
            for (String sql : Schema.STATEMENTS) {
                st.execute(sql);
            }
        }
        write(c -> {
            Reports.migratePlans(c);
            return null;
        });
    }

    private static void migrateSubmissions(Connection c, Statement st) throws SQLException {
        try (var rs = st.executeQuery("SELECT sql FROM sqlite_schema WHERE type='table' AND name='submissions'")) {
            if (!rs.next() || rs.getString(1).contains("'withdrawn'")) return;
        }
        st.execute("PRAGMA foreign_keys=OFF");
        c.setAutoCommit(false);
        try {
            st.execute(Schema.SUBMISSIONS.replace("IF NOT EXISTS submissions", "submissions_upgrade"));
            st.execute("""
                    INSERT INTO submissions_upgrade(id,project_id,task_id,version,content,artifact_name,
                      artifact_text,criteria_snapshot,status,created_by,created_at,finalized_at,points_snapshot,all_authors_confirm)
                    SELECT s.id,s.project_id,s.task_id,s.version,s.content,s.artifact_name,s.artifact_text,
                      s.criteria_snapshot,s.status,s.created_by,s.created_at,s.finalized_at,
                      COALESCE(ts.points,t.planned_points),0
                    FROM submissions s JOIN tasks t ON t.id=s.task_id
                    LEFT JOIN task_scores ts ON ts.submission_id=s.id
                    """);
            st.execute("DROP TABLE submissions");
            st.execute("ALTER TABLE submissions_upgrade RENAME TO submissions");
            try (var rs = st.executeQuery("PRAGMA foreign_key_check")) {
                if (rs.next()) throw new SQLException("Foreign key check failed during submission migration");
            }
            c.commit();
        } catch (SQLException ex) {
            c.rollback();
            throw ex;
        } finally {
            c.setAutoCommit(true);
            st.execute("PRAGMA foreign_keys=ON");
        }
    }

    public static Connection open() throws SQLException {
        if (url == null) {
            throw new IllegalStateException("Db.init() has not been called");
        }
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");
            st.execute("PRAGMA busy_timeout=10000");
        }
        c.setAutoCommit(true);
        return c;
    }

    /**
     * Runs a write transaction. Callers must keep the critical section short.
     * SQLite uses deferred transactions; two votes arriving together can make one
     * transaction upgrade its snapshot and receive SQLITE_BUSY. The whole
     * transaction is retried in that case, which lets duplicate-vote checks and
     * finalization run against the committed state instead of returning a 500.
     */
    public static <T> T write(SqlFunction<Connection, T> work) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 8; attempt++) {
            try (Connection c = open()) {
                c.setAutoCommit(false);
                try {
                    T value = work.apply(c);
                    c.commit();
                    return value;
                } catch (Exception ex) {
                    try {
                        c.rollback();
                    } catch (SQLException ignored) {
                    }
                    throw ex;
                } finally {
                    c.setAutoCommit(true);
                }
            } catch (SQLException ex) {
                if (!isBusy(ex)) throw ex;
                last = ex;
                try {
                    Thread.sleep(40L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
        throw last == null ? new SQLException("database busy") : last;
    }

    private static boolean isBusy(SQLException ex) {
        String message = String.valueOf(ex.getMessage()).toLowerCase();
        return message.contains("database is locked") || message.contains("sqlite_busy")
                || message.contains("database table is locked");
    }

    @FunctionalInterface
    public interface SqlFunction<T, R> {
        R apply(T value) throws Exception;
    }
}
