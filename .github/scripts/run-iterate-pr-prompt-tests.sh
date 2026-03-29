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
unset MOCK_GH_TEST_ROOT GITHUB_EVENT_NAME

PATH="$SCRIPT_DIR/test/iterate-pr-prompt/bin:$PATH"
export GITHUB_EVENT_NAME=pull_request_review_comment
"$SCRIPT_DIR/build-iterate-pr-agent-prompt.sh" "$TEST_DIR/event.json" >"$TMP"

if ! diff -u "$TEST_DIR/expected-feedback.txt" "$TMP"; then
  echo "iterate-pr-prompt test: output mismatch" >&2
  exit 1
fi

export GITHUB_EVENT_NAME=issue_comment
"$SCRIPT_DIR/build-iterate-pr-agent-prompt.sh" "$TEST_DIR/event-issue-comment.json" >"$TMP"

if ! diff -u "$TEST_DIR/expected-feedback-issue-comment.txt" "$TMP"; then
  echo "iterate-pr-prompt test (issue_comment): output mismatch" >&2
  exit 1
fi

FILTER_DIR="$SCRIPT_DIR/test/iterate-pr-prompt-filter"
export MOCK_GH_TEST_ROOT="$FILTER_DIR"
export GITHUB_EVENT_NAME=pull_request_review_comment
"$SCRIPT_DIR/build-iterate-pr-agent-prompt.sh" "$FILTER_DIR/event.json" >"$TMP"
if ! diff -u "$FILTER_DIR/expected-feedback.txt" "$TMP"; then
  echo "iterate-pr-prompt test (filter resolved): output mismatch" >&2
  exit 1
fi

SKIP_DIR="$SCRIPT_DIR/test/iterate-pr-prompt-skip"
export MOCK_GH_TEST_ROOT="$SKIP_DIR"
export GITHUB_EVENT_NAME=pull_request_review_comment
GITHUB_OUT_TMP=$(mktemp)
export GITHUB_OUTPUT="$GITHUB_OUT_TMP"
"$SCRIPT_DIR/build-iterate-pr-agent-prompt.sh" "$SKIP_DIR/event.json" >/dev/null
if ! grep -q '^SKIP_AGENT=true$' "$GITHUB_OUT_TMP"; then
  echo "iterate-pr-prompt test (skip resolved trigger): expected SKIP_AGENT in GITHUB_OUTPUT" >&2
  rm -f "$GITHUB_OUT_TMP"
  exit 1
fi
rm -f "$GITHUB_OUT_TMP"

MULTI_DIR="$SCRIPT_DIR/test/iterate-pr-prompt-multi-iterate"
export MOCK_GH_TEST_ROOT="$MULTI_DIR"
export GITHUB_EVENT_NAME=pull_request_review_comment

# Canonical triggering comment should produce prompt with both review comments.
unset GITHUB_OUTPUT
"$SCRIPT_DIR/build-iterate-pr-agent-prompt.sh" "$MULTI_DIR/event-canonical.json" >"$TMP"
if ! diff -u "$MULTI_DIR/expected-feedback.txt" "$TMP"; then
  echo "iterate-pr-prompt test (multi /iterate canonical): output mismatch" >&2
  exit 1
fi

# Non-canonical /iterate comment should set SKIP_AGENT=true (only one agent per review).
GITHUB_OUT_TMP=$(mktemp)
export GITHUB_OUTPUT="$GITHUB_OUT_TMP"
"$SCRIPT_DIR/build-iterate-pr-agent-prompt.sh" "$MULTI_DIR/event-noncanonical.json" >/dev/null
if ! grep -q '^SKIP_AGENT=true$' "$GITHUB_OUT_TMP"; then
  echo "iterate-pr-prompt test (multi /iterate non-canonical): expected SKIP_AGENT in GITHUB_OUTPUT" >&2
  rm -f "$GITHUB_OUT_TMP"
  exit 1
fi
rm -f "$GITHUB_OUT_TMP"

unset GITHUB_OUTPUT GITHUB_EVENT_NAME MOCK_GH_TEST_ROOT

echo "iterate-pr-prompt tests: OK"
