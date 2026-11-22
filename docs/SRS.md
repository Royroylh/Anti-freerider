# Software requirements specification

## Users and scope

Students collaborate on course projects. Members can belong to several projects. Project owners decide extension requests, hand off tasks, change future authorship and revise budgets. Students choose what to export for their instructor. The first release runs locally in a browser.

The **assignee** coordinates a task and may also be an author. **Authors** earn the frozen shares of an accepted version. A **review round** covers exactly one version. A **private reminder** goes to the assignee at the time it was issued. A **follow-up** is visible to the group. Members file **disputes** explicitly. See the [product plan](PLAN.md).

| Story | As a team member, I want to… |
| --- | --- |
| US-01 | Create or join a project and see who is responsible on the board. |
| US-02 | Agree on tasks, shares, checkpoints and points, and retrieve the original agreement after a change. |
| US-03 | Access deliverables, confirm my work, vote privately and end an open round when a replacement is necessary. |
| US-04 | Find the support, follow-up or extension record when a task is blocked or late. |
| US-05 | File a factual dispute, hear from the parties, obtain an independent review and choose what to send to the instructor. |
| US-06 | Ask about work and criteria, follow citations and independently reproduce contribution totals. |

## Functional requirements

Sources refer to the sections of the product plan. The release acceptance cases also specify handoffs, attachments, budget handling, withdrawal, matching and chat delivery.

| ID | Story | Source | Required behavior | Verification |
| --- | --- | --- | --- | --- |
| FR-01 | US-01 | Teams | Registration and sign-in establish a session. Sign-out invalidates it. Only current members may access project tasks, attachments, chat, reports and operations. | Use separate sessions, sign out and attempt access as a non-member. |
| FR-02 | US-01 | Teams | Creation supplies an invite code. Joining adds the project to the member’s list. Switching shows that project’s data. Leaving preserves past authorship and credit. | Create, join, switch and leave using four accounts. Inspect historical credit. |
| FR-03 | US-02 | Teams | Store branch, assignee, authors, shares totaling 100%, checkpoint, deadline, output, criteria and points. An allocation that exceeds either budget identifies the shortfall. Reduce task points or let the owner revise planned points with a reason. | Test both budgets, reduced points, an owner revision and concurrent allocation. |
| FR-04 | US-02 | Teams and release cases | Record old and new assignees, date and reason at handoff. Future authorship edits show both allocations, validate the total and record when the change takes effect. Preserve frozen shares. | Hand a Ben/Cara 60/40 task to Dan. Recompute a prior version after editing future shares. |
| FR-05 | US-03 | Review and release cases | Require deliverable text or one valid PDF. Freeze the content, criteria, points, authors, shares and eligibility. A file name, note or bare URL alone is insufficient. Current members may open the version-specific attachment. | Reject empty, invalid and oversized input. Compare downloaded bytes of two same-name versions. |
| FR-06 | US-03 | Review | Each author confirms personally. Submission confirms an author’s own participation. Each eligible non-author votes once. All-author teams require all confirmations plus separate anonymous ballots from everyone. Warn a two-person group’s sole non-author about inference before voting. | Exercise four-person, two-person and all-author cases, duplicate voting and unauthorized confirmation. |
| FR-07 | US-03 | Review | A missing response keeps the public state pending even after dissent. Once all responses arrive, any dissent rejects the version. Unanimity accepts it. Close collection and score a task at most once. | Test early dissent, the last ballot, revision and a repeated final response. |
| FR-08 | US-03 | Review | A draft, attempted resubmission or departure leaves an open round pending. A current author of that version may withdraw with a nonblank reason. Retain the deliverable, reason and date. Close voting without points. The next version collects fresh responses from its own participants. | Test departure, empty reason, non-author, late vote, resubmission and concurrent closure. |
| FR-09 | US-06 | Contributions | Project and branch completion use their respective accepted and planned totals. Sum each accepted task’s points times the member’s frozen share. Divide by total accepted points for a personal percentage. Show historical overages accurately. | Recompute 60/100 and 15/60 from exports. Inspect zero points, missing denominators and historical overages. |
| FR-10 | US-04 | Teams and reminders | The board shows current responsibilities, checkpoints, blockers and requested support. Unresolved support marks a task blocked. Each version retains its own review status. | Open a help request, inspect the board and task, then resolve it. |
| FR-11 | US-04 | Reminders | Privately remind the assignee at an unanswered checkpoint. Start the 24-hour window at that first reminder. A submission, assignee progress note or help request suppresses escalation. Otherwise expose the follow-up. Preserve times and records across refresh and restart. | Delay the first evaluation, then test 23 hours, 24 hours, an intervening request and restart. |
| FR-12 | US-04 | Teams and release cases | Send ordinary or task-linked chat. A second visible session receives each message once and retains its draft, selected task and reading position. Switching projects discards stale responses. | Use two sessions. Verify receipt within ten seconds, task navigation and a delayed response. |
| FR-13 | US-04 | Teams and reminders | Members request an extension with a reason. The owner decides. Retain old and proposed deadlines, request and decision dates and the outcome. | Inspect the task and export after approval or retention of the original date. |
| FR-14 | US-05 | Disputes | Filing freezes participants, shares and dates. Append later handoffs and extensions. Original parties may respond and must recuse themselves from review. Uninvolved members may review. Mark missing historical facts and restrict review if eligibility is uncertain. | File before handoff and extension. Exercise permissions as an original author and an independent member. |
| FR-15 | US-06 | Evidence questions | Resolve a deliverable and version within the current project. Explain missing evidence and request clarification for ambiguous titles. Cite saved text, frozen criteria and public acceptance records. Use program-calculated contribution totals. | Test missing and similar titles, two versions, project isolation and fallback mode. |
| FR-16 | US-05, US-06 | Local trial | Members select material to preview and export. Include the scored version and original values. Distinguish facts, statements and open disputes. Rejection alone never files a dispute. | Preview and download HTML and independently compute results from its JSON appendix. |

### States and denominators

**Blocked** and **Needs follow-up** describe work on a task. **Pending review**, **Accepted**, **Rejected** and **Withdrawn** describe a version. Preserve historical **Superseded** records. A project with a positive budget and zero accepted points shows 0% completion. The same calculation applies to a branch. A legacy branch with zero planned points needs a budget before a percentage is meaningful. With zero accepted team points, members see **No accepted contributions yet**. Once the team has earned points, a member with zero points has a 0% share.

## Nonfunctional requirements

| ID | Stories | Source | Required behavior | Verification |
| --- | --- | --- | --- | --- |
| NFR-01 | US-03, US-06 | Review | Members see public status, authors, signatures and public version dates, plus their own available actions. Protect other members’ ballot participation, choices, counts and timestamps. Exclude ballots and chat from quality evidence. Export public acceptance, points and authorship. | Compare member views before and after closure. Inspect export fields and the evidence context. |
| NFR-02 | US-02, US-03 | Review and release cases | A final ballot racing withdrawal produces one terminal state and at most one score per task. Concurrent task allocations must respect both budgets. | Use separate connections and inspect HTTP results, terminal events, score rows and allocated points. |
| NFR-03 | US-01, US-03 | Local trial | Desktop and mobile layouts support sign-in, project switching, task inspection and review without page-level horizontal overflow. | Exercise desktop Chrome and a 390 px mobile viewport and capture screenshots. |
| NFR-04 | US-01, US-06 | Local trial and evidence questions | Bind to loopback. Preserve SQLite records across restarts. An optional model uses a configured local endpoint. Label fallback and test-double responses. | Check startup, migration from a temporary legacy database, restart, the listening address and fallback responses. |
