---
name: document_plan
description: Document a coding task in a structured JSON file under tasks/. Use when planning a new feature, refactoring, or any task that requires multiple pull requests. Creates detailed task plans with PR breakdowns for agents and GitHub Actions.
---

# Document Plan

Create a structured JSON task plan that outlines a task and its PR breakdown (consumed by GitHub Actions and agents).

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

### Step 3: Create the JSON file

Create `tasks/TASK-ID.json` (kebab-case id, e.g. `multi-partner-support.json`) with this shape:

- **`id`**, **`title`**: Task identifier and display title.
- **`overview`**: Object with the high-level plan (not a flat string dump). The **Next Task Agent** workflow turns this into markdown sections (`### Summary`, scope bullets, architecture, etc.); keep field names stable so that rendering stays predictable.
  - `summary`, `problemStatement`, `keyInsight` (strings)
  - `scope`: `{ "in": [...], "out": [...] }` (string arrays)
  - `architecture`, `userExperience` (markdown strings; diagrams/code blocks allowed)
  - `affectedAreas`: `[{ "path": "...", "note": "..." }, ...]`
- **`pullRequests`**: Ordered array of PR objects:
  - `id` (number, 0-based), `title`, `description`, `estimate` (string)
  - `status`: `"planned"` | `"in_progress"` | `"completed"`
  - `started`: boolean (GitHub workflow sets this when an agent run begins; agents should respect it)
  - `dependencies`: e.g. `["PR 1"]` for PR 2 — only refer to completed PRs by id
  - `agentPrompt` (string): Instructions the automated workflow passes to the agent for this PR (can match `description` or add repo-specific detail)
  - Optional: `changes` (string) for release-note style notes after merge

Example skeleton (abbreviated):

```json
{
  "id": "my-feature",
  "title": "My Feature",
  "overview": {
    "summary": "...",
    "problemStatement": "...",
    "keyInsight": "...",
    "scope": { "in": [], "out": [] },
    "architecture": "...",
    "affectedAreas": [],
    "userExperience": "..."
  },
  "pullRequests": [
    {
      "id": 0,
      "title": "...",
      "description": "...",
      "estimate": "...",
      "status": "planned",
      "started": false,
      "dependencies": [],
      "agentPrompt": "..."
    }
  ]
}
```

## PR list in JSON

Keep `pullRequests` in dependency order. The automated workflow picks the first item where `status` is not `"completed"`, all `dependencies` refer to completed PRs, and no PR has `started: true` or `status: "in_progress"`. When you finish a PR, set that entry's `status` to `"completed"`, `started` to `false`, and optionally fill `changes`.

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

Before finalizing the JSON:

- [ ] Overview clearly explains the "why"
- [ ] Scope is clearly defined (in and out)
- [ ] Affected areas are identified
- [ ] Each PR does exactly one thing
- [ ] PRs are in dependency order
- [ ] Size estimates are reasonable
- [ ] Each PR has `agentPrompt` suitable for the automation workflow
