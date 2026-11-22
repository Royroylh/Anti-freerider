#!/usr/bin/env python3
"""Browser walkthrough for desktop and mobile, using Chrome via Playwright.

Requires the local server to be running (and preferably the data from verify_e2e.py).
Playwright is installed under tools/py by the local setup; the script uses the system
Chrome channel instead of downloading a browser.
"""
from __future__ import annotations

import json
import os
import sys
import time
import urllib.parse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools" / "py"))
from playwright.sync_api import sync_playwright  # noqa: E402

BASE = os.environ.get("AF_BASE_URL", "http://127.0.0.1:8765")
OUT = ROOT / "build" / "verification"
OUT.mkdir(parents=True, exist_ok=True)
PASS: list[str] = []
ERRORS: list[str] = []


def check(condition: bool, label: str, detail: object = None):
    if not condition:
        raise AssertionError(label + ("" if detail is None else f" :: {detail!r}"))
    PASS.append(label)
    print("  PASS " + label)


def attach_console(page, name: str):
    page.on("console", lambda message: ERRORS.append(f"{name} console {message.type}: {message.text}")
            if message.type == "error" else None)
    page.on("pageerror", lambda error: ERRORS.append(f"{name} pageerror: {error}"))


def login(page, email: str, password: str = "demo-pass-123"):
    page.goto(BASE + "/", wait_until="networkidle")
    if page.locator("#signin-form").count():
        page.fill("#email", email)
        page.fill("#password", password)
        page.click("#signin-form button[type=submit]")
        page.wait_for_url("**/dashboard.html*", timeout=15000)
    page.wait_for_selector("#project-title", timeout=15000)
    page.wait_for_timeout(900)


def open_task(page, project_id: int, task_id: int):
    page.goto(BASE + f"/dashboard.html?project={project_id}&task={task_id}", wait_until="networkidle")
    page.wait_for_selector("#task-dialog[open]", timeout=15000)
    page.wait_for_timeout(500)


def wait_text(page, selector: str, needle: str, timeout: float = 15.0):
    deadline = time.time() + timeout
    last = ""
    while time.time() < deadline:
        try:
            last = page.locator(selector).inner_text(timeout=1000)
        except Exception:
            last = ""
        if needle in last:
            return
        page.wait_for_timeout(100)
    raise AssertionError(f"text {needle!r} did not appear in {selector!r}; last={last!r}")


def project_ids(page):
    return page.evaluate("() => fetch('/api/projects').then(r => r.json())")


def close_task(page):
    page.click('[data-close="task-dialog"]')
    page.wait_for_timeout(300)


def main():
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel="chrome", headless=True)
        alice = browser.new_context(viewport={"width": 1440, "height": 1000})
        alice_page = alice.new_page()
        attach_console(alice_page, "alice")
        alice_page.on("dialog", lambda dialog: dialog.accept())

        print("[1] login through the rendered form")
        login(alice_page, "alice@demo.edu")
        url = alice_page.url
        check("password" not in url.lower() and "demo-pass" not in url.lower(),
              "dashboard URL has no password", url)
        check("alice%40" not in url.lower() and "alice@demo.edu" not in url.lower(),
              "dashboard URL has no email", url)
        alice_page.go_back()
        alice_page.wait_for_timeout(700)
        check("password" not in alice_page.url.lower() and "demo-pass" not in alice_page.url.lower()
              and "alice%40" not in alice_page.url.lower(),
              "browser back never exposes credentials in the URL", alice_page.url)
        alice_page.goto(BASE + "/dashboard.html", wait_until="networkidle")
        alice_page.wait_for_selector("#project-title", timeout=15000)
        alice_page.wait_for_timeout(700)
        alice_page.screenshot(path=str(OUT / "desktop-main.png"), full_page=True)
        check(alice_page.locator(".member-row").count() == 4, "desktop notice board has four member rows")

        print("[2] branch filter and evidence citations")
        alice_page.click("#branch-filter button:has-text('Questionnaire design')")
        alice_page.wait_for_timeout(300)
        check(alice_page.locator("#timeline .timeline-item").count() == 1,
              "branch filter narrows the accepted timeline to the questionnaire branch")
        alice_page.click("#branch-filter button:has-text('All branches')")
        alice_page.click("#assistant-button")
        alice_page.wait_for_selector("#assistant-panel.is-open", timeout=5000)
        check(alice_page.locator("#ai-mode-badge").is_visible(), "assistant side panel opens with a mode badge")
        alice_page.fill("#ai-question", "Who completed the survey analysis")
        alice_page.click("#ai-form button[type=submit]")
        alice_page.wait_for_selector("#ai-answer .citation-link", timeout=15000)
        answer = alice_page.locator("#ai-answer").inner_text()
        check("Questionnaire analysis report" in answer and "Ben Wu" in answer,
              "browser AI answer names the analysis report authors")
        alice_page.locator("#ai-answer .citation-link").first.click()
        alice_page.wait_for_selector("#task-dialog[open]", timeout=15000)
        alice_page.wait_for_timeout(700)
        check(alice_page.locator(".submission-card.highlight").count() == 1,
              "clicking a citation opens the cited task and highlights the cited version")
        check("Questionnaire analysis report" in alice_page.locator("#task-dialog-content h2").inner_text(),
              "citation opened the correct report task")
        alice_page.screenshot(path=str(OUT / "ai-citation.png"), full_page=False)
        close_task(alice_page)

        alice_page.fill("#ai-question", "Why was data collection delayed")
        alice_page.click("#ai-form button[type=submit]")
        alice_page.wait_for_selector("#ai-answer .citation-link", timeout=15000)
        alice_page.locator("#ai-answer .citation-link").first.click()
        alice_page.wait_for_selector("#task-dialog[open]", timeout=15000)
        alice_page.wait_for_selector("#date-change-1.highlight", timeout=15000)
        check("Interview data collection" in alice_page.locator("#task-dialog-content h2").inner_text(),
              "delay citation opens the task that contains the extension record")
        check(alice_page.locator("#date-change-1").count() == 1,
              "delay citation targets the recorded date-change event")
        alice_page.screenshot(path=str(OUT / "delay-citation.png"), full_page=False)
        close_task(alice_page)
        alice_page.keyboard.press("Escape")
        alice_page.wait_for_timeout(350)
        check(alice_page.locator("#assistant-panel").is_hidden(),
              "assistant panel can be dismissed with the keyboard")
        check(alice_page.locator("#stat-completion").get_attribute("aria-label").find("90 of 120") >= 0,
              "completion exposes numerator and denominator to assistive technology")
        check(alice_page.locator(".member-row").first.get_attribute("aria-label") is not None,
              "member rows expose a status label to assistive technology")

        print("[3] project switch, report preview, and download")
        projects = project_ids(alice_page)["projects"]
        main_pid = next(p["id"] for p in projects if p["name"] == "Research Methods 2026")
        math_pid = next(p["id"] for p in projects if p["name"] == "Contribution Math 100")
        alice_page.click(".project-item:has-text('Contribution Math 100')")
        wait_text(alice_page, "#project-title", "Contribution Math 100")
        alice_page.wait_for_timeout(500)
        check(alice_page.locator("#assistant-panel").is_hidden() and alice_page.locator("#ai-answer").is_hidden(),
              "switching projects clears the previous project's assistant answer")
        check("Dataset validation" in alice_page.locator("#chat-list").inner_text(),
              "project switch loads the new project's chat, not the old one")
        check(alice_page.locator("#stat-completion").inner_text() == "60%",
              "math project card shows 60% completion")
        check(alice_page.locator("#stat-my-points").inner_text() == "15",
              "math project shows Alice 15 accepted points")
        check("25%" in alice_page.locator("#stat-my-share").inner_text(),
              "math project shows Alice 25% of accepted work")
        alice_page.click("#preview-report-button")
        alice_page.wait_for_selector("#report-preview:not([hidden])", timeout=15000)
        alice_page.wait_for_timeout(700)
        preview = alice_page.locator("#report-preview").inner_text()
        check("60 / 100 = 60%" in preview and "15 / 60 = 25%" in preview and "15" in preview,
              "browser report preview displays 60 / 100 = 60% and 15 / 60 = 25%")
        check("No accepted contributions yet" in preview, "browser report preview shows the empty branch state")
        alice_page.locator("#report-preview details summary").first.click()
        alice_page.wait_for_timeout(250)
        check("Widget specification" in alice_page.locator("#report-preview").inner_text(),
              "clicking a personal score reveals its accepted versions")
        alice_page.screenshot(path=str(OUT / "math-report-preview.png"), full_page=True)
        with alice_page.expect_download(timeout=20000) as download_info:
            alice_page.click("#download-report-link")
        download = download_info.value
        download_path = OUT / "browser-math-report.html"
        download.save_as(str(download_path))
        check(download_path.exists() and download_path.stat().st_size > 1000,
              "browser download produced a closing-report HTML file")
        downloaded = download_path.read_text(encoding="utf-8")
        check("60%" in downloaded and "25%" in downloaded and "No accepted contributions yet" in downloaded,
              "downloaded report contains the same recomputable numbers")
        alice_page.click(".project-item:has-text('Research Methods 2026')")
        wait_text(alice_page, "#project-title", "Research Methods 2026")
        alice_page.wait_for_timeout(500)

        print("[4] create a project through the web UI and join with three other accounts")
        alice_page.click("#new-project-button")
        alice_page.fill("#create-project-form input[name=name]", "Browser Revision")
        alice_page.fill("#create-project-form textarea[name=description]",
                        "Four-account browser walkthrough of submit, confirm, vote and revise.")
        alice_page.fill("#create-project-form input[name=plannedPoints]", "10")
        alice_page.fill("#create-project-form input[name=branchName]", "Revision")
        alice_page.click("#create-project-form button[type=submit]")
        wait_text(alice_page, "#project-title", "Browser Revision")
        alice_page.wait_for_timeout(700)
        browser_pid = next(p["id"] for p in project_ids(alice_page)["projects"]
                           if p["name"] == "Browser Revision")
        alice_page.click("#settings-button")
        alice_page.wait_for_selector("#settings-dialog[open]")
        invite = alice_page.locator("#settings-dialog .invite-box code").inner_text().strip()
        check(len(invite) == 8, "project settings exposes an 8-character invite code", invite)
        alice_page.click('[data-close="settings-dialog"]')

        member_pages = {}
        for email in ("ben@demo.edu", "cara@demo.edu", "dan@demo.edu"):
            context = browser.new_context(viewport={"width": 1200, "height": 900})
            page = context.new_page()
            attach_console(page, email)
            page.on("dialog", lambda dialog: dialog.accept())
            login(page, email)
            page.click("#join-project-button")
            page.fill("#join-project-form input[name=inviteCode]", invite)
            wait_text(page, "#join-preview", "Browser Revision")
            check("Browser Revision" in page.locator("#join-preview").inner_text(),
                  "join dialog names the group before submitting the code")
            page.click("#join-project-form button[type=submit]")
            wait_text(page, "#project-title", "Browser Revision")
            page.wait_for_timeout(500)
            member_pages[email] = page
        for page in member_pages.values():
            page.goto(BASE + f"/dashboard.html?project={browser_pid}", wait_until="networkidle")
            page.wait_for_timeout(600)
        alice_page.goto(BASE + f"/dashboard.html?project={browser_pid}", wait_until="networkidle")
        alice_page.wait_for_timeout(600)
        check(all(page.locator(".member-row").count() == 4 for page in member_pages.values())
              and alice_page.locator(".member-row").count() == 4,
              "all four member rows appear after three accounts join through the UI")

        print("[5] plan, submit, confirm, vote, reject and revise through the web UI")
        alice_page.click("#add-task-button")
        alice_page.wait_for_selector("#task-create-dialog[open]")
        alice_page.fill("#create-task-form input[name=title]", "Browser revision deliverable")
        alice_page.fill("#create-task-form textarea[name=description]",
                        "A ten-point task used only by the browser walkthrough.")
        alice_page.fill("#create-task-form input[name=points]", "10")
        alice_page.fill("#create-task-form input[name=checkpointAt]", "2026-11-28T10:00")
        alice_page.fill("#create-task-form input[name=deadlineAt]", "2026-11-30T10:00")
        alice_page.fill("#create-task-form textarea[name=expectedOutput]",
                        "A revised deliverable with a dated revision note.")
        alice_page.fill("#create-task-form textarea[name=acceptanceCriteria]",
                        "The deliverable explains the revision.\nThe criterion is quoted in the version.")
        # Alice is the default sole author. Add Ben as a co-author and set 60/40.
        alice_page.check('#author-rows input[data-user="2"]')
        alice_page.fill('#author-rows input[data-share="1"]', "60")
        alice_page.fill('#author-rows input[data-share="2"]', "40")
        check("100%" in alice_page.locator("#share-total").inner_text(),
              "task author shares total 100% in the planning form")
        alice_page.click("#create-task-form button[type=submit]")
        alice_page.wait_for_selector("#task-dialog[open]", timeout=15000)
        alice_page.wait_for_timeout(800)
        task_url = urllib.parse.urlparse(alice_page.url)
        browser_tid = int(urllib.parse.parse_qs(task_url.query)["task"][0])
        check("Browser revision deliverable" in alice_page.locator("#task-dialog-content h2").inner_text(),
              "the newly planned task opens in the task dialog")

        def submit_version(page, summary: str):
            page.locator(".action-panel summary", has_text="Submit a new version").click()
            page.wait_for_timeout(200)
            page.fill("#task-submit-form textarea[name=content]", summary)
            page.fill("#task-submit-form input[name=artifactName]", "browser-revision.md")
            page.fill("#task-submit-form textarea[name=artifactText]",
                      "Revision note: the deliverable was updated after review. Criterion quoted here.")
            page.click("#task-submit-form button[type=submit]")
            page.wait_for_timeout(1200)

        submit_version(alice_page, "Version one was submitted for browser review.")
        alice_page.wait_for_selector(".submission-card", timeout=15000)
        check(alice_page.locator(".submission-card .status-pill").first.inner_text() == "Pending review",
              "submitted browser version is Pending review")
        check(alice_page.locator(".detail-step").count() == 3,
              "task detail follows agreement → submission → criteria")
        check(alice_page.locator(".criteria-list li").count() >= 2,
              "task detail lists the frozen acceptance criteria line by line")
        check(alice_page.locator("[data-slot=submission-preview]").count() == 1,
              "task detail exposes the submission-preview image slot")
        check(alice_page.locator("img").count() == 0,
              "missing images do not produce broken img elements")
        alice_page.screenshot(path=str(OUT / "browser-submission.png"), full_page=False)

        ben_page = member_pages["ben@demo.edu"]
        open_task(ben_page, browser_pid, browser_tid)
        ben_page.get_by_text("I confirm this submission as a co-author").click()
        ben_page.wait_for_timeout(900)
        check(ben_page.get_by_text("I confirm this submission as a co-author").count() == 0,
              "co-author confirmation is recorded through the browser")

        cara_page = member_pages["cara@demo.edu"]
        open_task(cara_page, browser_pid, browser_tid)
        cara_page.get_by_role("button", name="Disagree anonymously", exact=True).click()
        cara_page.wait_for_timeout(900)
        check(cara_page.locator(".submission-card .status-pill").first.inner_text() == "Pending review",
              "one disagreement keeps the browser UI on Pending review")

        dan_page = member_pages["dan@demo.edu"]
        open_task(dan_page, browser_pid, browser_tid)
        dan_page.get_by_role("button", name="Agree anonymously", exact=True).click()
        dan_page.wait_for_timeout(1200)
        check(dan_page.locator(".submission-card .status-pill").first.inner_text() == "Rejected",
              "completed non-unanimous collection reveals Rejected")

        open_task(alice_page, browser_pid, browser_tid)
        submit_version(alice_page, "Version two answers the review and quotes the criterion.")
        alice_page.wait_for_timeout(800)
        check(alice_page.locator(".submission-card .status-pill").first.inner_text() == "Pending review",
              "revised browser version starts its own Pending review round")
        ben_page.goto(BASE + f"/dashboard.html?project={browser_pid}&task={browser_tid}",
                      wait_until="networkidle")
        ben_page.wait_for_selector("#task-dialog[open]")
        ben_page.get_by_text("I confirm this submission as a co-author").click()
        ben_page.wait_for_timeout(800)
        cara_page.goto(BASE + f"/dashboard.html?project={browser_pid}&task={browser_tid}",
                       wait_until="networkidle")
        cara_page.wait_for_selector("#task-dialog[open]")
        cara_page.get_by_role("button", name="Agree anonymously", exact=True).click()
        cara_page.wait_for_timeout(800)
        dan_page.goto(BASE + f"/dashboard.html?project={browser_pid}&task={browser_tid}",
                      wait_until="networkidle")
        dan_page.wait_for_selector("#task-dialog[open]")
        dan_page.get_by_role("button", name="Agree anonymously", exact=True).click()
        dan_page.wait_for_timeout(1200)
        statuses = dan_page.locator(".submission-card .status-pill")
        check(statuses.count() >= 2, "both browser versions remain in the task")
        check(statuses.first.inner_text() == "Accepted", "revised browser version is accepted")
        dan_page.screenshot(path=str(OUT / "browser-revision-accepted.png"), full_page=False)
        close_task(dan_page)
        alice_page.goto(BASE + f"/dashboard.html?project={browser_pid}", wait_until="networkidle")
        alice_page.wait_for_timeout(900)
        check(alice_page.locator("#stat-completion").inner_text() == "100%",
              "browser project reaches 100% after the accepted revision")

        print("[6] two-person inference warning rendered before voting")
        pair_id = next(p["id"] for p in project_ids(alice_page)["projects"] if p["name"] == "Pair Sprint")
        created = alice_page.evaluate(
            """async ({pairId}) => {
                const board = await (await fetch(`/api/projects/${pairId}/board`)).json();
                const branchId = board.branches[0].id;
                for (const plan of [{plannedPoints:62}, {plannedPoints:62, branchId}]) {
                    const updated = await fetch(`/api/projects/${pairId}/plan`, {
                        method: 'POST', headers: {'Content-Type':'application/json'},
                        body: JSON.stringify({...plan, reason:'Budget for two browser test fixtures'})
                    });
                    if (!updated.ok) throw new Error(await updated.text());
                }
                const task = await (await fetch(`/api/projects/${pairId}/tasks`, {
                    method: 'POST', headers: {'Content-Type':'application/json'},
                    body: JSON.stringify({
                        branchId, title: 'Warning render check / Two-person team notice',
                        description: 'Pending single-author task used to render the inference warning.',
                        assigneeId: 1, points: 1,
                        checkpointAt: '2026-11-28T10:00:00Z', deadlineAt: '2026-11-30T10:00:00Z',
                        expectedOutput: 'Joint note', acceptanceCriteria: 'Both agree.',
                        authors: [{userId:2, sharePct:100}]
                    })
                })).json();
                const submission = await (await fetch(`/api/projects/${pairId}/tasks/${task.id}/submissions`, {
                    method: 'POST', headers: {'Content-Type':'application/json'},
                    body: JSON.stringify({content: 'Pending warning artifact', artifactName: 'warning.md',
                                          artifactText: 'Both members vote anonymously.'})
                })).json();
                return {taskId: task.id, submissionId: submission.submissions[0].id};
            }""", {"pairId": pair_id})
        open_task(alice_page, pair_id, created["taskId"])
        alice_page.wait_for_selector(".banner.warning", timeout=15000)
        warning = alice_page.locator(".banner.warning").first.inner_text()
        check("two-person" in warning.lower() and "infer" in warning.lower(),
              "two-person warning is visible in the browser before voting")
        alice_page.locator(".banner.warning").first.scroll_into_view_if_needed()
        alice_page.wait_for_timeout(300)
        alice_page.screenshot(path=str(OUT / "two-person-warning.png"), full_page=False)
        close_task(alice_page)

        # A second active task makes the member row show "Plus N items" with an expandable list.
        alice_page.evaluate(
            """async ({pairId}) => {
                const board = await (await fetch(`/api/projects/${pairId}/board`)).json();
                return await (await fetch(`/api/projects/${pairId}/tasks`, {
                    method: 'POST', headers: {'Content-Type':'application/json'},
                    body: JSON.stringify({
                        branchId: board.branches[0].id, title: 'Second active task / Second assigned task',
                        description: 'Makes the member row expandable.', assigneeId: 1, points: 1,
                        checkpointAt: '2026-11-29T10:00:00Z', deadlineAt: '2026-12-01T10:00:00Z',
                        expectedOutput: 'Note', acceptanceCriteria: 'Dated note.',
                        authors: [{userId:1, sharePct:100}]
                    })
                })).json();
            }""", {"pairId": pair_id})
        alice_page.goto(BASE + f"/dashboard.html?project={pair_id}", wait_until="networkidle")
        alice_page.wait_for_timeout(700)
        alice_row = alice_page.locator(".member-row", has_text="Alice Chen")
        check(alice_row.locator(".member-more").count() == 1,
              "a member with several active tasks gets an expandable Plus N items row")
        check("Plus" in alice_row.locator(".member-more summary").inner_text(),
              "the multi-task row labels the additional count")
        alice_page.screenshot(path=str(OUT / "multi-task-member.png"), full_page=False)

        print("[7] invite link remembered through sign-in")
        invite_context = browser.new_context(viewport={"width": 1100, "height": 900})
        invite_page = invite_context.new_page()
        attach_console(invite_page, "newcomer")
        invite_page.goto(BASE + "/?invite=" + urllib.parse.quote(invite), wait_until="networkidle")
        invite_page.fill("#email", "newcomer@demo.edu")
        invite_page.fill("#password", "new-pass-123")
        invite_page.click("#signin-form button[type=submit]")
        invite_page.wait_for_url("**/dashboard.html*invite=*", timeout=15000)
        invite_page.wait_for_selector("#join-dialog[open]", timeout=15000)
        value = invite_page.locator("#join-project-form input[name=inviteCode]").input_value()
        check(value == invite, "invite link is retained and prefilled after login", value)
        wait_text(invite_page, "#join-preview", "Browser Revision")
        check("Browser Revision" in invite_page.locator("#join-preview").inner_text(),
              "invite link names the group before joining")
        check(invite_page.locator("#empty-state").is_visible(),
              "a member with no projects sees the first-team empty state")
        check(invite_page.locator("[data-slot=team-setup-photo]").count() == 1,
              "empty state exposes the team-setup-photo image slot")
        check(invite_page.locator("img").count() == 0,
              "empty state has no broken image when material is missing")
        invite_page.screenshot(path=str(OUT / "invite-link.png"), full_page=False)

        print("[8] mobile layout")
        alice_page.set_viewport_size({"width": 390, "height": 844})
        alice_page.goto(BASE + f"/dashboard.html?project={main_pid}", wait_until="networkidle")
        alice_page.wait_for_timeout(900)
        overflow = alice_page.evaluate(
            "() => document.documentElement.scrollWidth > window.innerWidth + 1")
        check(overflow is False, "mobile main page has no horizontal overflow")
        alice_page.screenshot(path=str(OUT / "mobile-main.png"), full_page=True)
        alice_page.click("#assistant-button")
        alice_page.wait_for_selector("#assistant-panel.is-open", timeout=5000)
        alice_page.wait_for_timeout(350)
        panel_box = alice_page.locator("#assistant-panel").bounding_box()
        check(panel_box["width"] >= 380 and panel_box["x"] <= 1,
              "mobile assistant opens as a full-width independent view", panel_box)
        alice_page.screenshot(path=str(OUT / "mobile-assistant.png"), full_page=False)
        alice_page.keyboard.press("Escape")
        alice_page.wait_for_timeout(300)
        alice_page.goto(BASE + f"/dashboard.html?project={browser_pid}&task={browser_tid}",
                        wait_until="networkidle")
        alice_page.wait_for_selector("#task-dialog[open]", timeout=15000)
        alice_page.wait_for_timeout(600)
        overflow = alice_page.evaluate(
            "() => document.documentElement.scrollWidth > window.innerWidth + 1")
        check(overflow is False, "mobile task dialog has no horizontal overflow")
        alice_page.screenshot(path=str(OUT / "mobile-task.png"), full_page=False)

        check(not ERRORS, "no browser console or page errors", ERRORS)
        (OUT / "browser-results.json").write_text(json.dumps({
            "passed": PASS, "errors": ERRORS,
            "screenshots": sorted(str(path.name) for path in OUT.glob("*.png")),
            "downloadedReport": str(OUT / "browser-math-report.html")
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        browser.close()
        print(f"\nBrowser walkthrough passed {len(PASS)} checks. Screenshots in {OUT}")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        (OUT / "browser-results.json").write_text(json.dumps({
            "passed": PASS, "errors": ERRORS + [str(error)]
        }, ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"\nFAILED: {error}", file=sys.stderr)
        raise
