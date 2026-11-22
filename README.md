# Anti-freerider

Anti-freerider helps students answer three questions during a group project. Who owns each task? Who needs help? How was each member’s final contribution calculated?

Agree on responsibilities, points and acceptance criteria before work starts. Submit a fixed version for review. Authors confirm their work and eligible members vote anonymously. The closing report links each member’s points to accepted versions and the agreed shares. The notice board tracks checkpoints and requests for help. Members can discuss tasks, request extensions, hand work over, open a dispute or follow a citation to the original evidence. Rejecting a submission does not open a dispute.

[Product plan](docs/PLAN.md) · [Requirements](docs/SRS.md) · [Design](docs/DESIGN.md) · [User guide](docs/USER_MANUAL.md) · [Developer guide](docs/RUNBOOK.md) · [Requirements coverage](docs/REQUIREMENTS_COVERAGE.md) · [Test coverage](docs/TEST_COVERAGE.md)

## Run locally

Install JDK 21 or later, Bash and curl. The first build downloads two libraries from Maven Central. From a fresh checkout, run:

```bash
./scripts/build.sh
AF_SEED_DEMO=1 ./scripts/start.sh
```

Open the [local sign-in page](http://127.0.0.1:8765/). Sign in as `alice@demo.edu` with password `demo-pass-123`. Use separate browser profiles or private sessions for Ben, Cara and Dan (`ben@demo.edu`, `cara@demo.edu`, `dan@demo.edu`), who share the demo password. Seeding populates an empty database. To repeat the demo, follow the [isolated test procedure](docs/RUNBOOK.md#isolated-verification).

## From planning to a closing report

1. Create a project and branches, invite teammates and agree on checkpoints, deadlines, deliverables, author shares, points and criteria. Changing the assignee and editing future authorship are separate actions.
2. Paste the deliverable text or upload a PDF of up to 1 MiB. Submission freezes the deliverable, criteria, points, authors, shares and eligible participants for this version.
3. Authors confirm and other eligible members vote anonymously. In an all-author team everyone also casts a separate ballot. The version stays pending until all required responses arrive. A current member who authored the version can give a reason and withdraw an open round.
4. A task earns points at most once. The report includes the source values. For example, 60 accepted points out of 100 planned gives 60% completion. Alice’s 75% share of a 20-point task gives her 15 points, or 25% of the 60 accepted points.

The Java server binds to loopback, serves the handwritten HTML, CSS and JavaScript in `dist/`, and stores records in `data/af.db` by default. See the test report for desktop and mobile Chrome viewport results and the evidence-retrieval mode exercised.

## Copyright

This project, no matter the idea or the programs, is protected by copyright.
