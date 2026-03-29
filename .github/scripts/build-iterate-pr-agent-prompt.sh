#!/usr/bin/env bash
# Build the plaintext FEEDBACK prompt for the iterate-on-PR Cursor Cloud Agent workflow.
#
# Reads the pull_request_review_comment webhook payload from GITHUB_EVENT_PATH (set by
# GitHub Actions) so diff hunks and comment bodies are never interpolated into the
# workflow YAML (which would break the shell when they contain quotes, $(), etc.).
#
# Environment:
#   GITHUB_EVENT_PATH   Path to the webhook JSON (required unless first arg is provided)
#   GITHUB_REPOSITORY   owner/repo (required)
#   GH_TOKEN            For gh api (required)
#   GITHUB_OUTPUT       If set, appends PR_URL, PR_BRANCH, BASE_BRANCH, and multiline FEEDBACK
#   GITHUB_ACTOR        Login for "Author:" line (optional; falls back to event comment user)
#
# Optional first argument: path to event JSON (overrides GITHUB_EVENT_PATH; for tests).
set -euo pipefail

SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

if [[ "${1:-}" ]]; then
  EVENT_FILE=$1
elif [[ "${GITHUB_EVENT_PATH:-}" ]]; then
  EVENT_FILE=$GITHUB_EVENT_PATH
else
  echo "build-iterate-pr-agent-prompt.sh: GITHUB_EVENT_PATH or event JSON path required" >&2
  exit 1
fi

: "${GITHUB_REPOSITORY:?GITHUB_REPOSITORY is required}"
: "${GH_TOKEN:?GH_TOKEN is required}"

PR_NUMBER=$(jq -r '.pull_request.number // empty' "$EVENT_FILE")
if [[ -z "$PR_NUMBER" || "$PR_NUMBER" == null ]]; then
  echo "build-iterate-pr-agent-prompt.sh: event has no pull_request.number" >&2
  exit 1
fi

REPO_API="repos/${GITHUB_REPOSITORY}"
PR_JSON=$(gh api "${REPO_API}/pulls/${PR_NUMBER}")
ISSUE_COMMENTS_JSON=$(gh api "${REPO_API}/issues/${PR_NUMBER}/comments")
REVIEW_COMMENTS_JSON=$(gh api "${REPO_API}/pulls/${PR_NUMBER}/comments")

ACTOR="${GITHUB_ACTOR:-}"
if [[ -z "$ACTOR" ]]; then
  ACTOR=$(jq -r '.comment.user.login // "unknown"' "$EVENT_FILE")
fi

FEEDBACK_TMP=$(mktemp)
trap 'rm -f "$FEEDBACK_TMP"' EXIT

jq -n -r \
  --slurpfile event "$EVENT_FILE" \
  --argjson pr "$PR_JSON" \
  --argjson issue_comments "$ISSUE_COMMENTS_JSON" \
  --argjson review_comments "$REVIEW_COMMENTS_JSON" \
  --arg actor "$ACTOR" \
  '
  ($event[0]) as $event |
  ($issue_comments | map(.body // "") | join("\n\n")) as $issue_text |
  ($review_comments | map(
      (
        "File: " + (.path // "<unknown>") +
        (if (.line != null) then (" (line " + (.line|tostring) + ")") else "" end) +
        (if (.side != null) then (" [" + .side + "]") else "" end) +
        (if (.user.login != null) then (" by " + .user.login) else "" end)
      ) + "\n" +
      (if (.diff_hunk != null and .diff_hunk != "") then (.diff_hunk + "\n") else "" end) +
      (.body // "")
    ) | join("\n\n")
  ) as $review_text |
  ($event.comment.path // "<unknown>") as $path |
  ($event.comment.line // null) as $line |
  ($event.comment.body // "") as $trigger_body |
  ($event.comment.diff_hunk // "") as $trigger_hunk |
  ($pr.base.ref // "") as $base |
  ($pr.head.ref // "") as $head |
  ($pr.title // "") as $title |
  ($pr.body // "") as $body |
  (
    "The user has requested to iterate on this Pull Request based on the feedback.\n\n" +
    "Pull Request Base Branch: \($base)\n" +
    "Pull Request Target Branch: \($head)\n\n" +
    "Pull Request Overview:\n" +
    "Title: \($title)\n\n" +
    $body + "\n\n" +
    "Triggered Review Comment Context (inline in diff hunk):\n" +
    "```diff\n" +
    "--- a/\($path)\n" +
    "+++ b/\($path)\n" +
    $trigger_hunk +
    (if ($trigger_hunk != "" and ($trigger_hunk | endswith("\n") | not)) then "\n" else "" end) +
    "========== Comment on \($path)" +
    (if ($line != null) then (" (line " + ($line|tostring) + ")") else "" end) +
    " ==========\n" +
    "Author: \($actor)\n" +
    $trigger_body + "\n" +
    "========== End comment ==========\n" +
    "```\n\n" +
    "Issue Comments:\n" +
    $issue_text + "\n\n" +
    "Code Review Comments:\n" +
    $review_text + "\n\n" +
    "Please review the feedback provided in the comments and implement the requested changes. " +
    "Since the target PR URL has been provided, make sure to commit and push the changes " +
    "directly to the existing PR branch (\($head))."
  )
  ' >"$FEEDBACK_TMP"

PR_URL=$(echo "$PR_JSON" | jq -r .html_url)
PR_BRANCH=$(echo "$PR_JSON" | jq -r .head.ref)
BASE_BRANCH=$(echo "$PR_JSON" | jq -r .base.ref)

if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
  {
    echo "PR_BRANCH=$PR_BRANCH"
    echo "BASE_BRANCH=$BASE_BRANCH"
    echo "PR_URL=$PR_URL"
    FEEDBACK_DELIM="FEEDBACK_$(date +%s)_$RANDOM"
    echo "FEEDBACK<<$FEEDBACK_DELIM"
    cat "$FEEDBACK_TMP"
    echo ""
    echo "$FEEDBACK_DELIM"
  } >>"$GITHUB_OUTPUT"
else
  cat "$FEEDBACK_TMP"
fi
