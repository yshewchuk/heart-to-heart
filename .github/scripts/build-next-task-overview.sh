#!/usr/bin/env bash
# Emit the "## Task overview" through completed-predecessors section (next-task workflow).
# Args: <task-json-path> <pull-request-array-index>
set -euo pipefail
TASK_FILE=${1:?task json path required}
PR_INDEX=${2:?PR array index required}
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
jq -r -n -f "$SCRIPT_DIR/next-task-overview.jq" --slurpfile t "$TASK_FILE" --argjson idx "$PR_INDEX"
