# Product ADR-0036: Foreground-aware post-watering reminder presentation

**Status**: accepted

**Date**: 2026-09-09

## Context

Product ADR-0035 defined the post-watering reminder as a system notification. That works while YAPT
is backgrounded, but it is unnecessarily indirect while the user is already looking at the app: the
notification shade becomes an extra surface for a message that can be acted on in place.

The alternatives were to keep system notifications everywhere, show an in-process dialog that could
be lost on rotation or process recreation, always create both presentations, or route each due
reminder to exactly one durable presentation based on whether YAPT is foregrounded.

## Decision

Retain ADR-0035's 30-minute last-watering debounce, generic standing-water copy, WATER-path coverage,
eligibility exclusions, settings and backup behavior. Change only how a due reminder is presented:

- While YAPT is foregrounded, persist a pending presentation token in DataStore and show a global,
  dismissible in-app modal. Do not post the system notification for that firing.
- While YAPT is backgrounded, clear any stale pending modal token and post the existing system
  notification with its transient **Cared for today** deep link.
- Dismissing the modal clears only the token it displayed and cancels notification ID `-2`. A newer
  token cannot be accidentally cleared by dismissing an older dialog.
- Turning off either the master Notifications switch or **Drain-water reminder** clears pending work,
  modal state, and the system notification. Backup import also clears this transient presentation
  state because it belongs to the replaced care history.
- The Android notification permission gates the background system notification only. The in-app
  modal needs no OS permission, but both app-level reminder switches still gate normal scheduling.
- Developer mode includes **Show drain-water reminder now**, which deliberately raises the in-app
  modal immediately so the presentation can be tested without waiting 30 minutes.

The pending token is device-local operational state. It is not added to `.yapt` backups and does not
change backup schema v15.

## Consequences

- A user already in YAPT sees the reminder directly, while a backgrounded user keeps normal Android
  notification delivery.
- The two presentation paths are mutually exclusive for each firing, avoiding duplicate prompts.
- A due foreground reminder survives rotation and process recreation until it is dismissed or
  superseded, at the cost of one ephemeral DataStore key.
- If YAPT is backgrounded and notification permission is denied, no reminder can be surfaced; opening
  the app later does not manufacture a modal for that background firing.
- Foreground state is process-local and changes at activity start/stop boundaries, so a firing during
  a lifecycle transition may take the background path. Either result remains a single valid reminder.
