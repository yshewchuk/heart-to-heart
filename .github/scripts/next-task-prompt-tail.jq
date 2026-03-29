# Assignment + footer for the next-task agent prompt (follows overview output).
# Usage: jq -r -n -f next-task-prompt-tail.jq --slurpfile t TASK.json --argjson idx INDEX
($t[0]) as $root
| ($root.pullRequests[$idx]) as $next
| (
    "\n## Your assignment — implement this pull request\n\n"
    + "**PR \($next.id): \($next.title)**\n\n"
    + ($next.agentPrompt // $next.description // "")
    + "\n\nFollow the repository AGENTS.md and skill workflow. Commit and push your work; open or update the pull request as appropriate."
    + "\n\n**Task plan bookkeeping (required):** When this PR is finished (merged or implementation is complete on the branch), you **must** update the task plan at `tasks/"
    + $root.id
    + ".json`: for **PR \($next.id)**, set `status` to `\"completed\"` and `started` to `false`. Commit and push that change (to `main` when applicable) so the next-task workflow can select the next pull request."
  )
