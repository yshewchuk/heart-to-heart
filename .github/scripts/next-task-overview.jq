# Task overview + completed-predecessors section for the next-task agent prompt.
# Renders overview object as markdown (not raw JSON).
# Usage: jq -r -n -f next-task-overview.jq --slurpfile t TASK.json --argjson idx INDEX

def section($title; $body):
  if ($body == null) or ($body | type) != "string" or ($body | length) == 0 then
    ""
  else
    "### " + $title + "\n\n" + $body + "\n\n"
  end;

def bullet_list($items):
  if ($items | length) == 0 then
    "(none)"
  else
    ($items | map("- " + .) | join("\n"))
  end;

def render_scope($s):
  if $s == null or ($s | type) != "object" then
    ""
  else
    "### Scope\n\n"
    + "#### In scope\n\n"
    + bullet_list($s.in // [])
    + "\n\n"
    + "#### Out of scope\n\n"
    + bullet_list($s.out // [])
    + "\n\n"
  end;

def render_affected($areas):
  if ($areas | length) == 0 then
    ""
  else
    "### Affected areas\n\n"
    + ($areas
        | map(
            if (.path != null) and (.note != null) then
              "- `" + .path + "` — " + .note
            elif .path != null then
              "- `" + .path + "`"
            else
              "- " + (.note // "")
            end
          )
        | join("\n"))
    + "\n\n"
  end;

def overview_to_markdown($o):
  if ($o | type) != "object" then
    ""
  elif ($o | keys | length) == 0 then
    "_No overview details provided._\n\n"
  else
    section("Summary"; $o.summary // "")
    + section("Problem statement"; $o.problemStatement // "")
    + section("Key insight"; $o.keyInsight // "")
    + render_scope($o.scope)
    + section("Architecture"; $o.architecture // "")
    + render_affected($o.affectedAreas // [])
    + section("User experience"; $o.userExperience // "")
  end;

($t[0]) as $root
| ($root.pullRequests[$idx]) as $next
| ($root.pullRequests | map(select(.id < $next.id and .status == "completed"))) as $completed_before
| (
    "## Task overview\n\n"
    + overview_to_markdown($root.overview // {})
    + "## Pull requests completed before this one\n\n"
    + (if ($completed_before | length) == 0 then
        "(none)\n"
      else
        ($completed_before | map("- PR \(.id): \(.title) (\(.status))") | join("\n")) + "\n"
      end)
  )
