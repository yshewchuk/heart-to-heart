---
name: implement_pull_request
description: Implement the changes for a pull request and write unit tests. Use after starting a PR with start_pull_request skill. Focuses on writing focused, well-tested code.
---

# Implement Pull Request

Write code and tests for a pull request.

## When to Use

- Feature branch is created and ready for implementation
- Working on changes described in the task plan JSON under `tasks/`

## Implementation Principles

1. **Small, focused changes**: Each commit should be reviewable in minutes
2. **Test as you go**: Write tests that verify the change works correctly
3. **Stay within scope**: Avoid scope creep; defer extras to future PRs
4. **Check size**: If changes exceed 700 lines, consider breaking down

## Workflow

### Step 1: Implement Changes

Make the necessary code changes:

1. Follow project conventions and style
2. Keep functions small and focused
3. Add comments only for non-obvious intent
4. Avoid adding dependencies without justification

### Step 2: Write Tests

Follow the project's test structure:

**Test organization:**
```
tests/
├── unit/
│   └── feature/
│       └── my-feature.test.ts
├── integration/
│   └── feature/
│       └── my-feature.test.ts
```

**Test naming:** `[describe what being tested] [expected behavior when condition]`

**Good test examples:**
```typescript
describe('UserService.getProfile', () => {
  it('returns user profile when user exists', async () => {
    // test implementation
  });

  it('throws NotFoundError when user does not exist', async () => {
    // test implementation
  });
});
```

### Step 3: Run Tests

Verify all tests pass:

```powershell
npm test
# or
npm run test:unit
```

### Step 4: Verify Size

Check lines changed:

```powershell
git diff --stat
```

| Size | Lines | Action |
|------|-------|--------|
| XS-S | <200 | Good to go |
| M | 200-400 | Review carefully |
| L | 400-700 | Consider breaking down |
| XL | 700+ | Must break down (use `adjust_pull_request_plan`) |

### Step 5: Commit

Commit your changes with clear messages:

```powershell
git add .
git commit -m "feat(auth): add JWT-based authentication

- Add login endpoint with token generation
- Add token validation middleware
- Add unit tests for auth service"
```

## Size Management

**If PR becomes too large:**

Do NOT push a large PR. Instead:
1. Use `adjust_pull_request_plan` skill to break down into smaller PRs
2. Create a PR with just the plan changes for review
3. Start fresh PRs for the actual implementation

## Final Checklist

- [ ] Code changes are focused and minimal
- [ ] Tests cover key scenarios
- [ ] All tests pass
- [ ] PR size is within guidelines (<700 lines)
- [ ] No debug code or TODOs left in
- [ ] Commit message is clear
