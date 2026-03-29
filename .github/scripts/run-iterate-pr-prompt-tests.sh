#!/usr/bin/env bash
# Golden-file test for build-iterate-pr-agent-prompt.sh (no network; mocks gh).
set -euo pipefail
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
TEST_DIR="$SCRIPT_DIR/test/iterate-pr-prompt"
TMP=$(mktemp)
trap 'rm -f "$TMP"' EXIT

export GH_TOKEN=dummy
export GITHUB_REPOSITORY=example/heart-to-heart
export GITHUB_ACTOR=test-actor
unset GITHUB_OUTPUT

PATH="$SCRIPT_DIR/test/iterate-pr-prompt/bin:$PATH"
"$SCRIPT_DIR/build-iterate-pr-agent-prompt.sh" "$TEST_DIR/event.json" >"$TMP"

if ! diff -u "$TEST_DIR/expected-feedback.txt" "$TMP"; then
  echo "iterate-pr-prompt test: output mismatch" >&2
  exit 1
fi

"$SCRIPT_DIR/build-iterate-pr-agent-prompt.sh" "$TEST_DIR/event-issue-comment.json" >"$TMP"

if ! diff -u "$TEST_DIR/expected-feedback-issue-comment.txt" "$TMP"; then
  echo "iterate-pr-prompt test (issue_comment): output mismatch" >&2
  exit 1
fi
echo "iterate-pr-prompt tests: OK"
