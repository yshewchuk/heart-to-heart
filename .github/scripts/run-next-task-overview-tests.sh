#!/usr/bin/env bash
# Golden-file tests for build-next-task-overview.sh
set -euo pipefail
ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
BUILD="$ROOT/build-next-task-overview.sh"
TEST_ROOT="$ROOT/test"
FAILED=0
for CASE_DIR in "$TEST_ROOT"/overview-case-*/; do
  [ -d "$CASE_DIR" ] || continue
  NAME=$(basename "$CASE_DIR")
  TASK="$CASE_DIR/task.json"
  EXPECTED="$CASE_DIR/expected-overview.txt"
  META="$CASE_DIR/case.json"
  if [ ! -f "$TASK" ] || [ ! -f "$EXPECTED" ] || [ ! -f "$META" ]; then
    echo "SKIP $NAME: missing task.json, expected-overview.txt, or case.json"
    FAILED=1
    continue
  fi
  PR_INDEX=$(jq -r '.prIndex' "$META")
  ACTUAL=$(mktemp)
  "$BUILD" "$TASK" "$PR_INDEX" > "$ACTUAL"
  if ! diff -u "$EXPECTED" "$ACTUAL"; then
    echo "FAIL $NAME: output differs from expected-overview.txt"
    FAILED=1
  else
    echo "OK   $NAME"
  fi
  rm -f "$ACTUAL"
done
if [ "$FAILED" -ne 0 ]; then
  exit 1
fi
