# Plaintext FEEDBACK prompt for the iterate-on-PR Cursor Cloud Agent workflow.
# Usage:
#   jq -r -n -f iterate-pr-agent-prompt.jq \
#     --slurpfile event EVENT.json \
#     --argjson pr PR.json \
#     --argjson review_comments REVIEW_COMMENTS.json \
#     --arg actor LOGIN

($event[0]) as $event |
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
  "Code Review Comments:\n" +
  $review_text + "\n\n" +
  "Please review the feedback provided in the comments and implement the requested changes. " +
  "Since the target PR URL has been provided, make sure to commit and push the changes " +
  "directly to the existing PR branch (\($head))."
)
