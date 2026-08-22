# Product ADR-0027: "Check" reminders with a Still-moist action

**Status**: accepted

**Date**: 2026-08-22

**Supersedes**: ADR-0011 (soil-state question text). Partially supersedes ADR-0016 (three-way feedback chip clause
only) and ADR-0024 (the three-way chip's deselect behavior is replaced, not merely amended).

## Context

Split from #285, part 3 of 5 (related: #568, #569). Two compounding problems with the pre-existing watering
reminder:

1. **The default answer meant "change nothing".** The Add Care Log screen and the quick-water sheet pre-selected
   `JUST_RIGHT` (ADR-0002/ADR-0016), and ADR-0001 treated `JUST_RIGHT` as producing no suggestion at all. The most
   common answer to "How was the soil?" was also the instruction to leave the schedule alone — frequently a value
   the user never actually chose.
2. **The most common gardening action went unrecorded.** A gardener's single most frequent action is walking over,
   feeling the soil, and walking away without watering. That event carries a clean, unambiguous "not ready yet,
   lengthen" signal, and the app had no way to record it — every prior approach learned exclusively from watering
   events.

Two design questions needed resolving: (1) how to reframe the notification and record the still-moist observation,
and (2) what to do with the now-largely-uninformative three-way feedback chip.

## Decision

### Notification reframing

`FeatureFlagRegistry.CHECK_REMINDERS` (`check_reminders`, default off, independent of `ADAPTIVE_WATERING` —
different risk surface: this one touches `ReminderWorker`, the notification composer, and a new broadcast
receiver, none of which #568 goes near). Gated on the reminder actually being watering-due
(`status.isOverdue || status.isDueSoon`), matching the existing condition for showing "Skip watering" today — a
fertilizing/repotting-only reminder never reframes, flag or no flag.

Flag off (or not watering-due): notification is byte-for-byte identical to today — title is the plant's name, a
single "Skip watering" action. Flag on, watering-due: title becomes "Check {plant}"; "Skip watering" is replaced
by two actions — **Watered** (reuses the same deep-link `PendingIntent` as tapping the notification body — a
discoverability affordance, not a new code path) and **Still moist** (`StillMoistReceiver`, mirroring
`SkipWateringReceiver`'s no-dialog, single-tap shape).

### Recording the observation: `CareType.CHECK`, not a new table

Two options existed: a new `CareType.CHECK` value reusing the whole care-log pipeline, or a dedicated
`soil_checks` table. `CareType.CHECK` was chosen. The real `when (careType)` blast radius turned out to be small —
`EnumResources.labelRes()`/`icon()` and one bulk-action label map required a new branch (exhaustive `when`s); every
other `when (careType)` in the codebase (`QuickLogUseCase.alreadyLoggedMessage()`, `AddCareLogViewModel
.duplicateErrorRes()`) already carries an `else` branch and is never reached with CHECK, since CHECK is never
offered as a manually-loggable type on `AddCareLogScreen` — it is only ever written by `QuickLogUseCase
.recordStillMoistCheck()`. `CareLogRepository`/`CareLogEntity`/`BackupManager` all store `careType` as a plain
string already, so **no schema or backup-version bump was needed** — a material cost advantage a dedicated table
would not have had (a new entity, DAO, migration, and backup section, for a value that is fundamentally "a care
event happened, and here is its outcome"). The CHECK log's `wateringFeedback` is always `TOO_SOON` — coherent here
even though ADR-... (see below) rules `TOO_SOON` incoherent as a *WATER*-log outcome, because a CHECK log is by
definition the not-watered branch.

CHECK entries appear in the plain care-history list (a real action was taken — arguably correct) but are
explicitly excluded from `WateringHistoryChart`'s data series/marker-color map and from its `CareEventDecoration`
marker computation (`computeCareEventMarkers()`) — a named, tested filter, not a silent fallthrough.

### The three-way feedback chip collapses to one optional flag

The soil-state chip (`TOO_SOON`/`JUST_RIGHT`/`TOO_LATE`, ADR-0009/ADR-0011) asked "How was the soil?" **after** a
watering was already logged — necessarily retroactive, and needing three answers, one of which was an admission
of a mistake. Under the check-first flow this issue introduces, the answer becomes the action instead of a chip:

| What the user does | Signal | Needs a chip? |
|---|---|---|
| Check → still moist, doesn't water | lengthen | no — it's the **Still moist** tap |
| Check → waters it | timing was right | no — it's the WATER log's own timestamp |
| Waters, plant was already stressed | shorten | yes — invisible to the app otherwise |

`TOO_SOON` becomes incoherent as a WATER-log outcome under a check-first flow: discovering wet soil means you
don't water, so a WATER log carrying "still wet" describes a mistake, not a schedule signal — the **Still moist**
action expresses that observation without the contradiction. `JUST_RIGHT` becomes redundant: watering when the
app said to *is* the confirmation, and the timestamp already records it. `TOO_LATE` is not derivable from anything
else and stays.

The WATER-log feedback control (`AddCareLogScreen` and `WaterFeedbackBottomSheet`) collapses to a single optional
flag — "Plant was dry / stressed" — that writes `TOO_LATE` when checked, `null` when unchecked. Nothing is
pre-selected. `WateringFeedback` keeps all three enum values (`TOO_SOON`/`JUST_RIGHT`/`TOO_LATE` — no migration,
no backup change); only the WATER-log-writing UI stops offering the first two as user choices. Existing logs
carrying `TOO_SOON`/`JUST_RIGHT` continue to load, display, and export unchanged via the standard
`runCatching { Enum.valueOf(...) }.getOrDefault(...)` convention.

### Gap-learning widened to nullable feedback (mandatory, not deferred)

The chip collapse makes `null` feedback the *dominant* case on WATER logs, where before this issue it was rare
(JUST_RIGHT was pre-selected). `CareSchedule.computeAdaptiveInterval()` (#568, technical ADR-0021) previously
required non-null feedback; shipping the chip collapse without widening it would have regressed the app from
"learns on most waterings" to "learns on almost none" — a regression this issue's own change would have caused,
not merely left unaddressed.

`feedback: WateringFeedback?` — `null` maps to a new `NEUTRAL_TARGET_MULTIPLIER` (1.00, same value as
`JUST_RIGHT`'s: with no chip, the observed gap is the whole signal). The gain applied to a null-feedback
observation is capped at a new `NEUTRAL_OBSERVATION_GAIN` (0.15) — expressed as `min(confidenceGain,
NEUTRAL_OBSERVATION_GAIN)`, a **ceiling on the existing confidence-driven gain**, not a second parallel learning
rate. Explicit (non-null) feedback keeps using the full gain unchanged. Confidence still updates normally on gap
agreement for a null-feedback observation — gap agreement is evidence about the schedule regardless of what was
tapped, and suppressing it would leave confidence permanently pinned for a user who never touches the flag. The
gain cap exists because null-feedback observations are new exposure to arbitrarily large gaps (a 30-day holiday
gap previously produced no learning at all; now it does, at a slower rate) — the existing ±40% per-step clamp
covers part of that risk, the gain cap covers the rest.

### Skip watering/Reschedule is rejected as a learning signal

Product ADR-0007 established that a watering deferral ("I'm away this weekend") is deliberately *not* the same
thing as "my plant needs less water" — conflating the two would drift the interval away from the plant's real
needs. That reasoning still holds, and this issue removes the only reason the deferral ever looked like a usable
proxy: "still wet" now has a dedicated, unambiguous action (Still moist) that means exactly one thing. Mining a
second signal out of a deferral would reintroduce the exact conflation ADR-0007 exists to prevent, in a world
where the clean signal already exists elsewhere. `SkipWateringReceiver` (and its internal `skipWatering()`, pulled
out of `goAsync()` for testability) touches only `Plant.wateringDueDateOverride` — never `wateringConfidence`,
`wateringIntervalDays`, or `wateringBaseIntervalDays` — and `SkipWateringReceiverTest` pins this so it is not
wired up later as an "obvious-looking" improvement.

## Consequences

- Reframing roughly doubles the number of learning events reaching the model (a watering *or* a still-moist check
  both now produce a signal), and the reminder is honest about what it asks the user to do.
- **Accepted limitation — the obedient-user blind spot.** A user who always waters exactly on schedule and never
  taps Still-moist gives the app no explicit "lengthen" signal beyond gap-learning agreement and the seasonal
  curve (#569). This is a genuine limitation of any model that cannot observe the soil directly, not a bug; two
  partial safety nets exist (observed-gap disagreement, and the seasonal curve's automatic autumn lengthening),
  but neither substitutes for an explicit signal from a perfectly punctual user.
- **Accepted limitation — Still moist is reachable only from the notification.** This issue specifies the check
  reframing on the reminder alone; `PlantDetailScreen` and `PlantListScreen` are untouched, so a user who opens
  the app instead of acting on the notification sees only "Log watering" and "Skip watering" and has no way to
  record "I checked, it was still moist". This was an omission in the issue spec, not in its implementation —
  every acceptance criterion was notification-shaped.

  It interacts badly with the rejection recorded above: in-app, the only non-watering action is the deferral,
  which is deliberately inert to the model, so the intuitive control is the one that teaches the app nothing.
  The rejection's justification — that "still wet" now has a dedicated action — holds only once that action is
  reachable in the app.

  Fixed in #508, which reworks the watering-due surface into three actions that each mean one thing
  (Water / Still moist / Reschedule). **`check_reminders` must not graduate out of developer mode before #508
  lands**, or the notification will offer a choice the app cannot.
- `CareType.CHECK` is a permanent addition to the enum and to every exhaustive `when (careType)` in the codebase;
  future care-type-driven features must remember it exists and decide whether to include or exclude it (as this
  issue did explicitly for the chart/calendar-marker code).
- No Room migration, no backup schema bump — `check_reminders` is purely behavioral, like `ADAPTIVE_WATERING` and
  `SEASONAL_WATERING`'s flag gating (though unlike those two, this feature adds no new columns at all, gated or
  not).
- ADR-0009 (already superseded by ADR-0011) is unaffected further. ADR-0011's "How was the soil?" question text is
  superseded — the question moves to the check, and two of its three answers cease to exist as a WATER-log
  choice. ADR-0016's three-way-chip clause (three `FilterChip`s, JUST_RIGHT pre-selected) is superseded; its 2-tap
  fast-path framing no longer applies verbatim since there is no longer a pre-selected chip to fast-path past.
  ADR-0024's deselectable-3-way-chip behavior is superseded by the single optional flag it's replaced with.
