---
name: propose_plan
description: Create a pull request for a task plan document. Use after creating a task plan with document_plan skill. The PR should be a short introduction since the plan document speaks for itself.
---

# Propose Plan

Create a pull request for a task plan document.

## When to Use

After `document_plan` skill has created a task plan markdown file.

## PR Summary Format

The PR summary should be brief - the plan document provides all details.

```markdown
## Summary

Plan for [Task Name]: [2-3 sentence overview of what this task accomplishes and why]

## Changes

- Add `tasks/TASK-NAME.md` with complete task plan and PR breakdown

## Next Steps

1. Review the task plan in `tasks/TASK-NAME.md`
2. Approve and merge to begin implementation
```

## Workflow

### Step 1: Create Feature Branch

```powershell
git checkout -b plan/TASK-NAME
```

### Step 2: Stage and Commit

```powershell
git add tasks/TASK-NAME.md
git commit -m "docs: add task plan for TASK-NAME"
```

### Step 3: Push Branch

```powershell
git push -u origin plan/TASK-NAME
```

### Step 4: Create PR

Use `gh pr create` with the summary format above. Since the plan document is self-documenting, keep the PR description brief.

## GitHub CLI Reference

See `github_cli` skill for detailed Windows PowerShell GitHub CLI commands.

## Final Checklist

- [ ] PR summary is brief (plan document speaks for itself)
- [ ] Plan document is complete and well-structured
- [ ] Branch name follows convention: `plan/TASK-NAME`
- [ ] PR includes link to the plan document
- [ ] CI passes (if applicable)
