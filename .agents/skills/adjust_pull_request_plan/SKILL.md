---
name: adjust_pull_request_plan
description: Break down an oversized pull request into multiple smaller PRs. Use when implementation reveals that a PR is too large (>700 lines) or addresses multiple concerns. Updates the task plan with the new breakdown.
---

# Adjust Pull Request Plan

Break down a PR that has become too large or addresses multiple concerns.

## When to Use

- PR exceeds 700 lines of code
- Implementation reveals multiple distinct changes
- PR does more than one thing
- Review feedback indicates PR is too large

## Principles

**Don't push large PRs.** They:
- Take too long to review properly
- Have higher risk of bugs
- Block other work

**Instead, break them down and ship incrementally.**

## Workflow

### Step 1: Identify Logical Breakpoints

Analyze the current changes and identify natural divisions:

| Strategy | What to split |
|----------|---------------|
| **By layer** | Data layer, service layer, API layer |
| **By feature** | Core logic, utilities, helpers |
| **By phase** | Add new alongside old, remove old |
| **By scope** | User-facing changes, infrastructure |

### Step 2: Reset Feature Branch

Keep the current work but reorganize:

```powershell
# On feature branch
git log --oneline
# Note the commit hashes

git checkout main
git checkout -b feature/part-1
# Implement just part 1
```

### Step 3: Update Task Plan

Edit `tasks/TASK-ID.json`: replace or split the oversized PR in `pullRequests` (preserve numeric `id` order or renumber consistently), set `dependencies` and `agentPrompt` on each new entry, and keep statuses accurate.

### Step 4: Create Plan Adjustment PR

Instead of the large implementation PR, create a PR with just the plan changes:

```powershell
git checkout -b feature/TASK-NAME-replan
git add tasks/TASK-ID.json
git commit -m "docs: break down TASK-ID into smaller PRs"
git push -u origin feature/TASK-ID-replan
```

### Step 5: Create Implementation PRs

After plan is merged, create separate implementation PRs for each part.

## Breaking Down Strategies

### Strategy 1: Extract Refactoring First

```
Original: Add feature X with refactoring Y
Part 1: Refactoring Y (no feature changes)
Part 2: Add feature X (using refactored code)
```

### Strategy 2: Extract Infrastructure

```
Original: Add feature X with new database schema
Part 1: Add database schema and migrations
Part 2: Add feature X using new schema
```

### Strategy 3: Expand-Contract

```
Original: Replace old system with new system
Part 1: Add new system alongside old (both work)
Part 2: Migrate users to new system
Part 3: Remove old system
```

## Common Reasons for Breaking Down

| Reason | Solution |
|--------|----------|
| >700 lines | Split by layer or feature |
| Multiple concerns | Split by concern |
| Deep refactoring | Refactor in separate PR |
| New dependency | Justify or defer dependency |

## Final Checklist

- [ ] PR is broken into logical, reviewable chunks
- [ ] Each new PR is <400 lines
- [ ] Dependencies are clearly stated
- [ ] Task plan is updated with new breakdown
- [ ] Plan adjustment is a separate, small PR
