#!/usr/bin/env python3
"""Focused desktop/mobile checks against a fresh isolated demo server."""
import json
import time

from browser_walkthrough import BASE, OUT, PASS, check, close_task, login, open_task, sync_playwright, wait_text
from verify_acceptance_fixes import pdf
from verify_e2e import Client, board, confirm, vote


def main():
    alice, ben, cara, dan = [Client(f"{member}@demo.edu") for member in ("alice", "ben", "cara", "dan")]
    project = alice.post("/api/projects", {"name": "Browser Acceptance Lab", "plannedPoints": 10})
    pid = project["id"]
    for member in (ben, cara, dan):
        member.post("/api/projects/join", {"inviteCode": project["inviteCode"]})
    branch = board(alice, pid)["branches"][0]["id"]
    tasks = f"/api/projects/{pid}/tasks"
    created = alice.post(tasks, {"title": "Fieldwork evidence", "assigneeId": 2, "branchId": branch,
                                "points": 7, "checkpointAt": "2026-12-03T00:00:00Z",
                                "deadlineAt": "2026-12-04T00:00:00Z", "expectedOutput": "Field report",
                                "acceptanceCriteria": "Report identifies its sample.",
                                "authors": [{"userId": 2, "sharePct": 60}, {"userId": 3, "sharePct": 40}]})
    tid = created["id"]
    report = alice.post(f"/api/projects/{pid}/reports", {"taskId": tid, "reason": "Fieldwork report check"})
    report_id = report["id"]

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="chrome", headless=True)
        desktop = browser.new_context(viewport={"width": 1440, "height": 950}).new_page()
        mobile = browser.new_context(viewport={"width": 390, "height": 844}, is_mobile=True, has_touch=True).new_page()
        desktop.on("dialog", lambda dialog: dialog.accept())
        mobile.on("dialog", lambda dialog: dialog.accept())
        login(desktop, "alice@demo.edu")
        login(mobile, "ben@demo.edu")
        open_task(desktop, pid, tid)
        desktop.locator(".action-panel summary", has_text="Reassign the task").click()
        desktop.select_option("#task-reassign-form select[name=assigneeId]", "4")
        desktop.fill("#task-reassign-form textarea[name=reason]", "Dan coordinates the delivery.")
        desktop.click("#task-reassign-form button[type=submit]")
        wait_text(desktop, ".task-dialog-title", "Dan Zhou")
        check("Ben Wu · 60%" in desktop.locator(".task-steps .author-list").inner_text()
              and "Cara Lin · 40%" in desktop.locator(".task-steps .author-list").inner_text(),
              "desktop handoff preserves both authors and shares")
        desktop.locator(".action-panel summary", has_text="Edit authors and shares for the next version").click()
        desktop.fill('#task-authors-form input[data-author-id="2"]', "55")
        desktop.fill('#task-authors-form input[data-author-id="3"]', "45")
        check("Ben Wu 60%" in desktop.locator("#task-authors-form .muted-note").inner_text()
              and "Ben Wu 55%" in desktop.locator("#task-authors-form .muted-note").inner_text(),
              "desktop author edit shows before and after")
        desktop.fill("#task-authors-form textarea[name=reason]", "Refined author agreement.")
        desktop.get_by_role("button", name="Save authors").click()
        wait_text(desktop, ".task-steps .author-list", "Ben Wu · 55%")
        close_task(desktop)
        desktop.locator("#reports-list button", has_text=f"#{report_id}").click()
        desktop.wait_for_selector("#report-dialog[open]")
        member_report = desktop.locator("#report-dialog-content")
        check("Assignee Ben Wu" in member_report.inner_text() and "Dan coordinates" in member_report.inner_text()
              and "Ben Wu 60%" in member_report.inner_text(), "report dialog shows original agreement and dated handoff")
        desktop.screenshot(path=str(OUT / "acceptance-report-desktop.png"))
        desktop.click('[data-close="report-dialog"]')

        open_task(mobile, pid, tid)
        mobile.locator(".action-panel summary", has_text="Submit a new version").click()
        body = mobile.locator("#task-submit-form textarea[name=content]")
        body.fill("Keep this description after a failed upload")
        input_file = mobile.locator("#task-submit-form input[type=file]")
        input_file.set_input_files({"name": "fieldwork.pdf", "mimeType": "application/pdf", "buffer": b"bad"})
        mobile.get_by_role("button", name="Submit new version").click()
        wait_text(mobile, "#submission-error", "valid PDF")
        check(body.input_value() == "Keep this description after a failed upload", "mobile failed upload retains draft")
        input_file.set_input_files({"name": "fieldwork.pdf", "mimeType": "application/pdf", "buffer": pdf("Browser sample")})
        mobile.get_by_role("button", name="Submit new version").click()
        mobile.locator(".submission-card").wait_for()
        evidence = mobile.locator(".submission-card").first
        check(evidence.get_by_role("link", name="Open PDF · fieldwork.pdf").count() == 1,
              "mobile submitted PDF has a version-specific open link")
        link = evidence.get_by_role("link", name="Open PDF · fieldwork.pdf")
        check(desktop.request.get(BASE + link.get_attribute("href")).body() == pdf("Browser sample"),
              "second browser session receives the version PDF")
        mobile.locator(".action-panel summary", has_text="Submit a new version").click()
        mobile.fill("#task-submit-form textarea[name=artifactText]", "Revision: the field sample includes three sites.")
        mobile.get_by_role("button", name="Save draft").click()
        check(evidence.locator(".status-pill").inner_text() == "Pending review", "saving a mobile draft leaves v1 pending")
        evidence.get_by_role("textbox", name="Withdrawal reason").fill("The field data needs correction.")
        evidence.get_by_role("button", name="Withdraw review round").click()
        wait_text(mobile, ".submission-card", "Withdrawn")
        mobile.screenshot(path=str(OUT / "acceptance-withdrawn-mobile.png"))
        mobile.locator(".action-panel summary", has_text="Submit a new version").click()
        check(mobile.locator("#task-submit-form textarea[name=artifactText]").input_value().startswith("Revision"),
              "withdrawal refresh restores the saved mobile draft")
        mobile.get_by_role("button", name="Submit new version").click()
        mobile.locator(".submission-card").nth(1).wait_for()
        check(mobile.locator(".submission-card .status-pill").first.inner_text() == "Pending review",
              "resubmission creates a new pending round on mobile")

        old_version = mobile.locator(".submission-card").last
        old_id = old_version.get_attribute("id").removeprefix("submission-")
        old_version.get_by_role("button", name="Ask about this version").click()
        mobile.fill("#ai-question", "Which criteria does this report meet?")
        mobile.locator("#ai-form button[type=submit]").click()
        wait_text(mobile, "#ai-answer", "v1")
        check(mobile.locator(f'#ai-answer a[href*="submission={old_id}"]').count() == 3,
              "selected mobile v1 has three links back to v1")
        mobile.locator(f'#ai-answer a[href*="submission={old_id}"]').first.click()
        mobile.locator(f"#submission-{old_id}.highlight").wait_for()
        check(mobile.locator(f"#submission-{old_id} details[open]").count() == 1,
              "clicking citation opens and expands the exact version")
        close_task(mobile)
        mobile.click("#assistant-close")
        mobile.locator("#assistant-panel").wait_for(state="hidden")
        mobile.click("#settings-button")
        mobile.get_by_role("button", name="Leave project").click()
        wait_text(mobile, "#toast", "You have left the project")
        check(not any(p["id"] == pid for p in ben.get("/api/projects")["projects"]),
              "ordinary member can leave from the mobile settings")
        check(alice.get(f"{tasks}/{tid}")["latestSubmission"]["status"] == "pending",
              "member leaving after v2 submission does not end v2")
        ben.post("/api/projects/join", {"inviteCode": project["inviteCode"]})
        mobile.goto(BASE + f"/dashboard.html?project={pid}", wait_until="networkidle")

        desktop.click("#add-task-button")
        desktop.fill("#create-task-form input[name=title]", "Second allocation")
        desktop.fill("#create-task-form input[name=points]", "7")
        desktop.fill("#create-task-form textarea[name=expectedOutput]", "A second field report")
        desktop.fill("#create-task-form textarea[name=acceptanceCriteria]", "Sample details listed.")
        desktop.click("#create-task-form button[type=submit]")
        wait_text(desktop, "#task-create-error", "remaining 3 points, over by 4 points")
        desktop.screenshot(path=str(OUT / "acceptance-budget-desktop.png"))
        for label in ("Revise Project budget", "Revise General budget"):
            revision = desktop.locator("#task-budget details", has_text=label)
            revision.locator("summary").click()
            revision.get_by_role("spinbutton", name="New planned points").fill("14")
            revision.get_by_role("textbox", name="Reason for revision").fill("Allocate approved extra work.")
            revision.get_by_role("button", name="Save plan revision").click()
            desktop.locator("#task-budget details[open]", has_text=label).wait_for(state="hidden")
        desktop.click("#create-task-form button[type=submit]")
        desktop.wait_for_selector("#task-dialog[open]")
        check("Second allocation" in desktop.locator("#task-dialog-content h2").inner_text(),
              "both plan revisions let the original task form save at seven points")
        close_task(desktop)
        version = alice.get(f"{tasks}/{tid}")["submissions"][0]
        confirm(cara, pid, tid, version["id"])
        vote(alice, pid, tid, version["id"], "agree")
        vote(dan, pid, tid, version["id"], "agree")
        second_id = next(item["id"] for item in board(alice, pid)["tasks"] if item["title"] == "Second allocation")
        new_version = alice.post(f"{tasks}/{second_id}/submissions",
                                 {"artifactText": "Sample details: seven observations from the east site."})["submissions"][0]
        for member in (ben, cara, dan):
            vote(member, pid, second_id, new_version["id"], "agree")
        desktop.reload(wait_until="networkidle")
        wait_text(desktop, "#stat-points", "14 / 14")
        check(desktop.locator("#stat-completion").inner_text() == "100%", "desktop displays 14/14 = 100% after both results pass")

        for i in range(12):
            alice.post(f"/api/projects/{pid}/messages", {"body": f"History message {i} for the reading-position check."})
        mobile.reload(wait_until="networkidle")
        mobile.locator("#chat-list .chat-message").nth(11).wait_for()
        mobile.locator("#chat-list").evaluate("node => { node.scrollTop = 60; }")
        scroll = mobile.locator("#chat-list").evaluate("node => node.scrollTop")
        mobile.fill("#chat-body", "an unsent mobile draft")
        mobile.select_option("#chat-task", str(tid))
        mobile.bring_to_front()
        check(mobile.evaluate("document.visibilityState") == "visible", "receiving mobile tab is visible")
        desktop.select_option("#chat-task", str(tid))
        desktop.fill("#chat-body", "Chat polling checks this linked task.")
        start = time.monotonic()
        desktop.locator("#chat-form button[type=submit]").click()
        wait_text(mobile, "#chat-list", "Chat polling checks this linked task.", timeout=10)
        elapsed = time.monotonic() - start
        message = mobile.locator("#chat-list .chat-message", has_text="Chat polling checks this linked task.")
        message.wait_for(state="attached")
        check(message.count() == 1 and elapsed <= 10, "linked chat arrives once within ten seconds", elapsed)
        check(mobile.locator("#chat-body").input_value() == "an unsent mobile draft"
              and mobile.locator("#chat-task").input_value() == str(tid),
              "polling preserves the receiving session's draft and selected linked task")
        check(scroll > 0 and abs(mobile.locator("#chat-list").evaluate("node => node.scrollTop") - scroll) < 2,
              "new chat does not move the receiver's reading position")
        mobile.screenshot(path=str(OUT / "acceptance-chat-mobile.png"))
        message.get_by_role("button", name="Task: Fieldwork evidence").click()
        wait_text(mobile, "#task-dialog-content h2", "Fieldwork evidence")
        close_task(mobile)

        held = []
        old_path = f"**/api/projects/{pid}/messages"
        mobile.evaluate("""oldUrl => {
            const original = window.fetch.bind(window);
            window.fetch = (url, options = {}) => original(url, url === oldUrl
                ? {...options, signal: undefined} : options);
        }""", f"/api/projects/{pid}/messages")
        mobile.route(old_path, lambda route: held.append(route))
        until = time.monotonic() + 8
        while not held and time.monotonic() < until:
            mobile.wait_for_timeout(100)
        check(bool(held), "test captured an old project's in-flight poll")
        mobile.get_by_role("button", name="Contribution Math 100", exact=False).first.click()
        wait_text(mobile, "#project-title", "Contribution Math 100")
        # A mock transport that still fulfills after cancellation must also be ignored.
        held[0].fulfill(status=200, json={"messages": [{"id": 90001, "authorName": "Old fixture",
            "body": "stale response must not render", "createdAt": "2026-11-25T08:00:00Z"}]})
        mobile.wait_for_timeout(350)
        check("stale response must not render" not in mobile.locator("#chat-list").inner_text(),
              "late response never writes into the new project's chat")
        (OUT / "browser-acceptance-results.json").write_text(json.dumps({
            "passed": PASS, "chatDelaySeconds": round(elapsed, 3), "desktopWidth": 1440, "mobileWidth": 390,
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        browser.close()
        print(f"Browser fixes passed {len(PASS)} checks.")


if __name__ == "__main__":
    main()
