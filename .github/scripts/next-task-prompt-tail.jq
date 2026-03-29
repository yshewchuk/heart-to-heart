# Assignment + footer for the next-task agent prompt (follows overview output).
# Usage: jq -r -n -f next-task-prompt-tail.jq --slurpfile t TASK.json --argjson idx INDEX
($t[0].pullRequests[$idx]) as $next
| (
    "\n## Your assignment — implement this pull request\n\n"
    + "**PR \($next.id): \($next.title)**\n\n"
    + ($next.agentPrompt // $next.description // "")
    + "\n\nFollow the repository AGENTS.md and skill workflow. Commit and push your work; open or update the pull request as appropriate."
  )
