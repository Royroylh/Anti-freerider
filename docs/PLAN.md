# Product plan for the first release

Build a locally runnable website for desktop and mobile screens. Use a fictional course team to try a full assignment. Throughout the assignment each member should be able to identify the person responsible for a task, work that needs follow-up, and the calculation behind each person's final contribution. A standalone app can follow the web trial.

## Teams, communication and progress

Students can create a project, invite teammates by code or link and participate in several projects. The dark workspace has a project selector on the left. Organize each project into branches and tasks, such as survey design, data collection and analysis. Before work starts, agree on an assignee, co-authors, shares, checkpoint, deadline, expected output, criteria and point value.

The notice board has a row for each member. Show the current task, next checkpoint and any help needed. Below it, group accepted work by date and allow filtering by branch. Keep queues for tasks to do, work awaiting review, blockers and team requests. Members can link chat messages to tasks. Coordination, interviews and review work can earn credit when recorded as named, reviewable deliverables.

## Anonymous review and contributions

Each round covers a fixed version. Freeze the deliverable, criteria, task points, authors, shares totaling 100% and eligible participants at submission. New members join subsequent rounds. Each author confirms the version individually, and each eligible non-author casts one anonymous agree or disagree ballot. When everyone is an author, everyone also casts an anonymous ballot.

Until all confirmations and ballots arrive, members see **Pending review**, even after an early disagreement. Unanimity accepts the version. Any disagreement rejects it once collection finishes. Retain rejected versions without awarding points. A revision starts a fresh round. Each task earns points at most once. Rejection alone never files a dispute against a person.

A current member who authored a pending version may give a reason and withdraw the whole round. Leaving the project or saving a draft keeps the round open. Record the withdrawn deliverable, reason and date on the timeline. Close collection, award no points and keep ballots private. A new version determines eligibility again and collects fresh responses. If withdrawal and the final response arrive together, only one can close the round.

The UI, member APIs, notifications, evidence assistant and exports must protect individual choices, participation, counts and ballot times. Public records may show authorship, submission time and final acceptance time. In a two-person group with one non-author the submitter may infer the other member’s choice. Warn the voter before they vote.

Project completion is accepted task points divided by planned project points. Use the branch’s own totals for branch completion. Each person earns accepted task points multiplied by their frozen share, summed across tasks. Divide personal points by all accepted points to obtain their contribution percentage. With 100 planned and 60 accepted, a member with 15 points earns 25% of accepted contributions. With 100 planned and none accepted, completion is 0% and individual shares have no denominator. With positive accepted points a member who earned zero has a 0% share. Keep original values and accepted versions in exports, sum before rounding, and display **No accepted contributions yet** where appropriate.

## Reminders, disputes and evidence questions

At an unanswered checkpoint, privately remind the assignee first. Start the 24-hour window at the first private reminder. A submission, progress note or help request in that window suppresses escalation. Otherwise mark the task **Needs follow-up** after 24 hours. Refreshes and restarts preserve the time and avoid duplicate reminders. Show unresolved dependencies and the support requested. For submitted work, direct action to eligible reviewers.

A member can file a dispute from a task, attaching the original agreement, dates, reminders and deliverables. Involved parties can respond and uninvolved members can review. Students can preview, select and export material for an instructor. Separate recorded facts, member statements and unresolved disagreements.

Members can ask who completed the survey analysis, which criteria a selected report addresses, or why data collection was delayed. Link answers to the relevant version or event. Use the submission, agreed criteria and acceptance record for quality questions. If the sample method is missing, say what is missing. Keep group chat outside quality assessment. The program calculates contributions.

## Local acceptance trial

Use four fictional members and two branches. Track task coordination separately from a version’s review status. For example, a revision can be blocked on interview data while the earlier version remains rejected.

| Scenario | Expected observation |
| --- | --- |
| Check an unanswered reminder at 23 and 24 hours. | At 23 hours only the assignee sees it. At 24 hours the group sees follow-up if there was no response. An outstanding help request shows a blocker and the support needed. |
| Someone disagrees on v1 while another member has yet to vote. | The round stays pending. Rejection appears only after all responses arrive. |
| A member leaves during collection. An author gives a reason, withdraws and submits again. | The withdrawn version and reason remain accessible. The new round starts with fresh eligibility and no previous votes. |
| v1 is rejected, v2 is accepted, and the final response is sent twice. | Both versions retain their outcomes. Only v2 contributes points, exactly once. |

The closing report should let students trace any percentage back to its task and version, and any extension back to the reason and decision recorded at the time.
