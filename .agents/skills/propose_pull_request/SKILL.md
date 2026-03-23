---
name: propose_pull_request
description: Create a pull request for implemented changes. Use after implementing changes with implement_pull_request skill. Creates detailed PR descriptions with changes, testing, and decisions.
---

# Propose Pull Request

Create a pull request for implemented changes with detailed documentation.

## When to Use

- Implementation is complete and tests pass
- Ready for code review

## PR Overview Template

```markdown
## Summary
<!-- What changed and why? 2-3 sentences -->

## Changes
<!-- Bullet list of specific changes -->

## Testing
<!-- How was this verified? -->
### Automated Tests
<!-- Unit, integration, e2e tests -->
### Manual Testing
<!-- Steps for manual verification -->
### Edge Cases
<!-- What's covered, what's deferred -->

## Decisions
<!-- Key choices with reasoning -->

## Feedback Requested
<!-- Specific questions for reviewers -->
1.
2.
```

## Workflow

### Step 1: Final Verification

```powershell
git diff --stat
npm test
```

Ensure:
- All changes are committed
- Tests pass
- No debug code or TODOs

### Step 2: Push Branch

```powershell
git push -u origin feature/branch-name
```

### Step 3: Create PR

Use `gh pr create`:

```powershell
gh pr create --title "feat: add user authentication" --body "$(cat <<'EOF'
## Summary
<!-- Your summary here -->

## Changes
- [Action] [what] [to do what]

## Testing
### Automated Tests
- Unit tests validate X, Y, Z edge cases

### Manual Testing
1. [Step 1]
2. [Step 2]
3. [Expected result]

## Decisions
| Decision | Alternatives | Reasoning |
|----------|--------------|-----------|

## Feedback Requested
1. [Question 1]
2. [Question 2]
EOF
)"
```

### Step 4: Update Task Plan

Mark the PR as merged in `tasks/TASK-NAME.md`:

```markdown
- [x] **PR 1: Add user authentication**  <!-- Changed from [ ] to [x] -->
  - Est: ~5 files, ~150 lines
  - Status: Merged
```

### Step 5: Check for Parallel Work

Review the task plan for other unblocked PRs:
- PRs with no dependencies on current PR
- PRs whose dependencies are all merged

If found, start the next logical PR using `start_pull_request`.

## Documentation Requirements

### Summary
**Answer**: What changed and why?

Rules:
- Lead with business/technical reason
- Use present tense: "Adds" not "Added"
- 2-3 sentences max

### Changes
**Answer**: What specific actions were taken?

Format:
```markdown
- [Action] [what] [to do what]
```

Good examples:
- "Fixed NPE when user is null in UserService.getProfile()"
- "Increased connection pool size from 10 to 50"

### Testing
**Answer**: How do you know this works?

Include:
- Automated test coverage
- Manual verification steps
- Edge cases handled or deferred

### Decisions
**Answer**: Why were key choices made?

Format:
```markdown
| Decision | Alternatives | Reasoning |
|----------|--------------|-----------|
```

### Feedback
**Answer**: What do you want input on?

Ask about:
- Contentious areas
- Trade-offs made
- Deferred work
- Naming/clarity

## GitHub CLI Reference

See `github_cli` skill for detailed Windows PowerShell GitHub CLI commands.

## Final Checklist

- [ ] Summary is 2-3 sentences
- [ ] Changes list specific actions
- [ ] Testing explains verification
- [ ] Edge cases are handled or noted
- [ ] Decisions have reasoning
- [ ] Feedback questions are specific
- [ ] Task plan updated and checked off
- [ ] No debug code or TODOs
- [ ] CI passes
