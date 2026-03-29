#!/usr/bin/env bash
# Build the plaintext prompt for the Cursor Cloud Agent (next-task workflow).
# Args: <task-json-path> <pull-request-array-index>
set -euo pipefail
TASK_FILE=${1:?task json path required}
PR_INDEX=${2:?PR array index required}
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
# Avoid $(...) for jq output: bash strips trailing newlines from command substitution.
tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
"$SCRIPT_DIR/build-next-task-overview.sh" "$TASK_FILE" "$PR_INDEX" >> "$tmp"
"$SCRIPT_DIR/build-next-task-prompt-tail.sh" "$TASK_FILE" "$PR_INDEX" >> "$tmp"
cat "$tmp"
