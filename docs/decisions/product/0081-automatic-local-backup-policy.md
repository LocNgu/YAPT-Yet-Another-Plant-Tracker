# Product ADR-0081: Automatic local backup policy

**Status**: accepted

**Date**: 2026-11-05

## Context

Issue #1001 left four product questions open: how often to back up, how many archives to keep, whether
to include photos every time, and how to tell the user when it fails. Backups are the only recovery path
for an offline-first app, but a silent failure is worse than none, and notification noise erodes trust.

## Decision

- **Opt-in, off by default.** Nothing runs until the user picks a folder.
- **Frequency:** Daily or Weekly, default Weekly. A run is skipped when nothing changed since the last
  successful backup.
- **Retention:** keep the last 3, 7 or 14 dated files, default 7. Only YAPT's own
  `yapt-backup-YYYY-MM-DD.yapt` files are ever pruned, and only after a new one was written.
- **Photos:** "Include photos" is on by default, with the existing opt-in "Optimize photos" (product
  ADR-0041: lossy optimization stays explicit).
- **Failure reporting:** the Settings status line always shows the last result ("Last backup: …",
  "Folder unavailable, choose again", "N photos skipped"). A notification is posted once, on the second
  consecutive failure, or immediately when the folder permission is lost. One notification per streak.
- **Restore** stays the manual Import. Settings do not travel in a `.yapt` file.

## Consequences

A transient single failure is quiet. A user who denies notifications still sees the status line. Users who
want one rolling file must delete older ones themselves, or keep 3.
