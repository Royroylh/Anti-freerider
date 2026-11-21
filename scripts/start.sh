#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT/scripts/build.sh"
mkdir -p "$ROOT/data"
export AF_PORT="${AF_PORT:-8765}"
export AF_CLOCK_FILE="${AF_CLOCK_FILE:-$ROOT/data/clock.txt}"
cd "$ROOT"
echo "[start] http://127.0.0.1:${AF_PORT}/"
exec java -Daf.root="$ROOT" -cp "$ROOT/build/classes:$ROOT/server/lib/*" af.Server "$@"
