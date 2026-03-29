#!/usr/bin/env bash
# Emit assignment + footer for the next-task agent prompt (concat after overview).
# Args: <task-json-path> <pull-request-array-index>
set -euo pipefail
TASK_FILE=${1:?task json path required}
PR_INDEX=${2:?PR array index required}
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
jq -r -n -f "$SCRIPT_DIR/next-task-prompt-tail.jq" --slurpfile t "$TASK_FILE" --argjson idx "$PR_INDEX"
