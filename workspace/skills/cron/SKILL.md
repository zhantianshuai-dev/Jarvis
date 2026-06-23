---
name: cron
description: Schedule reminders and recurring agent tasks.
always: false
---

# Cron

Use the `cron` tool when the user asks for reminders, delayed follow-ups, recurring checks, or scheduled work.

## Modes

1. Reminder: deliver a message back to the current user at a later time.
2. Task: ask the agent to perform work at the scheduled time and report the result.
3. One-time: run once at an exact time, then delete automatically.

## Use Cases

- "Remind me in 30 minutes" -> create an `at` job with an ISO datetime.
- "Check this every 10 minutes" -> create an `every_seconds` job.
- "Every day at 9am" -> create a `cron_expr` job.
- "Show my reminders" -> call `cron(action="list")`.
- "Cancel that reminder" -> call `cron(action="remove", job_id="...")`.

## Examples

```
cron(action="add", name="break", message="Time to take a break.", every_seconds=1200)
cron(action="add", name="daily-summary", message="Summarize yesterday's work.", cron_expr="0 9 * * *", tz="Asia/Shanghai")
cron(action="add", name="meeting", message="Remind me about the meeting.", at="<ISO datetime>")
cron(action="list")
cron(action="remove", job_id="abc123")
cron(action="status")
```

## Time Conversion

Compute concrete times from the current time in the system prompt. For one-time jobs, pass `at` as an ISO datetime. For recurring schedules, prefer `every_seconds` for simple intervals and `cron_expr` for calendar schedules.

## Delivery

For user-facing reminders and scheduled tasks, leave `deliver` as true so the result is sent back to the original channel.
