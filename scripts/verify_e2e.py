#!/usr/bin/env python3
"""End-to-end acceptance run for the local Anti-freerider build.

Run against a freshly reset demo database:

    ./scripts/reset-demo.sh
    AF_SEED_DEMO=1 ./scripts/start.sh   # in another terminal

Then:

    python3 scripts/verify_e2e.py

The script uses four independent cookie jars for the four demo members, exercises the same
JSON API the browser uses, and writes machine-readable evidence under build/verification/.
"""
from __future__ import annotations

import html as html_module
import http.cookiejar
import json
import os
import re
import sqlite3
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = os.environ.get("AF_BASE_URL", "http://127.0.0.1:8765")
DATA = ROOT / "data"
OUT = ROOT / "build" / "verification"
CLOCK = DATA / "clock.txt"
T0 = "2026-11-21T10:00:00Z"

PASSED: list[str] = []
ARTIFACTS: dict[str, object] = {}
FAILED: list[str] = []


def check(condition: bool, label: str, detail: object = None) -> None:
    if not condition:
        message = label + ("" if detail is None else f" :: {detail!r}")
        FAILED.append(message)
        raise AssertionError(message)
    PASSED.append(label)
    print(f"  PASS  {label}")


class Client:
    def __init__(self, email: str | None = None, password: str = "demo-pass-123"):
        self.jar = http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.jar))
        self.email = email
        if email:
            self.login(email, password)

    def raw(self, method: str, path: str, body: dict | None = None):
        data = None
        headers = {}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request) as response:
                raw = response.read()
                status = response.status
        except urllib.error.HTTPError as error:
            raw = error.read()
            status = error.code
        try:
            payload = json.loads(raw) if raw else {}
        except Exception:
            payload = raw.decode("utf-8", errors="replace")
        return status, payload

    def request(self, method: str, path: str, body: dict | None = None, allow: tuple[int, ...] = ()):
        status, payload = self.raw(method, path, body)
        if status >= 400 and status not in allow:
            raise AssertionError(f"{method} {path} -> {status}: {payload}")
        return status, payload

    def login(self, email: str, password: str = "demo-pass-123"):
        status, payload = self.request("POST", "/api/auth/login",
                                       {"email": email, "password": password, "remember": True})
        assert status == 200 and payload.get("user", {}).get("email") == email, payload
        return payload

    def get(self, path: str, allow: tuple[int, ...] = ()):
        return self.request("GET", path, allow=allow)[1]

    def post(self, path: str, body: dict | None = None, allow: tuple[int, ...] = ()):
        return self.request("POST", path, body or {}, allow=allow)[1]

    def status_of(self, method: str, path: str, body: dict | None = None):
        status, _ = self.raw(method, path, body)
        return status


def set_clock(value: str):
    DATA.mkdir(parents=True, exist_ok=True)
    CLOCK.write_text(value + "\n", encoding="utf-8")
    time.sleep(0.02)


def db():
    return sqlite3.connect(DATA / "af.db")


def project_id(client: Client, name: str) -> int:
    for project in client.get("/api/projects")["projects"]:
        if project["name"] == name:
            return project["id"]
    raise AssertionError(f"project not found: {name}")


def board(client: Client, pid: int):
    return client.get(f"/api/projects/{pid}/board")


def find_task(board_payload, task_id: int):
    for task in board_payload["tasks"]:
        if task["id"] == task_id:
            return task
    raise AssertionError(f"task not found: {task_id}")


def find_member_row(board_payload, user_id: int):
    for row in board_payload["memberRows"]:
        if row["id"] == user_id:
            return row
    raise AssertionError(f"member row not found: {user_id}")


def submit(client: Client, pid: int, tid: int, content: str, name: str, text: str):
    payload = client.post(f"/api/projects/{pid}/tasks/{tid}/submissions",
                          {"content": content, "artifactName": name, "artifactText": text})
    return payload["submissions"][0]


def confirm(client: Client, pid: int, tid: int, sid: int):
    return client.post(f"/api/projects/{pid}/tasks/{tid}/submissions/{sid}/confirm")


def vote(client: Client, pid: int, tid: int, sid: int, choice: str, allow: tuple[int, ...] = ()):
    return client.post(f"/api/projects/{pid}/tasks/{tid}/submissions/{sid}/vote",
                       {"choice": choice}, allow=allow)


def assert_no_vote_fields(label: str, payload: object):
    text = json.dumps(payload, ensure_ascii=False)
    for forbidden in ['"choice"', '"voter"', '"votes"', '"vote"', '"myVote"', '"disagree"', '"agree"',
                      "voteCount", "voterName"]:
        check(forbidden not in text, f"{label}: no {forbidden} field")
    check("投票时间" not in text or "个人投票时间" in text, f"{label}: no private vote-time payload")


def parse_export(html: str):
    match = re.search(r'<script type="application/json" id="report-data">(.*?)</script>', html, re.S)
    if not match:
        raise AssertionError("export is missing the recomputable JSON appendix")
    return json.loads(html_module.unescape(match.group(1)))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    set_clock(T0)
    print("[1] registration, sessions, four independent accounts and an outsider")
    newcomer = Client()
    status, payload = newcomer.request("POST", "/api/auth/register", {
        "name": "New Student", "email": "newcomer@demo.edu", "password": "new-pass-123"
    })
    check(status == 200 and payload["user"]["email"] == "newcomer@demo.edu",
          "real registration creates an account and session", payload)
    check(newcomer.status_of("GET", "/api/me") == 200, "registered session can read /api/me")
    status, payload = newcomer.request("POST", "/api/auth/register", {
        "name": "New Student", "email": "newcomer@demo.edu", "password": "new-pass-123"
    }, allow=(409,))
    check(status == 409, "duplicate email registration is rejected", payload)
    newcomer.post("/api/auth/logout")
    check(newcomer.status_of("GET", "/api/me") == 401, "logout invalidates the session")

    alice = Client("alice@demo.edu")
    ben = Client("ben@demo.edu")
    cara = Client("cara@demo.edu")
    dan = Client("dan@demo.edu")
    eve = Client("eve@demo.edu")
    for client in (alice, ben, cara, dan, eve):
        check(client.get("/api/me")["user"]["email"] == client.email, f"session belongs to {client.email}")

    main_pid = project_id(alice, "Research Methods 2026")
    math_pid = project_id(alice, "Contribution Math 100")
    main_board = board(alice, main_pid)
    check(main_board["contribution"]["acceptedPoints"] == 0, "fresh demo starts with no accepted main points")
    check(main_board["contribution"]["hasAccepted"] is False, "fresh demo shows 暂无已验收贡献")
    check(len(main_board["members"]) == 4, "main project has four members")
    check(len(main_board["branches"]) == 2, "main project has two branches")
    task_ids = {task["title"].split(" / ")[0]: task["id"] for task in main_board["tasks"]}
    t1 = task_ids["Questionnaire design & pilot"]
    t2 = task_ids["Interview data collection"]
    t3 = task_ids["Questionnaire analysis report"]
    t4 = task_ids["Integrated final report"]

    print("[2] private reminders are not yet due; T1 version 1 stays pending after an early disagreement")
    set_clock("2026-11-21T10:01:00Z")
    sub1 = submit(alice, main_pid, t1, "Completed the questionnaire pilot with five respondents.",
                  "questionnaire-v1.md", "Questionnaire v1. Five sections. Pilot changed wording in section 3.")
    check(sub1["version"] == 1 and sub1["status"] == "pending", "T1 v1 is pending")
    check(sub1["actions"]["confirmed"] is True, "submitter's author confirmation is recorded")
    confirm(ben, main_pid, t1, sub1["id"])
    check(vote(cara, main_pid, t1, sub1["id"], "disagree")["submissions"][0]["status"] == "pending",
          "an early disagreement does not reveal a result before all votes")
    pending_board = board(alice, main_pid)
    pending_task = find_task(pending_board, t1)
    check(pending_task["state"] == "pending_review", "board still shows 待确认 with a missing vote")
    check(pending_task.get("score") is None, "pending version has no score")
    assert_no_vote_fields("member board while pending", pending_board)
    status, payload = cara.request(
        "POST", f"/api/projects/{main_pid}/tasks/{t1}/submissions/{sub1['id']}/vote",
        {"choice": "agree"}, allow=(409,))
    check(status == 409, "duplicate vote is rejected with 409", payload)
    final_vote = vote(dan, main_pid, t1, sub1["id"], "agree")
    check(final_vote["submissions"][0]["status"] == "rejected", "T1 v1 is rejected after collection completes")
    check(final_vote.get("score") is None, "rejected T1 v1 scores nothing")
    with db() as connection:
        check(connection.execute("SELECT COUNT(*) FROM task_scores WHERE task_id=?", (t1,)).fetchone()[0] == 0,
              "no score row exists for rejected T1 v1")
        check(connection.execute("SELECT COUNT(*) FROM reports WHERE task_id=?", (t1,)).fetchone()[0] == 0,
              "a failed acceptance does not create a report")

    print("[3] revised version v2 is unanimous, accepted, and scores once")
    set_clock("2026-11-21T10:05:00Z")
    sub2 = submit(alice, main_pid, t1, "Revised questionnaire after the pilot feedback.",
                  "questionnaire-v2.md",
                  "Questionnaire v2. All five sections are present. Pilot with six respondents. Revisions documented.")
    check(sub2["version"] == 2 and sub2["status"] == "pending", "T1 v2 is a separate pending version")
    confirm(ben, main_pid, t1, sub2["id"])
    # A second confirmation attempt is rejected, not silently counted twice.
    second_status, second_payload = ben.request(
        "POST", f"/api/projects/{main_pid}/tasks/{t1}/submissions/{sub2['id']}/confirm", {}, allow=(409,))
    check(second_status == 409, "duplicate author confirmation is rejected", second_payload)
    vote(cara, main_pid, t1, sub2["id"], "agree")
    accepted = vote(dan, main_pid, t1, sub2["id"], "agree")
    check(accepted["submissions"][0]["status"] == "accepted", "T1 v2 is accepted")
    check(accepted["submissions"][0]["version"] == 2, "accepted version is v2")
    check(accepted.get("score", {}).get("points") == 30, "T1 v2 scores its 30 planned points")
    detail = alice.get(f"/api/projects/{main_pid}/tasks/{t1}")
    versions = {v["version"]: v["status"] for v in detail["submissions"]}
    check(versions.get(1) == "rejected" and versions.get(2) == "accepted",
          "both T1 versions are retained with distinct outcomes", versions)
    check(len(detail["submissions"]) == 2, "both T1 versions remain visible")
    check(alice.status_of("POST", f"/api/projects/{main_pid}/tasks/{t1}/submissions",
                          {"content": "late retry"}) == 409,
          "re-submitting after acceptance does not create another scored version")
    with db() as connection:
        check(connection.execute("SELECT COUNT(*) FROM task_scores WHERE task_id=?", (t1,)).fetchone()[0] == 1,
              "T1 has exactly one score row")
        check(connection.execute("SELECT COUNT(*) FROM votes WHERE submission_id=?", (sub1["id"],)).fetchone()[0] == 2,
              "T1 v1 kept its two frozen non-author ballots internally")
    contribution = board(alice, main_pid)["contribution"]
    check(contribution["acceptedPoints"] == 30 and contribution["completionPct"] == 25.0,
          "main board recomputes 30/120 = 25% after T1",
          contribution)
    alice_after_t1 = next(m for m in contribution["members"] if m["id"] == 1)
    ben_after_t1 = next(m for m in contribution["members"] if m["id"] == 2)
    check(alice_after_t1["points"] == 18 and ben_after_t1["points"] == 12,
          "T1's 60/40 split produces Alice 18 and Ben 12",
          (alice_after_t1, ben_after_t1))

    print("[4] known-number project reaches 60%, Alice 15 points and 25% of accepted work")
    set_clock("2026-11-21T10:15:00Z")
    math_board = board(alice, math_pid)
    mt = {task["title"].split(" / ")[0]: task["id"] for task in math_board["tasks"]}
    m1 = submit(alice, math_pid, mt["Widget specification"], "Specification complete.",
                "spec.md", "Scope and acceptance examples included.")
    confirm(ben, math_pid, mt["Widget specification"], m1["id"])
    vote(cara, math_pid, mt["Widget specification"], m1["id"], "agree")
    accepted_m1 = vote(dan, math_pid, mt["Widget specification"], m1["id"], "agree")
    check(accepted_m1.get("score", {}).get("points") == 20, "20-point widget task accepted")
    set_clock("2026-11-21T10:20:00Z")
    m2 = submit(cara, math_pid, mt["Dataset validation"], "Validated 1,200 rows and documented missing values.",
                "validation.md", "Row counts match. Missing values documented. Validation date recorded.")
    vote(alice, math_pid, mt["Dataset validation"], m2["id"], "agree")
    vote(ben, math_pid, mt["Dataset validation"], m2["id"], "agree")
    accepted_m2 = vote(dan, math_pid, mt["Dataset validation"], m2["id"], "agree")
    check(accepted_m2.get("score", {}).get("points") == 40, "40-point validation task accepted")
    math_contribution = board(alice, math_pid)["contribution"]
    check(math_contribution["acceptedPoints"] == 60 and math_contribution["completionPct"] == 60.0,
          "known-number project shows 60% completion", math_contribution)
    math_members = {m["id"]: m for m in math_contribution["members"]}
    check(math_members[1]["points"] == 15 and math_members[1]["sharePct"] == 25.0,
          "Alice has 15 points and 25% of accepted work", math_members[1])
    check(math_members[3]["points"] == 40 and math_members[3]["sharePct"] == 66.7,
          "Cara has 40 points and 66.7% of accepted work", math_members[3])
    math_alice_row = find_member_row(board(alice, math_pid), 1)
    check(math_alice_row.get("privateReminder") is None,
          "an accepted task no longer exposes a private checkpoint reminder", math_alice_row)
    with db() as connection:
        reminder_rows = connection.execute(
            "SELECT resolved_at FROM reminders WHERE task_id=?", (mt["Widget specification"],)).fetchall()
        check(all(row[0] is not None for row in reminder_rows),
              "reminders are resolved when the task is accepted", reminder_rows)
    delivered = next(b for b in math_contribution["branches"] if b["name"] == "Delivered work")
    not_delivered = next(b for b in math_contribution["branches"] if b["name"] == "Not yet delivered")
    check(delivered["completionPct"] == 100.0, "delivered branch uses its 60-point denominator", delivered)
    check(not_delivered["hasAccepted"] is False, "branch with no accepted work is marked no contribution", not_delivered)

    print("[5] all-author task in the four-person project scores once")
    set_clock("2026-11-21T10:25:00Z")
    t4sub = submit(ben, main_pid, t4, "Integrated report with contribution appendix.",
                   "final.md", "All sections consistent. Contribution appendix attached.")
    check(t4sub["actions"]["allAuthors"] is True, "T4 is detected as an all-author version")
    check(t4sub["actions"]["role"] == "voter", "all-author version makes every member a voter")
    for client in (alice, ben, cara, dan):
        result = vote(client, main_pid, t4, t4sub["id"], "agree")
    check(result["submissions"][0]["status"] == "pending", "all-author ballots wait for personal confirmations")
    for client in (alice, cara, dan):
        result = confirm(client, main_pid, t4, t4sub["id"])
    check(result["submissions"][0]["status"] == "accepted", "all four author-voters accept T4")
    check(result.get("score", {}).get("points") == 20, "T4 scores its 20 planned points once")
    with db() as connection:
        check(connection.execute("SELECT COUNT(*) FROM task_scores WHERE task_id=?", (t4,)).fetchone()[0] == 1,
              "T4 has exactly one score row")

    print("[6] analysis report accepted; contribution reaches 90/120 = 75%")
    set_clock("2026-11-21T10:30:00Z")
    t3sub = submit(ben, main_pid, t3, "Analysis report with sample, method, tables and limitations.",
                   "analysis-report.md",
                   "Sample: 42 respondents. Method: descriptive analysis. Table 1 is traceable to the dataset. Limitations discussed.")
    confirm(dan, main_pid, t3, t3sub["id"])
    vote(alice, main_pid, t3, t3sub["id"], "agree")
    t3accepted = vote(cara, main_pid, t3, t3sub["id"], "agree")
    check(t3accepted["submissions"][0]["status"] == "accepted", "analysis report accepted")
    check(t3accepted.get("score", {}).get("points") == 40, "analysis report scores 40")
    contribution = board(alice, main_pid)["contribution"]
    check(contribution["acceptedPoints"] == 90 and contribution["completionPct"] == 75.0,
          "main project recomputes 90/120 = 75%", contribution)
    member_points = {m["name"]: m["points"] for m in contribution["members"]}
    check(member_points == {"Alice Chen": 23, "Ben Wu": 45, "Cara Lin": 5, "Dan Zhou": 17},
          "member points trace to the accepted task shares", member_points)

    print("[7] blocked state, deadline negotiation and a manual report")
    set_clock("2026-11-21T10:35:00Z")
    supported = cara.post(f"/api/projects/{main_pid}/tasks/{t2}/support", {
        "kind": "blocker",
        "body": "Two interview groups are missing; consent forms are pending.",
        "neededSupport": "Dan to contact two classmates and confirm consent."
    })
    check(supported["state"] == "blocked", "task with unresolved support shows 受阻")
    blocked_board = board(alice, main_pid)
    cara_row = find_member_row(blocked_board, 3)
    check(cara_row["status"] == "blocked", "member row shows blocked")
    check("contact two classmates" in (cara_row.get("neededSupport") or ""), "blocked row shows needed support")
    check(any(task["id"] == t2 for task in cara_row.get("activeTasks", [])),
          "member row exposes all active tasks for expandable display")
    check(cara_row.get("supportTaskId") == t2, "blocked support points at its task")
    set_clock("2026-11-21T10:36:00Z")
    extension = cara.post(f"/api/projects/{main_pid}/tasks/{t2}/extension", {
        "newDeadline": "2026-11-26T10:00:00Z", "reason": "Interviewees rescheduled to next week."
    })
    change = extension["dateChanges"][0]
    check(change["oldDeadline"] == "2026-11-24T10:00:00Z" and change["newDeadline"] == "2026-11-26T10:00:00Z",
          "extension retains the original and proposed dates", change)
    set_clock("2026-11-21T10:37:00Z")
    decided = alice.post(f"/api/projects/{main_pid}/tasks/{t2}/extension/{change['id']}/decide",
                         {"approve": True, "decisionNote": "Approved after group agreement."})
    decided_change = decided["dateChanges"][0]
    check(decided_change["status"] == "approved" and decided_change["decider"] == "Alice Chen",
          "owner decision and date are recorded", decided_change)
    check(decided["deadlineAt"] == "2026-11-26T10:00:00Z", "approved new deadline is applied")
    check(decided_change["oldDeadline"] == "2026-11-24T10:00:00Z",
          "original deadline remains available in history")
    set_clock("2026-11-21T10:38:00Z")
    report = alice.post(f"/api/projects/{main_pid}/reports", {
        "taskId": t2,
        "reason": "Two interview groups are still missing after the checkpoint; the analysis report needs the material."
    })
    check(report["canRespond"] is True and report["canReview"] is False,
          "reporter is involved and cannot review their own report")
    response = cara.post(f"/api/projects/{main_pid}/reports/{report['id']}/respond",
                         {"body": "I recorded the blocker and requested Dan's support; the new deadline was approved."})
    review = ben.post(f"/api/projects/{main_pid}/reports/{report['id']}/review",
                      {"body": "I reviewed the record. The blocker is documented, but delivery is still missing."})
    check(len(review["statements"]) == 2, "involved response and uninvolved review are both retained")
    check(review["canReview"] is True, "uninvolved member still sees the review action")
    with db() as connection:
        check(connection.execute("SELECT COUNT(*) FROM reports WHERE project_id=?", (main_pid,)).fetchone()[0] == 1,
              "exactly one manually created report exists")

    print("[7b] a report and a version retain the original co-authors after handoff")
    hand_project = alice.post("/api/projects", {"name": "Handoff Lab", "plannedPoints": 10})
    hand_pid = hand_project["id"]
    for client in (ben, cara, dan):
        client.post("/api/projects/join", {"inviteCode": hand_project["inviteCode"]})
    handover = alice.post(f"/api/projects/{hand_pid}/tasks", {
        "branchId": board(alice, hand_pid)["branches"][0]["id"], "title": "Handover check / 交接检查",
        "assigneeId": 2, "points": 10, "checkpointAt": "2026-11-28T10:00:00Z",
        "deadlineAt": "2026-11-29T10:00:00Z", "expectedOutput": "Handover note",
        "acceptanceCriteria": "The handover note is dated.",
        "authors": [{"userId": 2, "sharePct": 60}, {"userId": 3, "sharePct": 40}]
    })
    hand_tid = handover["id"]
    task_path = f"/api/projects/{hand_pid}/tasks/{hand_tid}"
    report = alice.post(f"/api/projects/{hand_pid}/reports", {"taskId": hand_tid, "reason": "Handoff review"})
    report_path = f"/api/projects/{hand_pid}/reports/{report['id']}"
    reassigned = alice.post(task_path + "/reassign", {
        "assigneeId": 4, "reason": "Dan will coordinate the handover check."
    })
    check(reassigned["assigneeId"] == 4, "reassignment changes only the current assignee")
    shares = [("Ben Wu", 60), ("Cara Lin", 40)]
    def author_shares(rows):
        return [(row["name"], row["sharePct"]) for row in rows]
    check(author_shares(reassigned["planAuthors"]) == shares, "handoff preserves Ben/Cara 60/40")
    history = reassigned["reassignments"][0]
    check(history["fromName"] == "Ben Wu" and history["toName"] == "Dan Zhou",
          "reassignment history records both sides")
    check(history["reason"] == "Dan will coordinate the handover check.",
          "reassignment history records the reason")
    check(author_shares(history["originalPlanAuthors"]) == shares, "handoff history retains original authors")
    for member in (ben, cara):
        check(member.get(report_path)["canRespond"], "a frozen report party can respond after handoff")
        member.post(report_path + "/respond", {"body": "This was my original assignment."})
        check(member.status_of("POST", report_path + "/review", {"body": "Review"}) == 403,
              "a frozen report party cannot review after handoff")
    dan.post(report_path + "/review", {"body": "Reviewed the handoff and original agreement."})
    extension = ben.post(task_path + "/extension", {
        "newDeadline": "2026-11-30T10:00:00Z", "reason": "Class moved the submission date."})
    change_id = extension["dateChanges"][0]["id"]
    alice.post(task_path + f"/extension/{change_id}/decide", {"approve": True})
    snapshot = alice.get(report_path)["facts"]
    check(snapshot["plan"]["assignee"] == "Ben Wu" and author_shares(snapshot["plan"]["authors"]) == shares
          and snapshot["plan"]["currentDeadlineAt"] == "2026-11-29T10:00:00Z",
          "report details keep original owner, authors and deadline despite later changes")
    check(snapshot["reassignments"][0]["toName"] == "Dan Zhou"
          and snapshot["dateChanges"][0]["newDeadline"] == "2026-11-30T10:00:00Z",
          "report facts append the handoff and approved extension")
    before = submit(dan, hand_pid, hand_tid, "Coordination delivery", "handover.txt", "The handover note is dated Nov 29.")
    check(author_shares(before["authors"]) == shares and before["actions"]["canVote"],
          "coordinator submits on behalf of the frozen authors and votes as a non-author")
    check(dan.status_of("POST", task_path + f"/submissions/{before['id']}/confirm", {}) == 403,
          "Dan cannot confirm as an author")
    check(alice.status_of("POST", task_path + "/authors", {"authors": [{"userId": 4, "sharePct": 90}],
          "reason": "Plan for future revisions"}) == 400, "server rejects author shares below 100%")
    changed = alice.post(task_path + "/authors", {"authors": [{"userId": 4, "sharePct": 100}],
                         "reason": "Dan will author any subsequent revision."})
    check(author_shares(changed["planAuthors"]) == [("Dan Zhou", 100)]
          and author_shares(changed["submissions"][0]["authors"]) == shares,
          "explicit future authorship edit keeps the pending version's original shares")
    confirm(ben, hand_pid, hand_tid, before["id"])
    vote(dan, hand_pid, hand_tid, before["id"], "agree")
    vote(alice, hand_pid, hand_tid, before["id"], "agree")
    completed = confirm(cara, hand_pid, hand_tid, before["id"])
    check(completed["submissions"][0]["status"] == "accepted", "both Ben and Cara must confirm independently")
    hand_html = alice.get(f"/api/projects/{hand_pid}/report/export?tasks=1&timeline=1&disputes=1")
    (OUT / "handoff-report.html").write_text(hand_html, encoding="utf-8")
    hand_data = parse_export(hand_html)
    check("Ben Wu 60%" in hand_html and "Cara Lin 40%" in hand_html and "Ben Wu → Dan Zhou" in hand_html,
          "handoff export renders the original agreement beside the later handoff")
    check({m["name"]: m["points"] for m in hand_data["contribution"]["members"]}
          == {"Alice Chen": 0, "Ben Wu": 6, "Cara Lin": 4, "Dan Zhou": 0},
          "accepted contribution keeps the frozen Ben/Cara 60/40 split")

    print("[8] create/join a two-person project, all-author votes and inference warning")
    set_clock("2026-11-21T10:40:00Z")
    pair = alice.post("/api/projects", {
        "name": "Pair Sprint", "description": "Two-person all-author verification project.",
        "plannedPoints": 60, "branchName": "Sprint"
    })
    pair_id = pair["id"]
    preview = ben.get("/api/invites/" + pair["inviteCode"])
    check(preview["project"]["name"] == "Pair Sprint" and preview["alreadyMember"] is False,
          "invite lookup shows the group name before joining", preview)
    ben.post("/api/projects/join", {"inviteCode": pair["inviteCode"]})
    preview_after = ben.get("/api/invites/" + pair["inviteCode"])
    check(preview_after["alreadyMember"] is True, "invite lookup reports existing membership")
    pair_board = board(ben, pair_id)
    check(len(pair_board["members"]) == 2, "invite code join makes Ben the second member")
    pair_branch = pair_board["branches"][0]["id"]
    pair_task = alice.post(f"/api/projects/{pair_id}/tasks", {
        "branchId": pair_branch, "title": "Pair deliverable / 双人成果",
        "description": "Both members author and vote.", "assigneeId": 1, "points": 50,
        "checkpointAt": "2026-11-22T10:00:00Z", "deadlineAt": "2026-11-23T10:00:00Z",
        "expectedOutput": "Joint deliverable",
        "acceptanceCriteria": "Both authors agree.\nArtifact is complete.",
        "authors": [{"userId": 1, "sharePct": 50}, {"userId": 2, "sharePct": 50}]
    })
    pair_sub = submit(alice, pair_id, pair_task["id"], "Joint artifact complete.",
                      "pair.md", "Both members contributed equally.")
    check(pair_sub["actions"]["allAuthors"] is True, "two-person all-author version is detected")
    check(pair_sub["actions"]["role"] == "voter", "both authors also cast anonymous votes")
    check(pair_sub["actions"]["inferenceWarning"] == "", "all-author pair has no only-non-author warning")
    confirm(ben, pair_id, pair_task["id"], pair_sub["id"])
    duplicate_results = []
    barrier = threading.Barrier(2)

    def duplicate_vote():
        client = Client("alice@demo.edu")
        barrier.wait()
        status, payload = client.raw(
            "POST", f"/api/projects/{pair_id}/tasks/{pair_task['id']}/submissions/{pair_sub['id']}/vote",
            {"choice": "agree"})
        duplicate_results.append((status, payload))

    threads = [threading.Thread(target=duplicate_vote) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    statuses = sorted(status for status, _ in duplicate_results)
    check(statuses == [200, 409], "concurrent duplicate votes produce one ballot and one 409", duplicate_results)
    pair_final = vote(ben, pair_id, pair_task["id"], pair_sub["id"], "agree")
    check(pair_final["submissions"][0]["status"] == "accepted", "two-person all-author version accepted")
    pair_contribution = board(alice, pair_id)["contribution"]
    check(pair_contribution["acceptedPoints"] == 50 and pair_contribution["completionPct"] == 83.3,
          "first pair task contributes 50/60", pair_contribution)

    set_clock("2026-11-21T10:45:00Z")
    pair_task2 = alice.post(f"/api/projects/{pair_id}/tasks", {
        "branchId": pair_branch, "title": "Concurrent final vote check / 并发终票检查",
        "description": "Both vote simultaneously.", "assigneeId": 1, "points": 10,
        "checkpointAt": "2026-11-22T10:00:00Z", "deadlineAt": "2026-11-23T10:00:00Z",
        "expectedOutput": "Joint artifact", "acceptanceCriteria": "Both agree.",
        "authors": [{"userId": 1, "sharePct": 50}, {"userId": 2, "sharePct": 50}]
    })
    pair_sub2 = submit(alice, pair_id, pair_task2["id"], "Concurrent artifact.",
                       "concurrent.md", "Two simultaneous final votes.")
    confirm(ben, pair_id, pair_task2["id"], pair_sub2["id"])
    concurrent_results = []
    barrier = threading.Barrier(2)

    def final_vote(email: str):
        client = Client(email)
        barrier.wait()
        status, payload = client.raw(
            "POST",
            f"/api/projects/{pair_id}/tasks/{pair_task2['id']}/submissions/{pair_sub2['id']}/vote",
            {"choice": "agree"})
        concurrent_results.append((status, payload))

    threads = [threading.Thread(target=final_vote, args=("alice@demo.edu",)),
               threading.Thread(target=final_vote, args=("ben@demo.edu",))]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    check(all(status == 200 for status, _ in concurrent_results),
          "simultaneous final votes both succeed after serialization", concurrent_results)
    final_board = board(alice, pair_id)
    final_task = find_task(final_board, pair_task2["id"])
    check(final_task["accepted"] is True, "simultaneous final votes accept the task")
    check(final_task.get("score", {}).get("points") == 10, "concurrent task scores exactly 10")
    with db() as connection:
        check(connection.execute("SELECT COUNT(*) FROM task_scores WHERE task_id=?",
                                 (pair_task2["id"],)).fetchone()[0] == 1,
              "concurrent task has exactly one score row")
    pair_contribution = final_board["contribution"]
    check(pair_contribution["acceptedPoints"] == 60 and pair_contribution["completionPct"] == 100.0,
          "pair project completes at 100%", pair_contribution)

    print("[9] private reminder → <24h → 24h follow-up → response")
    set_clock("2026-11-21T10:50:00Z")
    reminder_project = alice.post("/api/projects", {
        "name": "Reminder Lab", "description": "Controlled-clock reminder verification.",
        "plannedPoints": 10, "branchName": "Checkpoint"
    })
    reminder_pid = reminder_project["id"]
    dan.post("/api/projects/join", {"inviteCode": reminder_project["inviteCode"]})
    reminder_board = board(alice, reminder_pid)
    reminder_branch = reminder_board["branches"][0]["id"]
    reminder_task = alice.post(f"/api/projects/{reminder_pid}/tasks", {
        "branchId": reminder_branch, "title": "Literature check / 文献核对",
        "description": "Reminder clock test.", "assigneeId": 4, "points": 10,
        "checkpointAt": "2026-11-25T10:00:00Z", "deadlineAt": "2026-11-27T10:00:00Z",
        "expectedOutput": "Literature notes",
        "acceptanceCriteria": "At least five sources.\nNotes are dated.",
        "authors": [{"userId": 4, "sharePct": 100}]
    })
    # Explain the intentionally unaccepted math task so it does not add an unrelated reminder.
    math_m3 = mt["Remaining deliverable"]
    dan.post(f"/api/projects/{math_pid}/tasks/{math_m3}/note",
             {"body": "Not started yet; planned for next week, with no submitted work.", "ack": False})
    set_clock("2026-11-25T10:00:00Z")
    alice_view = board(alice, reminder_pid)
    dan_view = board(dan, reminder_pid)
    alice_task = find_task(alice_view, reminder_task["id"])
    dan_row = find_member_row(dan_view, 4)
    check(alice_task.get("followUp") is False, "first reminder is not visible to the group")
    check(dan_row.get("privateReminder") is not None, "assignee receives the private reminder")
    with db() as connection:
        rows = connection.execute("SELECT created_at,follow_up_at,resolved_at FROM reminders WHERE task_id=?",
                                  (reminder_task["id"],)).fetchall()
        check(len(rows) == 1 and rows[0][1] is None, "one private reminder exists before 24 hours", rows)
    for _ in range(3):
        board(alice, reminder_pid)
    with db() as connection:
        check(connection.execute("SELECT COUNT(*) FROM reminders WHERE task_id=?",
                                 (reminder_task["id"],)).fetchone()[0] == 1,
              "repeated board loads do not duplicate the reminder")
    set_clock("2026-11-26T09:59:00Z")
    alice_view = board(alice, reminder_pid)
    dan_view = board(dan, reminder_pid)
    check(find_task(alice_view, reminder_task["id"]).get("followUp") is False,
          "under 24 hours the group still does not see 待跟进")
    check(find_member_row(dan_view, 4).get("privateReminder") is not None,
          "private reminder remains visible to assignee under 24 hours")
    set_clock("2026-11-26T10:01:00Z")
    alice_view = board(alice, reminder_pid)
    dan_view = board(dan, reminder_pid)
    followed = find_task(alice_view, reminder_task["id"])
    check(followed.get("followUp") is True and followed["state"] == "follow_up",
          "after 24 hours the group sees 待跟进")
    check(find_member_row(dan_view, 4)["status"] == "follow_up", "member row switches to 待跟进")
    with db() as connection:
        follow_row = connection.execute(
            "SELECT follow_up_at,resolved_at FROM reminders WHERE task_id=?", (reminder_task["id"],)).fetchone()
        check(follow_row[0] is not None and follow_row[1] is None, "follow-up timestamp is recorded once", follow_row)
    dan.post(f"/api/projects/{reminder_pid}/tasks/{reminder_task['id']}/note",
             {"body": "Started the literature review; notes will be uploaded tomorrow.", "ack": True})
    alice_view = board(alice, reminder_pid)
    dan_view = board(dan, reminder_pid)
    check(find_task(alice_view, reminder_task["id"]).get("followUp") is False,
          "a recorded response removes the group follow-up")
    check(find_member_row(dan_view, 4).get("privateReminder") is None,
          "response resolves the private reminder")
    with db() as connection:
        resolved = connection.execute(
            "SELECT resolved_at,follow_up_at,follow_up_resolved_at FROM reminders WHERE task_id=?",
            (reminder_task["id"],)).fetchone()
        check(all(value is not None for value in resolved), "response timestamps are recorded", resolved)
        check(connection.execute("SELECT COUNT(*) FROM reminders WHERE task_id=?",
                                 (reminder_task["id"],)).fetchone()[0] == 1,
              "resolved reminder is still a single record")

    print("[10] non-members cannot read, vote, export or ask")
    check(eve.status_of("GET", f"/api/projects/{main_pid}/board") == 403,
          "outsider cannot read the board")
    check(eve.status_of("GET", f"/api/projects/{main_pid}/tasks/{t1}") == 403,
          "outsider cannot read a task or its deliverables")
    check(eve.status_of("POST", f"/api/projects/{main_pid}/tasks/{t1}/submissions/{sub2['id']}/vote",
                        {"choice": "agree"}) == 403,
          "outsider cannot vote")
    check(eve.status_of("GET", f"/api/projects/{math_pid}/report/export") == 403,
          "outsider cannot export a report")
    check(eve.status_of("POST", f"/api/projects/{math_pid}/ai", {"question": "谁完成了数据校验"}) == 403,
          "outsider cannot use project evidence Q&A")

    print("[11] evidence Q&A citations stay in the correct project")
    who = alice.post(f"/api/projects/{main_pid}/ai", {"question": "谁完成了问卷分析"})
    check("Questionnaire analysis report" in who["answer"] and "Ben Wu" in who["answer"],
          "who-completed answer cites the analysis report authors", who["answer"][:300])
    check(any(c["taskId"] == t3 and c["submissionId"] == t3sub["id"] for c in who["citations"]),
          "who-completed citation points to the accepted analysis version")
    check(all(c["projectId"] == main_pid for c in who["citations"]), "who-completed citations stay in main project")
    no_context = alice.post(f"/api/projects/{main_pid}/ai", {"question": "这份报告达到了哪些标准"})
    check(not no_context["citations"] and "请先打开" in no_context["answer"], "a contextual question requires a selected target")
    criteria = alice.post(f"/api/projects/{main_pid}/ai", {
        "question": "这份报告达到了哪些标准", "taskId": t3, "submissionId": t3sub["id"]})
    check("Report states the sample and method" in criteria["answer"] and "Questionnaire analysis report" in criteria["answer"],
          "criteria answer quotes the frozen criteria line by line", criteria["answer"][:300])
    check(any(c["submissionId"] == t3sub["id"] for c in criteria["citations"]),
          "criteria citation points to the accepted submission")
    delay = alice.post(f"/api/projects/{main_pid}/ai", {"question": "数据收集为什么延期"})
    check("Interviewees rescheduled" in delay["answer"] and "2026-11-24T10:00:00Z" in delay["answer"],
          "delay answer cites the recorded reason and original date", delay["answer"][:300])
    check(any(c["dateChangeId"] is not None for c in delay["citations"]),
          "delay citation points to the date-change event")
    contribution_answer = alice.post(f"/api/projects/{main_pid}/ai", {"question": "当前贡献比例是多少"})
    check("75%" in contribution_answer["answer"] or "90 / 120" in contribution_answer["answer"],
          "contribution answer uses the server-computed 75%", contribution_answer["answer"][:250])
    math_ai = alice.post(f"/api/projects/{math_pid}/ai", {"question": "谁完成了数据校验"})
    check("Dataset validation" in math_ai["answer"] and "Cara Lin" in math_ai["answer"],
          "second-project answer cites the math project's validation task", math_ai["answer"][:250])
    check(all(c["projectId"] == math_pid for c in math_ai["citations"]),
          "second-project citations do not leak into main project")
    assert_no_vote_fields("evidence Q&A main", who)
    assert_no_vote_fields("evidence Q&A math", math_ai)

    print("[12] export is recomputable and contains no anonymous ballot details")
    status, math_html = alice.request("GET", f"/api/projects/{math_pid}/report/export?tasks=1&timeline=1&disputes=1&reminders=1")
    (OUT / "math-report.html").write_text(math_html, encoding="utf-8")
    math_data = parse_export(math_html)
    appendix = math_data["dataAppendix"]
    accepted_points = sum(score["points"] for score in appendix["scores"])
    recomputed_members: dict[str, float] = {}
    for score in appendix["scores"]:
        for share in score["shares"]:
            recomputed_members[share["name"]] = recomputed_members.get(share["name"], 0) + \
                score["points"] * share["sharePct"] / 100.0
    check(appendix["projectPlannedPoints"] == 100 and accepted_points == 60,
          "export appendix carries 100 planned and 60 accepted points", appendix)
    check(recomputed_members["Alice Chen"] == 15.0, "independent recomputation gives Alice 15 points")
    check(round(recomputed_members["Alice Chen"] / accepted_points * 100, 1) == 25.0,
          "independent recomputation gives Alice 25% of accepted work")
    check(recomputed_members["Cara Lin"] == 40.0, "independent recomputation gives Cara 40 points")
    check("60%" in math_html and "25%" in math_html and "15" in math_html,
          "exported report displays the recomputed 60%, 25% and 15")
    check("60 / 100 = 60%" in math_html, "exported project completion shows its denominator")
    check("15 / 60 = 25%" in math_html, "exported personal share shows both denominators")
    check("个人成果明细" in math_html and "Widget specification" in math_html and "= 15 分" in math_html,
          "exported report lists per-member deliverables, versions and points")
    check("暂无已验收贡献" in math_html, "export displays 暂无已验收贡献 for the empty branch")
    for forbidden in ["choice", "disagree", "voter", '"votes"', '"agree"']:
        check(forbidden not in math_html.lower(), f"math export omits {forbidden}")
    with db() as connection:
        vote_rows = connection.execute("SELECT COUNT(*) FROM votes").fetchone()[0]
    check(vote_rows > 0, "ballots exist internally but are absent from the export")

    status, main_html = alice.request("GET", f"/api/projects/{main_pid}/report/export?tasks=1&timeline=1&disputes=1&reminders=1")
    (OUT / "main-report.html").write_text(main_html, encoding="utf-8")
    main_data = parse_export(main_html)
    check(len(main_data["disputes"]) == 1, "main export includes the manual report")
    for section in ["已记录的事实", "成员陈述", "待解决的分歧"]:
        check(section in main_html, f"main export separates {section}")
    check("Interviewees rescheduled" in main_html, "main export retains the delay reason")
    check("2026-11-24T10:00:00Z" in main_html, "main export retains the original deadline")
    assert_no_vote_fields("main export", main_data)
    for forbidden in ["choice", "disagree", "voter"]:
        check(forbidden not in main_html.lower(), f"main export omits {forbidden}")

    status, reminder_html = alice.request(
        "GET", f"/api/projects/{reminder_pid}/report/export?tasks=1&timeline=1&disputes=1&reminders=1")
    (OUT / "reminder-report.html").write_text(reminder_html, encoding="utf-8")
    reminder_data = parse_export(reminder_html)
    check(len(reminder_data["reminders"]) == 1, "reminder export includes the escalated reminder")
    reminder_fact = reminder_data["reminders"][0]
    check(reminder_fact["groupFollowUpAt"] is not None and reminder_fact["followUpResolvedAt"] is not None,
          "reminder export preserves follow-up and response times", reminder_fact)
    assert_no_vote_fields("reminder export", reminder_data)

    print("[13] persistence/refresh invariants")
    for _ in range(3):
        board(alice, main_pid)
        board(alice, math_pid)
    with db() as connection:
        for task in (t1, t3, t4):
            check(connection.execute("SELECT COUNT(*) FROM task_scores WHERE task_id=?", (task,)).fetchone()[0] == 1,
                  f"task {task} still has exactly one score after refreshes")
        check(connection.execute(
            "SELECT COUNT(*) FROM task_scores WHERE task_id IN (SELECT id FROM tasks WHERE project_id=?)",
            (main_pid,)).fetchone()[0] == 3, "main project has exactly three scored tasks")
        check(connection.execute("SELECT COUNT(*) FROM reports WHERE project_id=?", (main_pid,)).fetchone()[0] == 1,
              "refresh did not create another report")

    ARTIFACTS.update({
        "mainProjectId": main_pid,
        "mathProjectId": math_pid,
        "pairProjectId": pair_id,
        "reminderProjectId": reminder_pid,
        "mainAcceptedPoints": contribution["acceptedPoints"],
        "mainCompletionPct": contribution["completionPct"],
        "mathAcceptedPoints": math_contribution["acceptedPoints"],
        "mathCompletionPct": math_contribution["completionPct"],
        "alicePoints": math_members[1]["points"],
        "aliceSharePct": math_members[1]["sharePct"],
        "passedChecks": len(PASSED),
        "exports": [str(OUT / "math-report.html"), str(OUT / "main-report.html"),
                    str(OUT / "reminder-report.html")],
    })
    (OUT / "results.json").write_text(json.dumps({
        "passed": PASSED,
        "artifacts": ARTIFACTS,
        "failed": FAILED,
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\nAll {len(PASSED)} checks passed. Evidence written to {OUT}/results.json")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        FAILED.append(str(error))
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / "results.json").write_text(json.dumps({
            "passed": PASSED,
            "artifacts": ARTIFACTS,
            "failed": FAILED,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\nFAILED: {error}", file=sys.stderr)
        sys.exit(1)
