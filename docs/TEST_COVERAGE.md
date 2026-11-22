# Test coverage

The English release was tested on November 22, 2026 (Hong Kong time) from a separate checkout of the published project. The environment was macOS 15.3.1 arm64, OpenJDK 25, Python 3.12.9, Playwright 1.65.2 and Google Chrome 158.0.8127.36. The main suite used loopback port 26731, a fresh fictional team database and a controlled clock. Lifecycle tests used a separate server on port 26732. Model configuration was unset and the application reported evidence retrieval mode. The Java build used locally cached copies of its pinned dependencies.

## Commands, results and evidence

See the [developer guide](RUNBOOK.md#isolated-verification) for a reproducible sequence. Replace its example ports with the ports above to match this run.

| Layer | Command or operation | Observed result | Reproducible evidence |
| --- | --- | --- | --- |
| Build | `./scripts/build.sh` | Java compiled successfully. | Build output |
| Unit | `./scripts/test_unit.sh` | Five named cases passed, covering allocations, denominators, incomplete and completed rounds and the reminder boundary. | U-01–U-05 in the terminal |
| Local startup | Reset the isolated database and start with demo seeding, an explicit port, data directory and clock file. | The server seeded fictional members and returned the controlled time. | Startup log and GET /api/health |
| API end-to-end | `python scripts/verify_e2e.py` | 213 checks passed. | build/verification/results.json, main-report.html and handoff-report.html |
| Independent recomputation | `python scripts/check_contributions.py` | Project 60%, Alice 15 points and 25%, Dan 0 points and 0%. Export fields and denominators passed. | recomputed-report.html and terminal arithmetic |
| Browser end-to-end | `python scripts/browser_walkthrough.py` | 55 checks passed, with zero browser console or page errors. | browser-results.json and desktop/mobile screenshots |
| Focused API | `python scripts/verify_acceptance_fixes.py` | 46 checks passed. | acceptance-fixes.json, two PDFs, withdrawal and revised-plan exports |
| Focused browser | `python scripts/browser_acceptance_fixes.py` | 21 checks passed. | browser-acceptance-results.json, withdrawal and chat screenshots |
| SQLite lifecycle | `python scripts/verify_lifecycle.py --port 26732` | Eleven named cases passed, including restart and synthetic legacy migration. | lifecycle-results.json and server logs |

The API and browser counts refer to individual checks and may include multiple assertions per scenario. The independent calculation is a separate procedure. The [requirements matrix](REQUIREMENTS_COVERAGE.md) maps the 20 requirements to implementation and evidence. Line and branch coverage were not measured. The temporary checkout holds generated databases, logs, screenshots and exports under build/verification or its test data directory.

## Seven release regressions

| Requirement | Scenario | Observed result |
| --- | --- | --- |
| FR-14 — dispute snapshot | File a dispute involving Ben and Cara, hand coordination to Dan and extend the deadline. | Original parties could respond but received 403 on review. Dan could review. Export retained the original deadline, 60/40 split and subsequent changes. |
| FR-04 — handoff | Transfer coordination, then edit future authorship independently. | The new assignee could not confirm for another author. Ben and Cara retained 6 and 4 points. The editor showed the old and proposed shares. |
| FR-05 — attachments | Try empty input, a filename, short status, URL, invalid or oversized PDF. Upload two same-name versions and download them in another session. | Invalid input was rejected. Each downloaded version matched its original bytes and SHA-256. Outsiders received 403. Failed mobile upload retained the form. |
| FR-06–08, NFR-02 — withdrawal | Exercise blank reasons, ineligible members, departure, drafts, late votes, a new round and a race against the final ballot. | Explicit withdrawal preserved the old PDF, reason and date. The next round collected fresh responses. In the race one request succeeded and the other returned 409; one terminal event was recorded. Repeating the final vote earned no extra points. |
| FR-15, NFR-01 — evidence | Ask about a missing lunar soil report, ambiguous names and two selected versions. Plant a unique marker in task chat. | Missing or ambiguous titles returned no citations. Selected citations opened the correct version. Chat and individual ballots stayed outside quality evidence. |
| FR-03, FR-09, NFR-02 — budgets | Allocate 7 of 10 project and branch points. Try another 7, then 3. In a separate scenario revise the budget to 14. | Both limits reported 3 remaining and a shortfall of 4. Concurrent 7-point requests could only allocate once. After revision, the page and export showed 14/14 = 100%. |
| FR-12 — chat | Send a task-linked message from desktop while another visible session has a draft and a reading position. Delay an old project response during switching. | The receiver saw one message within ten seconds without refreshing. The link, draft, selection and reading position were preserved; the old response did not appear in the new project. |

## Additional observations

- **Scoring.** The independent export calculation found 60/100 accepted points. Alice earned 20 × 75% = 15 points and no other awards, giving 15/60 = 25%. Dan had 0/60 = 0%. With no accepted work, a positive project budget gives 0% completion and the personal share displays **No accepted contributions yet**.
- **Privacy.** Four members saw the same public version state and events after early dissent. Personal action eligibility was session-specific. v1 remained pending until all responses arrived, then closed rejected. The accepted revision scored once. The checks inspected the board, task events, evidence answers and exports.
- **Reminders.** Initial evaluation happened after the checkpoint. Restart at hour 23 preserved the private reminder. At hour 24 only the silent task escalated. A task with an intervening help request remained blocked. Refresh preserved the timestamps; after handoff, the private event was visible only to its original recipient.
- **Migration.** The synthetic database recorded a filing and subsequent handoff at the same timestamp. Event order recovered Ben/Cara’s filing-time 60/40 split. Historical superseded versions retained their state and artifacts. SQLite integrity and foreign-key checks passed. The recovered snapshot and review permissions survived restart.

## Verification limits

The run used saved submission text and local retrieval. A real model was not connected. PDF fidelity checks used synthetic files. Browser checks used desktop Chrome and an emulated mobile viewport. Physical phones, Safari, Firefox, production deployment, sustained load and recovery from arbitrary database corruption remain unverified.
