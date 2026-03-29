#!/usr/bin/env bash
# Build the plaintext FEEDBACK prompt for the iterate-on-PR Cursor Cloud Agent workflow.
#
# Reads the webhook payload from GITHUB_EVENT_PATH (set by GitHub Actions) so diff hunks
# and comment bodies are never interpolated into the workflow YAML (which would break the
# shell when they contain quotes, $(), etc.).
#
# Supported events:
# - pull_request_review_comment (inline diff comment)
# - issue_comment (PR body / timeline comment; only when issue.pull_request is present)
#
# Environment:
#   GITHUB_EVENT_PATH   Path to the webhook JSON (required unless first arg is provided)
#   GITHUB_REPOSITORY   owner/repo (required)
#   GH_TOKEN            For gh api (required)
#   GITHUB_OUTPUT       If set, appends PR_URL, PR_BRANCH, BASE_BRANCH, multiline FEEDBACK, and
#                       optionally SKIP_AGENT=true when the trigger comment is on a resolved thread
#   GITHUB_ACTOR        Login for "Author:" line (optional; falls back to event comment user)
#   GITHUB_EVENT_NAME   Set by Actions to github.event_name; used to detect pull_request_review_comment
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

PR_NUMBER=$(
  jq -r '
    (
      .pull_request.number
      // (if (.issue.pull_request != null) then .issue.number else empty end)
      // empty
    )
  ' "$EVENT_FILE"
)
if [[ -z "$PR_NUMBER" || "$PR_NUMBER" == null ]]; then
  echo "build-iterate-pr-agent-prompt.sh: event has no pull_request.number or issue.number" >&2
  exit 1
fi

REPO_API="repos/${GITHUB_REPOSITORY}"
GH_OWNER="${GITHUB_REPOSITORY%%/*}"
GH_REPO="${GITHUB_REPOSITORY#*/}"

# Resolved review threads are only exposed via GraphQL; REST review comments have no resolution flag.
GRAPHQL_RESPONSE=$(
  gh api graphql \
    -f owner="$GH_OWNER" \
    -f name="$GH_REPO" \
    -F number="$PR_NUMBER" \
    -f query='
      query($owner: String!, $name: String!, $number: Int!) {
        repository(owner: $owner, name: $name) {
          pullRequest(number: $number) {
            reviewThreads(first: 100) {
              nodes {
                isResolved
                comments(first: 100) {
                  nodes {
                    databaseId
                  }
                }
              }
            }
          }
        }
      }
    '
)
RESOLVED_COMMENT_IDS=$(
  echo "$GRAPHQL_RESPONSE" | jq -c '
    [
      (.data.repository | .pullRequest // empty | .reviewThreads.nodes // [])[]
      | select(.isResolved == true)
      | (.comments.nodes // [])[]
      | .databaseId
      | select(. != null)
    ] | unique
  '
)

PR_JSON=$(gh api "${REPO_API}/pulls/${PR_NUMBER}")
REVIEW_COMMENTS_JSON=$(gh api "${REPO_API}/pulls/${PR_NUMBER}/comments")

# For pull_request_review_comment, treat the whole submitted review as the unit of work:
# - include all comments from the same review in the agent prompt
# - only trigger one agent per review (even if multiple comments contain /iterate)
# - only skip for resolved threads if *all* /iterate comments in that review are resolved
if [[ "${GITHUB_EVENT_NAME:-}" == "pull_request_review_comment" ]]; then
  TRIGGER_COMMENT_ID=$(jq -r '(.comment.id // empty) | if . == null or . == "" then empty else . end' "$EVENT_FILE")
  TRIGGER_REVIEW_ID=$(jq -r '(.comment.pull_request_review_id // empty) | if . == null or . == "" then empty else . end' "$EVENT_FILE")

  if [[ -n "$TRIGGER_REVIEW_ID" ]]; then
    REVIEW_COMMENTS_JSON=$(
      echo "$REVIEW_COMMENTS_JSON" | jq -c --arg rid "$TRIGGER_REVIEW_ID" '
        map(select((.pull_request_review_id // null) != null))
        | map(select((.pull_request_review_id | tostring) == $rid))
      '
    )
  fi

  # Find all /iterate comments within this review (by comment id).
  ITERATE_COMMENT_IDS=$(
    echo "$REVIEW_COMMENTS_JSON" | jq -c '
      [
        .[]
        | select((.body // "") | contains("/iterate"))
        | .id
        | select(. != null)
      ]
    '
  )

  # If a review contains multiple /iterate comments, only trigger on the canonical one
  # (lowest comment id) to avoid starting multiple cloud agents with identical context.
  if [[ -n "$TRIGGER_COMMENT_ID" ]]; then
    CANONICAL_ITERATE_ID=$(
      echo "$ITERATE_COMMENT_IDS" | jq -r '
        if (length == 0) then "" else (map(tostring) | sort | .[0]) end
      '
    )
    if [[ -n "$CANONICAL_ITERATE_ID" && "$TRIGGER_COMMENT_ID" != "$CANONICAL_ITERATE_ID" ]]; then
      echo "Iterate workflow skipped: another /iterate comment in the same review will trigger the agent." >&2
      if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
        echo "SKIP_AGENT=true" >>"$GITHUB_OUTPUT"
      fi
      exit 0
    fi
  fi

  # Skip only if *all* /iterate comments for this review are on resolved threads.
  if echo "$ITERATE_COMMENT_IDS" | jq -e 'length > 0' >/dev/null 2>&1; then
    ALL_ITERATE_RESOLVED=$(
      jq -n \
        --argjson iterate_ids "$ITERATE_COMMENT_IDS" \
        --argjson resolved_ids "$RESOLVED_COMMENT_IDS" \
        '
          ($resolved_ids | map(tostring) | unique) as $resolved |
          ($iterate_ids | map(tostring) | unique) as $iterate |
          ($iterate | all(. as $id | ($resolved | index($id)) != null))
        '
    )
    if [[ "$ALL_ITERATE_RESOLVED" == "true" ]]; then
      echo "Iterate workflow skipped: all /iterate comments in this review are on resolved threads." >&2
      if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
        echo "SKIP_AGENT=true" >>"$GITHUB_OUTPUT"
      fi
      exit 0
    fi
  fi
fi

ACTOR="${GITHUB_ACTOR:-}"
if [[ -z "$ACTOR" ]]; then
  ACTOR=$(
    jq -r '
      (
        .comment.user.login
        // .sender.login
        // "unknown"
      )
    ' "$EVENT_FILE"
  )
fi

FEEDBACK_TMP=$(mktemp)
trap 'rm -f "$FEEDBACK_TMP"' EXIT

jq -r -n -f "$SCRIPT_DIR/iterate-pr-agent-prompt.jq" \
  --slurpfile event "$EVENT_FILE" \
  --argjson pr "$PR_JSON" \
  --argjson review_comments "$REVIEW_COMMENTS_JSON" \
  --argjson resolved_comment_ids "$RESOLVED_COMMENT_IDS" \
  --arg actor "$ACTOR" \
  >"$FEEDBACK_TMP"

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
