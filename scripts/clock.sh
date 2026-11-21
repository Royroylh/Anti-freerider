#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLOCK="${AF_CLOCK_FILE:-$ROOT/data/clock.txt}"
mkdir -p "$(dirname "$CLOCK")"
case "${1:-show}" in
  show)
    if [[ -f "$CLOCK" ]]; then cat "$CLOCK"; else echo "(no clock file; using real time)"; fi
    ;;
  set)
    [[ -n "${2:-}" ]] || { echo "usage: $0 set 2026-11-21T11:00:00Z" >&2; exit 1; }
    printf '%s\n' "$2" > "$CLOCK"
    echo "[clock] $2"
    ;;
  advance)
    [[ -n "${2:-}" ]] || { echo "usage: $0 advance 24h" >&2; exit 1; }
    current="$(cat "$CLOCK" 2>/dev/null || date -u +%Y-%m-%dT%H:%M:%SZ)"
    python3 - "$current" "$2" "$CLOCK" <<'PY'
import sys, datetime, re
current, delta, path = sys.argv[1], sys.argv[2], sys.argv[3]
m = re.fullmatch(r'([+-]?\d+)([smhd])', delta)
if not m:
    raise SystemExit('delta must look like 30m, 24h, 2d')
n, unit = int(m.group(1)), m.group(2)
base = datetime.datetime.fromisoformat(current.replace('Z', '+00:00'))
base += datetime.timedelta(seconds=n * {'s':1,'m':60,'h':3600,'d':86400}[unit])
open(path, 'w').write(base.astimezone(datetime.timezone.utc).isoformat(timespec='seconds').replace('+00:00','Z')+'\n')
print('[clock]', open(path).read().strip())
PY
    ;;
  *)
    echo "usage: $0 show|set <iso>|advance <30m|24h|2d>" >&2
    exit 1
    ;;
esac
