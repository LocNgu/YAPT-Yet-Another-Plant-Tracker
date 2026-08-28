# Product ADR-0029: Water / Still moist / Reschedule watering — three actions, three meanings

**Status**: superseded by [ADR-0030](0030-off-schedule-actions-ask-why.md)

**Date**: 2026-08-24

**Supersedes**: ADR-0007 (name, dialog UX, and 1-7-day forward-only range). Resolves ADR-0027's
"Still moist is reachable only from the notification" accepted limitation.

## Context

Product ADR-0007 established "Skip watering" as a temporary `wateringDueDateOverride` push, deliberately
inert to `wateringIntervalDays`/the adaptive model. That decision's mechanism (write the override, never
the interval) still holds, but three problems accumulated on top of it, one introduced by #570:

- The wording ("Skip") implies the watering is dropped, when it is actually deferred.
- The 1-7-day stepper only ever moves the due date *forward*. There was no way to pull it to today or to
  pick an arbitrary custom date.
- #570 added a "Still moist" notification action (`CareType.CHECK`, product ADR-0027) that lengthens the
  adaptive model's learned interval — but only from the reminder notification. A user who opens the app
  instead of tapping the notification saw just "Log watering" and "Skip watering", and Skip is deliberately
  inert to the model. The intuitive in-app control was the one that taught the app nothing — ADR-0027
  recorded this explicitly as an accepted limitation pointing here.

## Decision

Plant Detail's watering-due surface (both the classic single-page layout and the Water tab, product
ADR-0023) now offers three buttons in one row, each meaning exactly one thing:

| Action | Meaning | Effect on the model |
|---|---|---|
| **Water** | I watered it | Opens the same `WaterFeedbackBottomSheet` flow the watering `StatChip` already uses — a WATER log, optional "was dry" flag |
| **Still moist** | I checked; not ready yet | Routes through the same `QuickLogUseCase.recordStillMoistCheck()` the #570 notification action (`StillMoistReceiver`) calls — a `CareType.CHECK` log, due-date deferral, and (when `adaptive_watering` is on) a confidence update |
| **Reschedule watering** | I'm away / not now | Writes `wateringDueDateOverride` only — **inert** to `wateringIntervalDays`, `wateringBaseIntervalDays`, `wateringConfidence`, and `watering_adjustments` |

The existing watering `StatChip` elsewhere on the screen is left in place; removing it is a deferred
follow-up, out of scope here.

### Still moist, in-app

`PlantDetailViewModel.recordStillMoist()` calls `QuickLogUseCase.recordStillMoistCheck(plant)` directly —
the exact function `StillMoistReceiver.handleStillMoist()` calls — so the in-app and notification paths
produce identical logs and identical model effects by construction, not by two implementations happening
to agree. This closes ADR-0027's accepted limitation: Still moist is no longer notification-only.

### Reschedule watering (renamed from "Skip watering")

`skip_watering_title` (and its now-orphaned stepper-specific siblings — the confirm/decrease/increase
strings and the 1-7-day plural) is replaced by `reschedule_watering_title` ("Reschedule watering"), shared
by both the Plant Detail button/dialog and the `ReminderWorker` notification action label — one rename
satisfies both surfaces, since `SkipWateringReceiver` still handles that notification action's underlying
`wateringDueDateOverride` write unchanged.

The 1-7-day stepper dialog is replaced with five options:

- **Today** — sets the due date to today. Disabled while the plant's effective due date is already today
  (`PlantCareStatus.isDueSoon`) — that write would be a true no-op there. Enabled while overdue
  (`isOverdue`) — it usefully clears the backlog to today without deferring further.
- **+1 day / +2 days / +3 days** — anchored to the current *effective* due date
  (`maxOf(nextWateringDueAt, now)`, unchanged from the superseded stepper's anchor — already
  override-aware via `CareSchedule`).
- **Custom date…** — a Material 3 `DatePicker` constrained to today-or-later via `SelectableDates`. Past
  dates are excluded outright; a "log a backdated watering" need is already served by the AddCareLog flow,
  not this control.

Every option writes `wateringDueDateOverride` only, exactly as the superseded skip flow did — never
`wateringIntervalDays` or `wateringBaseIntervalDays`. The override still clears automatically on the next
WATER log (`QuickLogUseCase.clearWateringOverrideIfActive()`, unchanged).

**The ADR-0006 follow-up interval-suggestion dialog never fires after any Reschedule option.** The
superseded skip flow's `Event.SkipConfirmed(days, proposed)` — which `PlantDetailScreen`'s event collector
fed directly into `suggestedWateringInterval.value`, bypassing `applySuggestionOrPrompt()`/
`shouldShowIntervalDialog()` and the #572 "Ask before changing intervals" toggle entirely — is removed, not
routed around the toggle. This was a pre-existing bug (the toggle was silently inert to skip), not an
intentional design being preserved; removing it is a deliberate behavior change. Reschedule is a purely
calendar operation with zero downstream prompt, for any option, regardless of the toggle's state.

**Reschedule is not a `watering_adjustments` writer.** It gains no new `WateringAdjustmentTrigger` value —
the table (product ADR-0028) exists to explain every event that moves `wateringConfidence`/the base
interval, and Reschedule moves neither. This mirrors `SkipWateringReceiver`'s existing posture and keeps
Reschedule symmetric with Still moist: one writer of `wateringDueDateOverride` that also feeds the model
(Still moist), one that deliberately does not (Reschedule).

## Consequences

- `wateringDueDateOverride` now has three writers: the Reschedule dialog, `SkipWateringReceiver` (the
  notification action), and `QuickLogUseCase.recordStillMoistCheck()` (both the in-app Still moist button
  and the notification's Still-moist action) — no longer written from one place, though every writer still
  agrees on the column's meaning (a temporary, calendar-only due-date push).
- `check_reminders` (#570) can now graduate out of developer mode without offering the notification a
  choice ("Still moist") the app itself couldn't make — the blocking condition ADR-0027 recorded is
  resolved by this issue, though graduating the flag itself is a separate decision.
- `worker/SkipWateringReceiver.kt` — an unregistered duplicate of `notification/SkipWateringReceiver.kt`
  that still mutated `wateringIntervalDays` directly, contradicting ADR-0007 — is deleted as dead code in
  the same PR.
- The Reschedule dialog's five options replace one full-width button with a stepper; Plant Detail's
  watering-due surface goes from one button to three, which was evaluated against the tabs/inline-settings
  layout (product ADR-0023) rather than assumed safe by default.
