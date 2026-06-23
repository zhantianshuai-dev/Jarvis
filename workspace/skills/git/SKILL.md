---
name: git
description: "Load only when the user explicitly asks to push to a remote repository. For status, diff, log, branch, show, or local commit, use the structured git tool directly and do not load this skill."
metadata: {"vikingbot":{"requires":{"bins":["git"]}}}
always: false
---

# Git Skill

Load this skill only for `git push` or clearly equivalent remote-publish requests. For read-only Git inspection such as `status`, `diff`, `log`, `branch`, or `show`, do not load this skill; call the structured `git` tool directly.

Use the structured `git` tool for Git operations. Prefer it over the generic `exec` tool so policy hooks and path checks can protect the repository.

## Safe Read Workflow

Before editing or committing code, inspect the repository:

```json
{"action":"status"}
```

Use `diff` to understand unstaged changes:

```json
{"action":"diff","stat":true}
```

Use `log` for recent history:

```json
{"action":"log","limit":10}
```

## Local Commit Workflow

Only commit files the user asked you to include.

1. Run `status`.
2. Run `diff` or `diff` with `stat=true`.
3. Add explicit paths only.
4. Commit with a concise Conventional Commit-style message.
5. Run `status` again.

Example:

```json
{"action":"commit","paths":["jarvis/src/main/java/com/zhan/jarvis/tool/impl/GitTool.java"],"message":"feat(git): add structured git tool"}
```

Never use `paths:["."]`.

## Branches

To create and switch to a new local branch:

```json
{"action":"checkout_new_branch","branch":"feature/git-tool"}
```

Use clear branch names such as:

```text
feature/git-tool
fix/auth-login
docs/git-plan
```

## Confirmed Operations

These operations require a two-step confirmation flow:

```text
git push
git pull
git restore
```

First call the requested action. The tool returns `requires_confirmation=true` and a `confirm_id`. Stop there and wait for the user; confirmation is handled by the backend `/api/v1/tools/confirm` flow, not by another LLM tool call.

For push and pull, always pass an explicit branch:

```json
{"action":"push","remote":"origin","branch":"main"}
```

## Prohibited Operations

Do not attempt these through `git`, `exec`, or any other tool:

```text
git reset
git clean
git checkout .
git push --force
```

If the user asks for prohibited destructive Git work, explain that current Git policy blocks it.

## Subagents

For parallel code work, spawn subagents with backend-managed worktree isolation:

```json
{"task":"Refactor auth service in isolation","create_worktree":true,"worktree":"auth-refactor"}
```

If `worktree` is omitted, Jarvis creates a safe name from the task and task id. For read-only research or summarization, do not create a worktree. Do not try to create, remove, or switch worktrees through shell commands; worktree lifecycle is managed by Jarvis.
