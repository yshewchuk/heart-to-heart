---
name: github_cli
description: Use GitHub CLI (gh) in Windows PowerShell. Use when creating branches, pushing code, creating pull requests, checking status, or any GitHub operations. Commands use PowerShell syntax.
---

# GitHub CLI in Windows PowerShell

Commands for working with GitHub using the `gh` CLI in Windows PowerShell.

## ⚠️ PowerShell Limitations (Important)

PowerShell does **not** support:
- `&&` or `||` as command separators — use `;` or run commands separately
- Bash-style heredocs (`<<EOF`) — they cause parse errors
- Multi-line strings via `-Body "$(cat <<'EOF'...)"` — this fails

**For multi-line PR bodies**, write to a temp file first, then use `--body-file`:

```powershell
# Write body to a file first
@"
## Summary
Description here.

## Changes
- Change 1
"@ | Out-File -FilePath pr-body.txt -Encoding utf8

# Then create PR with the file
gh pr create --title "feat: my feature" --body-file "pr-body.txt"

# Clean up
Remove-Item pr-body.txt
```

**For chained commands**, run them as separate Shell calls:

```powershell
git add .
git commit -m "feat: my feature"
git push -u origin feature/my-feature
```

## Authentication

### Check if logged in
```powershell
gh auth status
```

### Log in (if needed)
```powershell
gh auth login
```

## Branches

### Create and switch to new branch
```powershell
git checkout -b feature/branch-name
```

### List local branches
```powershell
git branch
```

### List remote branches
```powershell
git branch -r
```

### Switch to existing branch
```powershell
git checkout branch-name
```

### Delete local branch
```powershell
git branch -d branch-name
```

## Remote Operations

### Push branch to remote
```powershell
git push -u origin feature/branch-name
```

### Push current branch
```powershell
git push
```

### Pull from remote
```powershell
git pull origin main
```

### Fetch updates
```powershell
git fetch origin
```

## Staging and Committing

### Check status
```powershell
git status
```

### Stage specific file
```powershell
git add path/to/file.txt
```

### Stage all changes
```powershell
git add .
```

### Commit with message
```powershell
git commit -m "feat: add new feature"
```

### Amend last commit (if not pushed)
```powershell
git commit --amend --no-edit
```

### Unstage file
```powershell
git reset HEAD path/to/file.txt
```

## Pull Requests

### Create PR with inline body (single-line only)
```powershell
gh pr create --title "feat: add feature" --body "Description here"
```

### Create PR with multi-line body
See the PowerShell Limitations section above for the correct approach using a temp file and `--body-file`.

### List open PRs
```powershell
gh pr list
```

### View PR details
```powershell
gh pr view 123
```

### View PR status
```powershell
gh pr status
```

### Merge PR
```powershell
gh pr merge 123 --admin --squash
```

### Close PR
```powershell
gh pr close 123
```

## Working with Main/Master

### Sync main branch
```powershell
git checkout main
git pull origin main
```

### Rebase current branch on main
```powershell
git rebase main
```

### Merge main into current branch
```powershell
git merge main
```

## Diff and Log

### View unstaged changes
```powershell
git diff
```

### View staged changes
```powershell
git diff --staged
```

### View recent commits
```powershell
git log --oneline -10
```

### View commits on branch
```powershell
git log main..feature/branch-name --oneline
```

## GitHub Status Checks

### View CI status
```powershell
gh pr checks 123
```

### View all PR statuses
```powershell
gh pr status --verbose
```

## Configuration

### Set default branch
```powershell
gh config set default_branch main
```

### View all config
```powershell
gh config list
```

## Useful Aliases

Add to PowerShell profile for convenience:

```powershell
# Git aliases
Set-Alias -Name gs -Value git status
Set-Alias -Name gp -Value git push
Set-Alias -Name gco -Value git checkout
Set-Alias -Name gl -Value git pull
Set-Alias -Name gr -Value git rebase
```

## Common Workflows

### Start new feature
```powershell
git checkout main
git pull origin main
git checkout -b feature/my-feature
# Make changes
git add .
git commit -m "feat: add my feature"
git push -u origin feature/my-feature
```

### Complete PR review
```powershell
git checkout main
git pull origin main
git branch -d feature/my-feature
gh pr merge 123 --admin --squash
```

### Rebase onto latest main
```powershell
git checkout feature/my-feature
git fetch origin
git rebase origin/main
# Resolve conflicts if any
git push --force-with-lease
```
