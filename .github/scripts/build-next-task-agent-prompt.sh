#!/usr/bin/env bash
# Build the plaintext prompt for the Cursor Cloud Agent (next-task workflow).
# Args: <task-json-path> <pull-request-array-index>
set -euo pipefail
TASK_FILE=${1:?task json path required}
PR_INDEX=${2:?PR array index required}
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
jq -r -n -f "$SCRIPT_DIR/next-task-agent-prompt.jq" --slurpfile t "$TASK_FILE" --argjson idx "$PR_INDEX"
