# Task overview + completed-predecessors section for the next-task agent prompt.
# Usage: jq -r -n -f next-task-overview.jq --slurpfile t TASK.json --argjson idx INDEX
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
  )
