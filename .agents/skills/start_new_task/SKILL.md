---
name: start_new_task
description: Orchestrate the high-level process of completing a coding task. Use when starting a new task, planning work, or implementing changes that require multiple pull requests. Delegates to specialized skills for planning, implementation, and PR creation.
---

# Start New Task

Orchestrate the complete lifecycle of a coding task from planning through PR creation.

## Overview

This is an **orchestrator skill** that coordinates the task completion workflow:

| Phase | Skill | When to Invoke |
|-------|-------|---------------|
| **Plan** | `document_plan` | Document the task requirements |
| **Propose Plan** | `propose_plan` | Create PR for the plan document |
| **Start PR** | `start_pull_request` | Begin working on a PR |
| **Implement** | `implement_pull_request` | Write code and tests |
| **Adjust Plan** | `adjust_pull_request_plan` | Break down oversized PRs |
| **Propose PR** | `propose_pull_request` | Create the PR for review |

## Workflow

### Phase 1: Plan the Work

**When**: User asks to start a new task or plan work.

1. **Explore** - Invoke `explore_task` skill to understand the codebase and identify affected areas
2. **Document** - Invoke `document_plan` skill to create a structured task plan
3. **Propose Plan** - Invoke `propose_plan` skill to create a PR for the plan

**Output**: A committed JSON task plan in `tasks/` (e.g. `tasks/TASK-ID.json`) documenting:
- Areas of the code that will change
- Architectural changes (with diagrams if needed)
- User experience changes
- PR checklist (spaced for easy tracking)
- Estimates for files/lines changed per PR

### Phase 2: Implement (Iterate)

**When**: Task plan is merged, or user instructs to "iterate on a task"

1. **Check State** - Review the task plan JSON (`tasks/*.json`), note completed PRs
2. **Start PR** - Invoke `start_pull_request` skill to create a feature branch
3. **Implement** - Invoke `implement_pull_request` skill to write code and tests
4. **Adjust if Needed** - If PR becomes too large, invoke `adjust_pull_request_plan`
5. **Propose PR** - Invoke `propose_pull_request` skill to create PR for review
6. **Include task-plan completion in the same PR** - Update the same task JSON in this branch/PR to set the finished PR's `status` to `"completed"` and `started` to `false`, then commit and push so merging this PR keeps the repo/task plan consistent

## PR Checklist Format

Document PRs in the task plan with spacing for tracking:

```markdown
## Pull Requests

- [ ] **PR 1: [Title]**
  - Est: ~X files, ~Y lines
  - Status: [Planned/In Progress/Merged]

<br>

- [ ] **PR 2: [Title]**
  - Est: ~X files, ~Y lines
  - Status: [Planned/In Progress/Merged]
```

## Size Guidelines

| Size | Lines Changed | Review Time |
|------|---------------|-------------|
| XS | <50 | 5 min |
| S | 50-200 | 10-15 min |
| M | 200-400 | 20-30 min |
| L | 400-700 | 45-60 min |
| XL | 700+ | Must break down |

## Delegation

Use specialized skills for each phase:

- `document_plan` - Create the task documentation
- `propose_plan` - Create PR for the plan document
- `start_pull_request` - Create feature branch
- `implement_pull_request` - Implement changes and tests
- `adjust_pull_request_plan` - Break down oversized PRs
- `propose_pull_request` - Create the final PR

## When to Use

| Scenario | Action |
|----------|--------|
| User asks to "start a task" | Run full Phase 1 (Plan) |
| Task plan is merged | Run Phase 2 (Implement) |
| User says "iterate" | Run Phase 2 (Implement) |
| User asks to "just implement this" | Start with `implement_pull_request` |
