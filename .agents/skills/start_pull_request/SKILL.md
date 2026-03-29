---
name: start_pull_request
description: Start working on a pull request by creating a feature branch. Use when beginning implementation of a PR from an approved task plan. Picks the next logical unblocked PR to work on.
---

# Start Pull Request

Create a feature branch to begin working on a PR.

## When to Use

- Task plan is merged and ready for implementation
- User says "start working on PR X" or "implement the next PR"
- Picking up a new PR from the task plan

## Workflow

### Step 1: Review Task Plan

Read the task plan JSON (`tasks/TASK-ID.json`) and:
1. Find the next unblocked PR (dependencies must be merged / prior PRs `completed` in the JSON)
2. Set that PR's `status` to `"in_progress"` and `started` to `true`
3. Note the estimated scope

### Step 2: Sync with Main

Ensure main branch is up to date:

```powershell
git checkout main
git pull origin main
```

### Step 3: Create Feature Branch

Branch naming convention: `feature/TASK-NAME-DESCRIPTION` or `fix/TASK-NAME-DESCRIPTION`

```powershell
git checkout -b feature/add-user-auth
```

### Step 4: Verify Branch

```powershell
git branch
# Should show the new branch with * prefix
```

## Picking the Next PR

**Rules for selecting which PR to work on:**

1. **Dependencies first**: Only start a PR if all its dependencies are merged
2. **Logical order**: Follow the order in the task plan
3. **Parallel work**: If multiple PRs have no dependencies and are unblocked, you can work on one while another is in review

**Check for parallel work**:
- Review open PRs
- If a PR doesn't depend on any open PRs, it can be started in parallel

## Updating the Task Plan

When starting a PR, update the task plan document:

```markdown
- [ ] **PR 1: Add user authentication**
  - Est: ~5 files, ~150 lines
  - Status: In Progress  # <-- Changed from "Planned"
  - Dependencies: None
```

## GitHub CLI Reference

See `github_cli` skill for detailed Windows PowerShell GitHub CLI commands.

## Final Checklist

- [ ] Selected PR has all dependencies merged
- [ ] Task plan updated with "In Progress" status
- [ ] Feature branch created from main
- [ ] Branch name follows convention
- [ ] Main branch is up to date
