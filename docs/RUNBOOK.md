# Developer guide

## Build and run

Use JDK 21+, Bash and curl. The first build needs Maven Central. The checks also require Python 3.10+ and stable Google Chrome with Playwright. HTTP requests use Java virtual threads. Verify both `java -version` and `javac -version`. Follow [Run locally](../README.md#run-locally), then check `curl -fsS http://127.0.0.1:8765/api/health`. The build downloads Gson 2.11.0 and SQLite JDBC 3.46.1.3 into `server/lib/` and compiles into `build/classes/`.

| Path | Contents |
| --- | --- |
| `dist/` | Handwritten sign-in and workspace pages, styles, JavaScript and labeled image placeholders. |
| `server/src/af/` | HTTP routes, domain rules, SQLite schema and migrations, and demo seeds. |
| `server/test/af/` | Rule tests independent of HTTP and SQLite. |
| `scripts/` | Startup and clock tools, API and browser suites, and independent report verification. |
| `docs/` | Product plan, requirements, design, guides and coverage reports. |

The service binds to 127.0.0.1. `AF_PORT` defaults to 8765. Set `AF_DATA_DIR` for the database directory. `AF_CLOCK_FILE` selects a clock file read on demand. Without a clock file, use `AF_FIXED_CLOCK` or system time. Set `AF_SEED_DEMO=1` or pass `--seed-demo` to seed an empty database. Omit the flag during ordinary use.

## Data and migration

Stop the server before making an offline backup of the database and its WAL files. For online backups use SQLite's backup API. Point the new code at a separate copy first. Verify projects, historical versions and scores before upgrading live data.

On startup, Db.init checks the old submissions schema and rebuilds it transactionally, preserving IDs and states. Recover frozen points for scored tasks from task_scores where possible. Historical all-author rounds retain their original confirmation rule. Create missing tables and reconstruct dispute snapshots from the original agreement, handoffs and dated events. Missing information is labeled and restricts review. Check old versions and exports, restart and check again. The lifecycle suite builds a synthetic legacy database.

## Isolated verification

The regression scripts read and write `data/` relative to their checkout. Use an independent copy with its own database, clock and port. The reset script removes the database, WAL and SHM files **in that checkout**.

```bash
RUN_DIR=$(mktemp -d)
git clone --no-local . "$RUN_DIR/checkout"
git -C "$RUN_DIR/checkout" checkout --detach "$(git rev-parse HEAD)"
cd "$RUN_DIR/checkout"
./scripts/build.sh
./scripts/test_unit.sh
python3 -m venv .venv
. .venv/bin/activate
python -m pip install -r scripts/requirements-browser.txt
AF_PORT=18771 ./scripts/reset-demo.sh
AF_PORT=18771 AF_DATA_DIR="$PWD/data" AF_CLOCK_FILE="$PWD/data/clock.txt" \
  AF_SEED_DEMO=1 ./scripts/start.sh
```

In another terminal, enter the same checkout, activate its virtual environment and run the suites in order:

```bash
. .venv/bin/activate
export AF_BASE_URL=http://127.0.0.1:18771
curl -fsS "$AF_BASE_URL/api/health"
python scripts/verify_e2e.py
python scripts/check_contributions.py
python scripts/browser_walkthrough.py
python scripts/verify_acceptance_fixes.py
python scripts/browser_acceptance_fixes.py
python scripts/verify_lifecycle.py --port 18772
```

The browser walkthrough uses Pair Sprint and Contribution Math 100 data prepared by the API suite. The lifecycle script starts a separate Java server, restarts it and migrates a synthetic legacy database. Find screenshots, results and downloadable reports under `build/verification/` in the temporary checkout. Stop its server when finished.

## Troubleshooting

- **Build failure.** Check the JDK versions and TLS/network access to Maven Central. The build fetches missing Java dependencies.
- **Health check failure.** Check that the port is free and the startup log points to this checkout's data, clock and static files. Inspect request and exception logs.
- **Account or clock failure.** Start with a fresh demo database and the initial test clock at `2026-11-21T10:00:00Z`. API tests use separate cookie jars. Browser tests use separate contexts.
- **Chrome failure.** Install stable Chrome and confirm Python imports the expected Playwright package. Review the real API and browser output and screenshots under `build/verification/`.
- **Evidence retrieval mode.** The default uses deterministic retrieval. To configure a local OpenAI-compatible HTTP service, set `AF_AI_ENDPOINT` and `AF_AI_MODEL`. Supply an optional `AF_AI_API_KEY` only in the server process environment. A failed endpoint is labeled as fallback. `AF_AI_MODE=test-double` selects a labeled test response. See [test coverage](TEST_COVERAGE.md) for the limits of the verified configuration.
