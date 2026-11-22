#!/usr/bin/env python3
"""Integration cases for restart, reminder visibility, and an old-schema fixture."""
import argparse
import contextlib
import json
import os
import sqlite3
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path

import verify_e2e as api

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "build" / "verification"
START = "2026-11-21T10:00:00Z"
AT_FIRST = "2026-11-21T14:01:00Z"
AT_23 = "2026-11-22T13:01:00Z"
AT_24 = "2026-11-22T14:01:00Z"


@contextlib.contextmanager
def running(data, port, label, seed=False):
    env = {key: value for key, value in os.environ.items() if not key.startswith("AF_")}
    env.update(AF_PORT=str(port), AF_DATA_DIR=str(data), AF_CLOCK_FILE=str(data / "clock.txt"))
    if seed:
        env["AF_SEED_DEMO"] = "1"
    with (OUT / f"{label}-server.log").open("w", encoding="utf-8") as log:
        proc = subprocess.Popen(["java", f"-Daf.root={ROOT}", "-cp",
                                 f"{ROOT}/build/classes:{ROOT}/server/lib/*", "af.Server"], env=env,
                                stdout=log, stderr=subprocess.STDOUT)
        try:
            for _ in range(100):
                if proc.poll() is not None:
                    raise RuntimeError(f"Server stopped. See {log.name}")
                try:
                    with urllib.request.urlopen(api.BASE + "/api/health", timeout=0.4) as response:
                        if json.load(response)["time"] == (data / "clock.txt").read_text().strip():
                            break
                except (urllib.error.URLError, TimeoutError):
                    pass
                time.sleep(0.1)
            else:
                raise RuntimeError(f"Server failed to start. See {log.name}")
            yield
        finally:
            proc.terminate()
            try:
                proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait()


def reminder_rows(data, ids):
    with sqlite3.connect(data / "af.db") as db:
        return db.execute(
            "SELECT task_id,created_at,follow_up_at,resolved_at FROM reminders WHERE task_id IN (?,?) ORDER BY task_id",
            ids).fetchall()


def add_task(alice, project, branch, user, title, authors=None):
    body = {"title": title, "assigneeId": user, "branchId": branch, "points": 1,
            "checkpointAt": "2026-11-21T11:00:00Z", "deadlineAt": "2026-12-01T00:00:00Z",
            "expectedOutput": "Report", "acceptanceCriteria": "Include dated observations"}
    if authors:
        body["authors"] = authors
    return alice.post(f"/api/projects/{project}/tasks", body)["id"]


def prepare_legacy(data, project, task, report):
    # Rebuild only this synthetic, stopped SQLite database with the pre-withdrawal schema.
    # Child rows still point to submissions, exactly as they did before the upgrade.
    with sqlite3.connect(data / "af.db") as db:
        sql = db.execute("SELECT sql FROM sqlite_schema WHERE name='submissions'").fetchone()[0]
        lines = ("points_snapshot", "all_authors_confirm", "withdrawal_reason", "pdf BLOB")
        old = "\n".join(line for line in sql.splitlines() if not any(key in line for key in lines))
        old = old.replace("CREATE TABLE submissions", "CREATE TABLE old_submissions").replace(",'withdrawn'", "")
        db.execute(old)
        db.execute("DROP TABLE submissions")
        db.execute("ALTER TABLE old_submissions RENAME TO submissions")
        db.execute("""INSERT INTO submissions(id,project_id,task_id,version,content,artifact_name,artifact_text,
                      criteria_snapshot,status,created_by,created_at,finalized_at)
                      VALUES(70,?,?,1,'Old summary','old.txt','Original synthetic file','Dated facts',
                             'superseded',2,?,?)""", (project, task, AT_24, AT_24))
        db.execute("INSERT INTO submission_authors VALUES(70,2,'Ben Wu',60,?)", (AT_24,))
        db.execute("INSERT INTO submission_authors VALUES(70,3,'Cara Lin',40,NULL)")
        db.execute("DELETE FROM report_plans WHERE report_id=?", (report,))
        db.execute("DELETE FROM task_authors WHERE task_id=?", (task,))
        db.execute("INSERT INTO task_authors VALUES(?,?,100)", (task, 4))
        assert not db.execute("PRAGMA foreign_key_check").fetchall()


def main():
    cli = argparse.ArgumentParser()
    cli.add_argument("--port", default=18772, type=int)
    port = cli.parse_args().port
    api.BASE = f"http://127.0.0.1:{port}"
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="af-lifecycle-") as temporary:
        data = Path(temporary)
        clock = data / "clock.txt"

        def set_time(stamp):
            clock.write_text(stamp + "\n", encoding="utf-8")

        set_time(START)
        with running(data, port, "lifecycle-initial", seed=True):
            alice, ben, cara, dan, eve = [api.Client(f"{name}@demo.edu") for name in
                                        ("alice", "ben", "cara", "dan", "eve")]
            project = alice.post("/api/projects", {"name": "Lifecycle fixture", "plannedPoints": 10})
            pid = project["id"]
            for member in (ben, cara, dan):
                member.post("/api/projects/join", {"inviteCode": project["inviteCode"]})
            board = api.board(alice, pid)
            branch = board["branches"][0]["id"]
            project_url = f"/api/projects/{pid}"
            ids = (add_task(alice, pid, branch, 2, "Late response"),
                   add_task(alice, pid, branch, 3, "Needs help"))
            late, helped = [f"{project_url}/tasks/{task}" for task in ids]
            empty = api.board(alice, pid)["contribution"]
            api.check(empty["acceptedPoints"] == 0 and empty["completionPct"] == 0 and not empty["hasAccepted"]
                      and "No accepted contributions yet" in alice.get(project_url + "/report/export?tasks=1"),
                      "I-01 zero accepted points display 0% project completion and unavailable personal shares")
            set_time(AT_FIRST)
            api.check(eve.status_of("GET", project_url + "/board") == 403 and not reminder_rows(data, ids),
                      "I-02 denied board access does not initiate reminder evaluation")
            api.board(ben, pid)
            api.board(alice, pid)
            api.check(reminder_rows(data, ids) == [(task, AT_FIRST, None, None) for task in ids]
                      and any(e["type"] == "reminder_private" for e in ben.get(late)["events"])
                      and not any(e["type"] == "reminder_private" for e in dan.get(late)["events"]),
                      "I-03 each task gets one private reminder at first evaluation, visible only to its recipient")
        set_time(AT_23)
        with running(data, port, "lifecycle-restart"):
            api.board(alice, pid)
            api.check(reminder_rows(data, ids) == [(task, AT_FIRST, None, None) for task in ids],
                      "I-04 exactly 23 hours after first reminder and restart, neither reminder escalates or resets")
            cara.post(helped + "/support", {"kind": "help", "body": "A second interviewer is needed.",
                                           "neededSupport": "Find one interviewer."})
            set_time(AT_24)
            grouped = api.board(alice, pid)
            states = {entry["id"]: entry["state"] for entry in grouped["tasks"]}
            api.check(states[ids[0]] == "follow_up" and states[ids[1]] == "blocked"
                      and not next(t for t in grouped["tasks"] if t["id"] == ids[1])["followUp"],
                      "I-05 at 24 hours only silence escalates; a help request keeps the other task blocked")
            api.board(alice, pid)
            api.check(reminder_rows(data, ids) == [(ids[0], AT_FIRST, AT_24, None),
                                                  (ids[1], AT_FIRST, None, AT_24)],
                      "I-06 refreshing preserves the original and escalation timestamps with one row each")
            alice.post(late + "/reassign", {"assigneeId": 4, "reason": "Dan will coordinate the interviews."})
            api.check(any(event["type"] == "reminder_private" for event in ben.get(late)["events"])
                      and not any(event["type"] == "reminder_private" for event in dan.get(late)["events"]),
                      "I-07 a handoff does not disclose the original private reminder to the new assignee")
            legacy = add_task(alice, pid, branch, 2, "Legacy fixture",
                              [{"userId": 2, "sharePct": 60}, {"userId": 3, "sharePct": 40}])
            report = alice.post(project_url + "/reports", {"taskId": legacy, "reason": "Legacy report"})["id"]
            alice.post(f"{project_url}/tasks/{legacy}/reassign", {"assigneeId": 4, "reason": "Legacy handoff"})
        prepare_legacy(data, pid, legacy, report)
        with running(data, port, "lifecycle-legacy"):
            restored = dan.get(f"{project_url}/reports/{report}")
            plan = restored["facts"]["plan"]
            api.check(plan["assignee"] == "Ben Wu" and [a["sharePct"] for a in plan["authors"]] == [60, 40]
                      and restored["canReview"] and cara.get(f"{project_url}/reports/{report}")["canRespond"],
                      "I-08 migration restores report-time roles even when handoff and report share a timestamp")
            old_version = ben.get(f"{project_url}/tasks/{legacy}")["submissions"][0]
            api.check(old_version["status"] == "superseded" and old_version["artifactText"] == "Original synthetic file"
                      and old_version["withdrawalReason"] is None,
                      "I-09 migration retains historical superseded status and original artifact")
            with sqlite3.connect(data / "af.db") as db:
                api.check(db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
                          and not db.execute("PRAGMA foreign_key_check").fetchall(),
                          "I-10 legacy upgrade preserves SQLite integrity and foreign keys")
        with running(data, port, "lifecycle-post-migration-restart"):
            api.check(dan.get(f"{project_url}/reports/{report}") == restored,
                      "I-11 restart preserves the recovered snapshot and actionable roles")
    (OUT / "lifecycle-results.json").write_text(json.dumps({"passed": api.PASSED}, ensure_ascii=False, indent=2),
                                                encoding="utf-8")
    print(f"Lifecycle integration: {len(api.PASSED)} cases passed")


if __name__ == "__main__":
    main()
