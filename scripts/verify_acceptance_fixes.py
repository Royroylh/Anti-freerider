#!/usr/bin/env python3
"""Focused acceptance scenarios. Run after verify_e2e.py on the isolated demo server."""
import base64
import hashlib
import json
import threading

from verify_e2e import BASE, Client, OUT, board, check, parse_export, set_clock, vote


def pdf(text):
    stream = f"BT /F1 16 Tf 40 140 Td ({text}) Tj ET".encode()
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 200] /Contents 4 0 R "
        b"/Resources << /Font << /F1 5 0 R >> >> >>",
        b"<< /Length %d >>\nstream\n" % len(stream) + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    data = b"%PDF-1.4\n"
    offsets = [b"0000000000 65535 f \n"]
    for i, obj in enumerate(objects, 1):
        offsets.append(b"%010d 00000 n \n" % len(data))
        data += b"%d 0 obj\n" % i + obj + b"\nendobj\n"
    return data + (b"xref\n0 6\n" + b"".join(offsets) + b"trailer\n<< /Size 6 /Root 1 0 R >>\n"
                   b"startxref\n%d\n%%%%EOF\n" % len(data))


def race(*operations):
    gate = threading.Barrier(len(operations))
    result = [None] * len(operations)

    def run(i, operation):
        gate.wait(timeout=10)
        result[i] = operation()

    threads = [threading.Thread(target=run, args=(i, op)) for i, op in enumerate(operations)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=15)
    return result


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    set_clock("2026-11-25T08:00:00Z")
    alice, ben, cara, dan, eve = [Client(f"{name}@demo.edu") for name in ("alice", "ben", "cara", "dan", "eve")]

    def project(name, planned, *members):
        created = alice.post("/api/projects", {"name": name, "plannedPoints": planned})
        pid = created["id"]
        for member in members:
            member.post("/api/projects/join", {"inviteCode": created["inviteCode"]})
        return pid, board(alice, pid)["branches"][0]["id"], created["inviteCode"]

    def task_data(branch, title, points=1):
        return {"title": title, "assigneeId": 1, "branchId": branch, "points": points,
                "checkpointAt": "2026-12-01T00:00:00Z", "deadlineAt": "2026-12-03T00:00:00Z",
                "expectedOutput": "Dated observations", "acceptanceCriteria": "样本编号\n统计方法"}

    def task(pid, branch, title, points=1):
        return alice.post(f"/api/projects/{pid}/tasks", task_data(branch, title, points))["id"]

    pid, branch, invite = project("Acceptance Versions", 25, ben, cara, dan)
    tid = task(pid, branch, "问卷分析报告", 5)
    base = f"/api/projects/{pid}/tasks/{tid}"
    for label, body in (
        ("filename only", {"artifactName": "fieldwork.pdf"}),
        ("short summary only", {"content": "已完成"}),
        ("short placeholder pasted as text", {"artifactText": "已完成。"}),
        ("whitespace only", {"artifactText": " \n\t "}),
        ("external URL only", {"artifactText": "https://example.invalid/report.pdf"}),
        ("invalid PDF", {"artifactName": "bad.pdf", "pdfBase64": "bm90IGEgUERG"}),
    ):
        check(alice.status_of("POST", base + "/submissions", body) == 400, label + " cannot open acceptance")
    oversized = base64.b64encode(b"x" * 1_048_577).decode()
    check(alice.status_of("POST", base + "/submissions",
                          {"artifactName": "huge.pdf", "pdfBase64": oversized}) == 413,
          "server enforces 1 MiB PDF limit")
    check(not ben.get(base)["submissions"], "failed submissions leave the task without versions")
    missing = ben.post(f"/api/projects/{pid}/ai", {"question": "《月壤光谱》达到哪些标准？"})
    check("未找到成果" in missing["answer"] and not missing["citations"] and "问卷" not in missing["answer"],
          "named missing work cites no unrelated report")
    check(missing["mode"] == "evidence-retrieval-fallback", "run records deterministic retrieval fallback mode")

    first, second = pdf("First report sample 17"), pdf("Second report sample 38")
    (OUT / "fieldwork-v1.pdf").write_bytes(first)
    (OUT / "fieldwork-v2.pdf").write_bytes(second)

    def upload(data):
        return alice.post(base + "/submissions", {"artifactName": "fieldwork.pdf",
                          "pdfBase64": base64.b64encode(data).decode()})["submissions"][0]

    def downloaded(client, attachment_url):
        with client.opener.open(f"{BASE}{attachment_url}") as response:
            check(response.headers.get_content_type() == "application/pdf", "member receives PDF content type")
            return response.read()

    v1 = upload(first)
    check(downloaded(ben, v1["attachmentUrl"]) == first, "Ben opens the exact v1 PDF bytes")
    check(eve.status_of("GET", v1["attachmentUrl"]) == 403, "outsider cannot fetch attachment bytes")
    vote(ben, pid, tid, v1["id"], "disagree")
    dan.post(f"/api/projects/{pid}/leave")
    check(cara.get(base)["submissions"][0]["status"] == "pending", "departure keeps pending v1 intact")
    check(dan.status_of("GET", v1["attachmentUrl"]) == 403, "former member loses access to project PDF")
    check(alice.status_of("POST", base + "/submissions", {"artifactText": "样本编号 B-38"}) == 409,
          "valid replacement is refused until explicit withdrawal")
    withdraw = base + f"/submissions/{v1['id']}/withdraw"
    reason = "组员退出，重新收集确认"
    check(ben.status_of("POST", withdraw, {"reason": reason}) == 403, "non-author cannot withdraw")
    check(alice.status_of("POST", withdraw, {"reason": "   "}) == 400, "author must give a nonblank reason")
    closed = alice.post(withdraw, {"reason": reason})
    check(closed["submissions"][0]["status"] == "withdrawn" and closed["submissions"][0]["withdrawalReason"] == reason
          and closed["submissions"][0]["finalizedAt"] and not closed.get("score"),
          "withdrawn v1 records reason/date and awards no points")
    check(cara.status_of("POST", base + f"/submissions/{v1['id']}/vote", {"choice": "agree"}) == 409,
          "withdrawn round rejects late ballots")

    v2 = upload(second)
    check(downloaded(ben, v2["attachmentUrl"]) == second and downloaded(ben, v1["attachmentUrl"]) == first,
          "same file name in v2 leaves v1 bytes unchanged")
    for version in (v1, v2):
        answer = ben.post(f"/api/projects/{pid}/ai", {
            "question": "这份报告达到哪些标准", "taskId": tid, "submissionId": version["id"]})
        check(len(answer["citations"]) == 3 and all(c["submissionId"] == version["id"] for c in answer["citations"]),
              f"selected v{version['version']} bounds all evidence citations")
    check(vote(ben, pid, tid, v2["id"], "agree")["submissions"][0]["status"] == "pending",
          "v2 collects ballots from scratch despite Ben having voted in v1")
    check(vote(cara, pid, tid, v2["id"], "agree")["submissions"][0]["status"] == "accepted",
          "v2 uses three current members and scores once after Cara's ballot")
    check(cara.status_of("POST", base + f"/submissions/{v2['id']}/vote", {"choice": "agree"}) == 409,
          "repeated last ballot cannot score again")
    task(pid, branch, "问卷分析报告")
    task(pid, branch, "问卷分析补充报告")
    for named in ("问卷分析报告", "问卷分析"):
        answer = ben.post(f"/api/projects/{pid}/ai", {"question": f"《{named}》达到哪些标准？"})
        check("多个候选" in answer["answer"] and not answer["citations"], f"ambiguous title {named} asks user to choose")

    race_id = task(pid, branch, "Last ballot vs withdrawal", 4)
    race_base = f"/api/projects/{pid}/tasks/{race_id}"
    submission = alice.post(race_base + "/submissions", {"artifactText": "样本编号 R-4。统计方法为中位数。"})["submissions"][0]
    path = race_base + f"/submissions/{submission['id']}"
    vote(ben, pid, race_id, submission["id"], "agree")
    terminal_responses = race(lambda: cara.raw("POST", path + "/vote", {"choice": "agree"}),
                              lambda: alice.raw("POST", path + "/withdraw", {"reason": "Use revised sample"}))
    observed = alice.get(race_base)
    state = observed["submissions"][0]["status"]
    check(sorted(response[0] for response in terminal_responses) == [200, 409]
          and (state, bool(observed.get("score"))) in (("withdrawn", False), ("accepted", True)),
          f"concurrent final vote/withdrawal yields exactly one terminal state ({state})")
    check(sum(event["type"] in ("accepted", "withdrawn") for event in observed["events"]) == 1,
          "only one terminal event is written")

    html = ben.get(f"/api/projects/{pid}/report/export?tasks=1&timeline=1")
    (OUT / "withdrawal-report.html").write_text(html, encoding="utf-8")
    exported = parse_export(html)
    check(reason in html and "已撤回" in html and base64.b64encode(first).decode() in html,
          "export preserves withdrawn date, reason and downloadable original PDF")
    private_keys = {"choice", "voter", "voterId", "votes", "voteCount", "hasVoted", "voteTime"}

    def keys(value):
        if isinstance(value, dict):
            for key, nested in value.items():
                yield key
                yield from keys(nested)
        if isinstance(value, list):
            for item in value:
                yield from keys(item)

    check(not (set(keys(cara.get(base))) | set(keys(board(cara, pid))) | set(keys(exported))) & private_keys,
          "member detail, board and export expose no individual ballot fields")

    ben.post(f"/api/projects/{pid}/leave")
    check(alice.get(base)["score"]["points"] == 5, "additional departure preserves recorded contributions")
    ben.post("/api/projects/join", {"inviteCode": invite})

    def reserve(client, project_id, branch_id, title, amount):
        return client.raw("POST", f"/api/projects/{project_id}/tasks", task_data(branch_id, title, amount))

    small, small_branch, _ = project("Budget Limits", 10, ben)
    task(small, small_branch, "First seven", 7)
    status, error = reserve(ben, small, small_branch, "Second seven", 7)
    check(status == 409 and error["error"].count("剩余 3 分，超出 4 分") == 2,
          "project and branch both report remaining 3 and excess 4")
    check(reserve(ben, small, small_branch, "Reduced to three", 3)[0] == 200, "lowering task to 3 saves")
    other = project("Branch Only", 20, ben)[0]
    alice.post(f"/api/projects/{other}/branches", {"name": "Small branch", "plannedPoints": 4})
    other_branch = next(b["id"] for b in board(alice, other)["branches"] if b["name"] == "Small branch")
    check("分支剩余 4 分，超出 1 分" in reserve(ben, other, other_branch, "Too much", 5)[1]["error"],
          "branch alone can reject a task with project points available")
    combined = project("Project Only", 10, ben)[0]
    alice.post(f"/api/projects/{combined}/branches", {"name": "Large branch", "plannedPoints": 20})
    combined_branch = next(b["id"] for b in board(alice, combined)["branches"] if b["name"] == "Large branch")
    check("项目剩余 10 分，超出 1 分" in reserve(ben, combined, combined_branch, "Too much", 11)[1]["error"],
          "project alone can reject a task with branch points available")

    revised, revised_branch, _ = project("Revised Plan 14", 10, ben)
    first_id = task(revised, revised_branch, "Revised first seven", 7)
    revision_path = f"/api/projects/{revised}/plan"
    for target in ({}, {"branchId": revised_branch}):
        alice.post(revision_path, {"plannedPoints": 14, "reason": "Approved second field report"} | target)
    second_id = task(revised, revised_branch, "Revised second seven", 7)
    for item in (first_id, second_id):
        sid = alice.post(f"/api/projects/{revised}/tasks/{item}/submissions",
                         {"artifactText": "样本编号 X-7。统计方法为均值。"})["submissions"][0]["id"]
        vote(ben, revised, item, sid, "agree")
    revised_board = board(alice, revised)
    check(revised_board["contribution"]["completionPct"] == 100
          and revised_board["branches"][0]["completionPct"] == 100
          and revised_board["contribution"]["acceptedPoints"] == 14
          and len(revised_board["planChanges"]) == 2, "revised project and branch both reach 14/14 = 100%")
    plan_html = ben.get(f"/api/projects/{revised}/report/export?tasks=1&timeline=1")
    (OUT / "revised-plan-report.html").write_text(plan_html, encoding="utf-8")
    check(plan_html.count("14 / 14 = 100%") >= 2 and "Approved second field report" in plan_html,
          "export shows revised denominators and dated plan reasons")
    ben.post(f"/api/projects/{revised}/leave")
    # Separate fixture with a departed scored author.
    retained, retained_branch, _ = project("Retained Contribution", 7, cara)
    payload = task_data(retained_branch, "Departed contributor's work", 7)
    payload["assigneeId"] = 3
    retained_task = alice.post(f"/api/projects/{retained}/tasks", payload)["id"]
    sid = cara.post(f"/api/projects/{retained}/tasks/{retained_task}/submissions",
                    {"artifactText": "样本编号 C-7。统计方法为中位数。"})["submissions"][0]["id"]
    vote(alice, retained, retained_task, sid, "agree")
    cara.post(f"/api/projects/{retained}/leave")
    check(any(m["id"] == 3 and m["points"] == 7 and m["sharePct"] == 100
              for m in board(alice, retained)["contribution"]["members"]),
          "departed scored author keeps contribution and its denominator")
    simultaneous, concurrent_branch, _ = project("Concurrent Plan", 10, ben)
    replies = race(lambda: reserve(alice, simultaneous, concurrent_branch, "Concurrent A", 7),
                   lambda: reserve(ben, simultaneous, concurrent_branch, "Concurrent B", 7))
    check(sorted(reply[0] for reply in replies) == [200, 409]
          and board(alice, simultaneous)["contribution"]["assignedPoints"] == 7,
          "concurrent task allocations cannot oversubscribe")
    reminder_pid, reminder_branch, _ = project("Reminder Early Response", 1, dan)
    reminder_task = task_data(reminder_branch, "Early response check")
    reminder_task.update({"assigneeId": 4, "checkpointAt": "2026-11-25T08:05:00Z"})
    reminder_tid = alice.post(f"/api/projects/{reminder_pid}/tasks", reminder_task)["id"]
    set_clock("2026-11-25T08:05:00Z")
    check(any(row["id"] == 4 and row["privateReminder"] for row in board(dan, reminder_pid)["memberRows"]),
          "checkpoint first creates a private reminder for the assigned member")
    set_clock("2026-11-26T07:05:00Z")
    check(not board(alice, reminder_pid)["tasks"][0]["followUp"],
          "at hour 23 the reminder remains private")
    dan.post(f"/api/projects/{reminder_pid}/tasks/{reminder_tid}/note",
             {"body": "Field notes in progress", "ack": True})
    set_clock("2026-11-26T08:06:00Z")
    check(not board(alice, reminder_pid)["tasks"][0]["followUp"]
          and not any(row["privateReminder"] for row in board(dan, reminder_pid)["memberRows"]),
          "response during the private window prevents group escalation after 24 hours")

    (OUT / "acceptance-fixes.json").write_text(json.dumps({
        "withdrawalRace": {"statuses": [r[0] for r in terminal_responses], "finalStatus": state},
        "pdfSHA256": [hashlib.sha256(data).hexdigest() for data in (first, second)],
        "revisedPlanProjectId": revised, "versionProjectId": pid, "versionTaskId": tid,
        "revisedPlan": revised_board["planChanges"],
        "revisedContribution": revised_board["contribution"],
    }, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
