#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LIB="$ROOT/server/lib"
mkdir -p "$LIB" "$ROOT/build/classes"

download() {
  local url="$1" dest="$2"
  if [[ ! -s "$dest" ]]; then
    echo "[build] downloading $(basename "$dest")"
    curl -fsSL --retry 3 -o "$dest" "$url"
  fi
}

download "https://repo1.maven.org/maven2/org/xerial/sqlite-jdbc/3.46.1.3/sqlite-jdbc-3.46.1.3.jar" \
  "$LIB/sqlite-jdbc-3.46.1.3.jar"
download "https://repo1.maven.org/maven2/com/google/code/gson/gson/2.11.0/gson-2.11.0.jar" \
  "$LIB/gson-2.11.0.jar"

rm -rf "$ROOT/build/classes"
mkdir -p "$ROOT/build/classes"
find "$ROOT/server/src" -name '*.java' -print0 | xargs -0 javac -encoding UTF-8 \
  -cp "$LIB/gson-2.11.0.jar:$LIB/sqlite-jdbc-3.46.1.3.jar" -d "$ROOT/build/classes"
echo "[build] compiled to build/classes"
