#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DB="$ROOT/data/af.db"
if [[ -f "$DB" ]]; then
  if lsof -nP -iTCP:"${AF_PORT:-8765}" -sTCP:LISTEN >/dev/null 2>&1; then
    echo "[reset] a server is listening on ${AF_PORT:-8765}; stop it before resetting the demo." >&2
    exit 1
  fi
fi
rm -f "$DB" "$DB-wal" "$DB-shm"
mkdir -p "$ROOT/data"
printf '2026-11-21T10:00:00Z\n' > "$ROOT/data/clock.txt"
echo "[reset] removed local demo database and set the test clock to 2026-11-21T10:00:00Z"
echo "[reset] start with: AF_SEED_DEMO=1 ./scripts/start.sh"
