# Build the Cursor agent prompt for next-task-agent.
# Usage: jq -n -f next-task-agent-prompt.jq --slurpfile t TASK.json --argjson idx INDEX
($t[0]) as $root
| ($root.pullRequests[$idx]) as $next
| ($root.pullRequests | map(select(.id < $next.id and .status == "completed"))) as $completed_before
| (
    "## Task overview\n\n"
    + ($root.overview | tojson)
    + "\n\n## Pull requests completed before this one\n\n"
    + (if ($completed_before | length) == 0 then
        "(none)\n"
      else
        ($completed_before | map("- PR \(.id): \(.title) (\(.status))") | join("\n")) + "\n"
      end)
    + "\n## Your assignment — implement this pull request\n\n"
    + "**PR \($next.id): \($next.title)**\n\n"
    + ($next.agentPrompt // $next.description // "")
    + "\n\nFollow the repository AGENTS.md and skill workflow. Commit and push your work; open or update the pull request as appropriate."
  )
