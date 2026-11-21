package af;

/** Database schema. Kept in one place so a fresh checkout initializes identically. */
final class Schema {
    private Schema() {}

    static final String SUBMISSIONS = """
        CREATE TABLE IF NOT EXISTS submissions (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
          task_id INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
          version INTEGER NOT NULL,
          content TEXT NOT NULL DEFAULT '',
          artifact_name TEXT NOT NULL DEFAULT '',
          artifact_text TEXT NOT NULL DEFAULT '',
          criteria_snapshot TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'pending'
            CHECK (status IN ('pending','accepted','rejected','superseded','withdrawn')),
          created_by INTEGER NOT NULL REFERENCES users(id),
          created_at TEXT NOT NULL,
          finalized_at TEXT,
          points_snapshot INTEGER NOT NULL,
          all_authors_confirm INTEGER NOT NULL DEFAULT 1,
          withdrawal_reason TEXT,
          pdf BLOB,
          UNIQUE (task_id, version)
        )
        """;

    static final String[] STATEMENTS = {
        """
        CREATE TABLE IF NOT EXISTS users (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          email TEXT NOT NULL UNIQUE COLLATE NOCASE,
          name TEXT NOT NULL,
          password_hash TEXT NOT NULL,
          created_at TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS sessions (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          token_hash TEXT NOT NULL UNIQUE,
          created_at TEXT NOT NULL,
          expires_at TEXT NOT NULL,
          persistent INTEGER NOT NULL DEFAULT 0
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS projects (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          name TEXT NOT NULL,
          description TEXT NOT NULL DEFAULT '',
          planned_points INTEGER NOT NULL DEFAULT 100 CHECK (planned_points > 0),
          invite_code TEXT NOT NULL UNIQUE,
          owner_id INTEGER NOT NULL REFERENCES users(id),
          created_at TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS project_members (
          project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
          user_id INTEGER NOT NULL REFERENCES users(id) ON DELETE CASCADE,
          role TEXT NOT NULL DEFAULT 'member',
          joined_at TEXT NOT NULL,
          PRIMARY KEY (project_id, user_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS branches (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
          name TEXT NOT NULL,
          planned_points INTEGER NOT NULL DEFAULT 0,
          position INTEGER NOT NULL DEFAULT 0,
          created_at TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS tasks (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
          branch_id INTEGER NOT NULL REFERENCES branches(id),
          title TEXT NOT NULL,
          description TEXT NOT NULL DEFAULT '',
          assignee_id INTEGER NOT NULL REFERENCES users(id),
          planned_points INTEGER NOT NULL CHECK (planned_points > 0),
          checkpoint_at TEXT NOT NULL,
          deadline_at TEXT NOT NULL,
          expected_output TEXT NOT NULL,
          acceptance_criteria TEXT NOT NULL,
          created_by INTEGER NOT NULL REFERENCES users(id),
          original_plan TEXT NOT NULL DEFAULT '',
          created_at TEXT NOT NULL,
          updated_at TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS task_authors (
          task_id INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
          user_id INTEGER NOT NULL REFERENCES users(id),
          share_pct REAL NOT NULL CHECK (share_pct > 0 AND share_pct <= 100),
          PRIMARY KEY (task_id, user_id)
        )
        """,
        SUBMISSIONS,
        """
        CREATE TABLE IF NOT EXISTS submission_authors (
          submission_id INTEGER NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
          user_id INTEGER NOT NULL REFERENCES users(id),
          name_snapshot TEXT NOT NULL,
          share_pct REAL NOT NULL,
          confirmed_at TEXT,
          PRIMARY KEY (submission_id, user_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS submission_eligible (
          submission_id INTEGER NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
          user_id INTEGER NOT NULL REFERENCES users(id),
          role TEXT NOT NULL CHECK (role IN ('author','voter')),
          PRIMARY KEY (submission_id, user_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS votes (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          submission_id INTEGER NOT NULL REFERENCES submissions(id) ON DELETE CASCADE,
          voter_id INTEGER NOT NULL REFERENCES users(id),
          choice TEXT NOT NULL CHECK (choice IN ('agree','disagree')),
          created_at TEXT NOT NULL,
          UNIQUE (submission_id, voter_id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS task_scores (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          task_id INTEGER NOT NULL UNIQUE REFERENCES tasks(id) ON DELETE CASCADE,
          submission_id INTEGER NOT NULL UNIQUE REFERENCES submissions(id),
          points INTEGER NOT NULL,
          accepted_at TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS task_events (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
          task_id INTEGER REFERENCES tasks(id) ON DELETE CASCADE,
          type TEXT NOT NULL,
          actor_id INTEGER REFERENCES users(id),
          visibility TEXT NOT NULL DEFAULT 'project' CHECK (visibility IN ('project','private')),
          body TEXT NOT NULL DEFAULT '',
          payload TEXT NOT NULL DEFAULT '{}',
          created_at TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS reminders (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
          task_id INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
          assignee_id INTEGER NOT NULL REFERENCES users(id),
          checkpoint_at TEXT NOT NULL,
          created_at TEXT NOT NULL,
          resolved_at TEXT,
          follow_up_at TEXT,
          follow_up_resolved_at TEXT,
          UNIQUE (task_id, checkpoint_at)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS task_support (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
          task_id INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
          user_id INTEGER NOT NULL REFERENCES users(id),
          kind TEXT NOT NULL CHECK (kind IN ('help','blocker')),
          body TEXT NOT NULL,
          needed_support TEXT NOT NULL DEFAULT '',
          created_at TEXT NOT NULL,
          resolved_at TEXT,
          resolved_by INTEGER REFERENCES users(id)
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS date_changes (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
          task_id INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
          old_deadline TEXT NOT NULL,
          new_deadline TEXT NOT NULL,
          reason TEXT NOT NULL,
          requested_by INTEGER NOT NULL REFERENCES users(id),
          created_at TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'pending'
            CHECK (status IN ('pending','approved','rejected')),
          decided_by INTEGER REFERENCES users(id),
          decided_at TEXT,
          decision_note TEXT NOT NULL DEFAULT ''
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS messages (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
          task_id INTEGER REFERENCES tasks(id) ON DELETE SET NULL,
          author_id INTEGER NOT NULL REFERENCES users(id),
          body TEXT NOT NULL,
          created_at TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS reports (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
          task_id INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
          reporter_id INTEGER NOT NULL REFERENCES users(id),
          reason TEXT NOT NULL,
          status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open','closed')),
          created_at TEXT NOT NULL,
          closed_at TEXT
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS report_statements (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          report_id INTEGER NOT NULL REFERENCES reports(id) ON DELETE CASCADE,
          author_id INTEGER NOT NULL REFERENCES users(id),
          kind TEXT NOT NULL CHECK (kind IN ('response','review')),
          body TEXT NOT NULL,
          created_at TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS reassignments (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
          task_id INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
          from_user_id INTEGER NOT NULL REFERENCES users(id),
          to_user_id INTEGER NOT NULL REFERENCES users(id),
          actor_id INTEGER NOT NULL REFERENCES users(id),
          reason TEXT NOT NULL,
          original_plan TEXT NOT NULL,
          created_at TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS report_plans (
          report_id INTEGER PRIMARY KEY REFERENCES reports(id) ON DELETE CASCADE,
          snapshot TEXT NOT NULL
        )
        """,
        """
        CREATE TABLE IF NOT EXISTS plan_changes (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          project_id INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
          branch_id INTEGER REFERENCES branches(id),
          old_points INTEGER NOT NULL,
          new_points INTEGER NOT NULL,
          reason TEXT NOT NULL,
          actor_id INTEGER NOT NULL REFERENCES users(id),
          created_at TEXT NOT NULL
        )
        """,
        "CREATE INDEX IF NOT EXISTS idx_members_user ON project_members(user_id)",
        "CREATE INDEX IF NOT EXISTS idx_tasks_project ON tasks(project_id)",
        "CREATE INDEX IF NOT EXISTS idx_submissions_task ON submissions(task_id, version)",
        "CREATE INDEX IF NOT EXISTS idx_events_project ON task_events(project_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_messages_project ON messages(project_id, created_at)",
        "CREATE INDEX IF NOT EXISTS idx_reminders_task ON reminders(task_id)",
        "CREATE INDEX IF NOT EXISTS idx_support_task ON task_support(task_id, resolved_at)"
    };
}
