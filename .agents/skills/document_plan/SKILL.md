---
name: document_plan
description: Document a coding task in a structured markdown file. Use when planning a new feature, refactoring, or any task that requires multiple pull requests. Creates detailed task plans with PR breakdowns.
---

# Document Plan

Create a structured markdown document that outlines a task and its PR breakdown.

## When to Use

- Planning a new feature or refactoring
- When a task requires multiple PRs
- After exploring the codebase with `explore_task` skill

## Workflow

### Step 1: Determine Scope

Identify what the task encompasses:
- What problem does it solve?
- What are the boundaries of this task?
- What is explicitly out of scope?

### Step 2: Identify PRs

Break down the task into logical, small PRs:

| Strategy | When to Use |
|----------|-------------|
| **Foundation First** | Infrastructure needed before features |
| **Expand-Contract** | Add new thing alongside old, then remove old |
| **Feature Flags** | Incremental rollout of changes |
| **Parallel Tracks** | Independent changes that can ship separately |

**Rules for good PRs:**
- Each PR should be reviewable in 15-20 minutes
- PRs should not depend on each other unless necessary
- Ship refactorings separately from feature changes

### Step 3: Create the Document

Create `tasks/TASK-NAME.md` with this structure:

```markdown
# Task: [Task Name]

## Overview

[2-3 sentences describing what this task accomplishes]

## Problem Statement

[What problem does this solve? Why does it need to change?]

## Scope

### In Scope
- [Item 1]
- [Item 2]

### Out of Scope
- [Item 1]
- [Item 2]

## Affected Areas

- `src/file1.ts` - [what changes]
- `src/file2.ts` - [what changes]

## Architecture

[If applicable, describe architectural changes]
[Include diagrams if helpful]

## User Experience

[How does this affect users?]

## Pull Requests

<br>

- [ ] **PR 1: [Title]**
  - Description: [What this PR does]
  - Est: ~X files, ~Y lines
  - Status: Planned
  - Dependencies: None

<br>

- [ ] **PR 2: [Title]**
  - Description: [What this PR does]
  - Est: ~X files, ~Y lines
  - Status: Planned
  - Dependencies: PR 1

<br>

- [ ] **PR 3: [Title]**
  - Description: [What this PR does]
  - Est: ~X files, ~Y lines
  - Status: Planned
  - Dependencies: PR 2

## Next Steps

1. Review and merge this plan
2. Implement PRs in order
3. Mark each PR complete in this document as you merge
```

## PR Checklist Formatting

**Important**: Use `<br>` between PRs to create visual spacing. This allows checkboxes to be checked off individually when viewing the rendered markdown.

```markdown
- [ ] **PR 1: Add user authentication**
  - Est: ~5 files, ~150 lines
  - Status: Planned

<br>

- [ ] **PR 2: Add user profile page**
  - Est: ~8 files, ~300 lines
  - Status: Planned
  - Dependencies: PR 1
```

## Estimating PR Size

Use the guidelines:

| Size | Lines Changed | Files |
|------|---------------|-------|
| XS | <50 | 1-2 |
| S | 50-200 | 2-5 |
| M | 200-400 | 5-10 |
| L | 400-700 | 10-15 |
| XL | 700+ | Must split |

Be conservative in estimates. It's better to underestimate and deliver fast than overestimate and deliver late.

## Final Checklist

Before finalizing the document:

- [ ] Overview clearly explains the "why"
- [ ] Scope is clearly defined (in and out)
- [ ] Affected areas are identified
- [ ] Each PR does exactly one thing
- [ ] PRs are in dependency order
- [ ] Size estimates are reasonable
- [ ] Each PR is spaced with `<br>` for checkbox tracking
