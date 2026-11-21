#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"$ROOT/scripts/build.sh"
mkdir -p "$ROOT/build/test-classes"
CLASS_PATH="$ROOT/build/classes:$ROOT/server/lib/*"
javac -encoding UTF-8 -cp "$CLASS_PATH" -d "$ROOT/build/test-classes" "$ROOT/server/test/af/RulesTest.java"
java -cp "$ROOT/build/test-classes:$CLASS_PATH" af.RulesTest
