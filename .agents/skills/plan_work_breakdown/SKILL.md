---
name: plan_work_breakdown
description: Orchestrate the creation of small, well-structured pull requests. Use when users ask to create a PR, draft a pull request, or prepare code changes for review. Delegates to specialized skills for planning (pr-scoped-planning) and documentation (pr-summary-writer).
---

# Short PR Creation

Guide code changes through a structured process that produces small, reviewable, well-documented pull requests.

## Overview

This is an **orchestrator skill** that coordinates two specialized skills:

| Phase | Skill | When to Invoke |
|-------|-------|----------------|
| **Planning** | `pr-scoped-planning` | Before writing code |
| **Documentation** | `pr-summary-writer` | After completing code |

## Core Philosophy

**Small PRs ship faster and get better reviews.** Every PR should be:
- **Small**: Can be fully reviewed in 15-20 minutes
- **Focused**: Does exactly one thing well
- **Self-contained**: Can be merged without other pending PRs
- **Documented**: Provides all context needed for confident review

## Workflow

### Phase 1: Planning (Before Coding)

**Invoke**: `pr-scoped-planning` skill

Focus areas:
1. Define the PR scope and goal
2. Identify natural boundaries
3. Apply breakdown strategies (Foundation First, Expand-Contract, Feature Flags, etc.)
4. Define PR dependencies
5. Estimate sizes

**Output**: A PR plan document outlining:
- The structure of one or more PRs
- Dependencies between PRs
- Size estimates
- Key decisions about the breakdown

### Phase 2: Implementation

Execute the planned changes following the PR structure.

**Validation during implementation:**
- Check that each PR stays within size guidelines
- Avoid scope creep
- Update plan if unexpected complexity is discovered

### Phase 3: Documentation (After Coding)

**Invoke**: `pr-summary-writer` skill

Focus areas:
1. Write clear summary (what + why)
2. Document specific changes
3. Explain testing approach and coverage
4. Record key decisions with reasoning
5. Request feedback on contentious areas

**Output**: A complete PR description with:
- Summary (2-3 sentences)
- Changes list
- Testing documentation
- Decision log
- Specific feedback questions

## When to Use Each Phase

| Scenario | Phases Needed |
|----------|--------------|
| Small, straightforward change | Documentation only |
| Complex feature requiring breakdown | Planning + Documentation |
| Refactoring with multiple PRs | Planning + Documentation |
| Quick bug fix | Documentation (skip planning) |
| Large epic spanning multiple PRs | Planning (per PR) + Documentation (per PR) |

## Delegation Strategy

**Use specialized skills for best results:**

1. **pr-scoped-planning**: When you need help breaking down a task before coding
2. **pr-summary-writer**: When you need help writing a PR description after coding

**When to invoke directly:**
- User asks specifically to "plan the PRs" → `pr-scoped-planning`
- User asks specifically to "write the PR description" → `pr-summary-writer`
- User asks to "create a PR" end-to-end → Use this orchestrator

## PR Size Guidelines

| Size | Lines Changed | Review Time | Action |
|------|---------------|-------------|--------|
| XS   | <50           | 5 min       | Fast-track |
| S    | 50-200        | 10-15 min   | Same day |
| M    | 200-400       | 20-30 min   | 1-2 days |
| L    | 400-700       | 45-60 min   | Break down |
| XL   | 700+          | 60+ min     | Must break down |

## Anti-Patterns to Avoid

### Bad PR Descriptions
- "Fixed bug" (what bug? what was the symptom?)
- "Updated code" (to what? why?)
- Copy-pasted commit messages
- Novel-length descriptions that repeat code

### Bad Practices
- PRs that only pass CI after multiple pushes
- Mixing refactoring with feature changes
- Including commented-out code
- Adding dependencies without justification

### Missing Documentation
- No testing explanation
- No context for why change was needed
- No information on how to test manually
- Ignoring known edge cases without mentioning them

## Final Checklist

Before finalizing each PR, verify:

- [ ] PR does exactly one thing
- [ ] Summary explains the "why", not just the "what"
- [ ] Testing section covers key scenarios
- [ ] Edge cases are either handled or explicitly noted as deferred
- [ ] Significant decisions are documented with reasoning
- [ ] Feedback questions are specific and actionable
- [ ] PR size is within guidelines (or justified exception)
- [ ] All CI checks pass
- [ ] Self-review completed (read your own diff)

## Related Skills

### Orchestration
- **start_new_task**: Complete workflow from planning through PR creation
- **document_plan**: Create task plan document
- **propose_plan**: Create PR for plan document
- **start_pull_request**: Start working on a PR
- **implement_pull_request**: Implement changes and tests
- **adjust_pull_request_plan**: Break down oversized PRs
- **propose_pull_request**: Create PR for implementation

### Utilities
- **github_cli**: GitHub CLI commands for Windows PowerShell

### Legacy (superseded)
- **pr-scoped-planning**: Replaced by document_plan
- **pr-summary-writer**: Replaced by propose_pull_request