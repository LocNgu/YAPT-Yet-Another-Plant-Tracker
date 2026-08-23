# Product ADR-0028: "Why this date?" watering transparency sheet

**Status**: accepted

**Date**: 2026-08-23

**Supersedes**: ADR-0006 (for the "Ask before changing intervals" toggle's off state only — the dialog remains the
default, on-by-default behavior)

## Context

Split from #285, part 5 of 5 (depends on #568/#569). The adaptive + seasonal watering model (#568/#569) was chosen
partly because every quantity in it is either a stored value or one line of arithmetic from stored values — "no
inferred narrative, no invented explanation." This issue is where that design choice pays off: a sheet that shows
exactly how the next watering date was derived, plus a log of recent automatic adjustments, so silent model
behavior never becomes a black box.

Three things needed deciding, and one pre-existing bug needed fixing before the sheet could honestly show numbers
that matched what actually drove the due date:

1. **A shipped bug**: `PlantDetailViewModel.applySuggestedInterval()` (the ADR-0006 dialog's Apply button) wrote
   only `Plant.wateringIntervalDays`. Once `SEASONAL_WATERING` is on and the plant isn't pinned,
   `CareSchedule.effectiveWateringIntervalDays()` reads `Plant.wateringBaseIntervalDays` instead — so applying a
   suggestion silently did nothing to the actual due date. Every `computeAdaptiveInterval()` call site had the
   mirror-image bug: feeding `Plant.wateringIntervalDays` (stale once season is on) as `currentBaseIntervalDays`
   instead of the live season-neutral value.
2. **What "Recent adjustments" derives from.** The issue leaned toward deriving the list from `CareLog` replay.
   Rejected: a dialog dismissal, an inline/AddEditPlant manual edit, and (new in this issue) a silently-applied
   suggestion all change `wateringConfidence`/base without ever writing a `CareLog` row. A pure replay would
   silently misrepresent history — exactly what the sheet exists to prevent.
3. **What "Pin interval" actually pins**, settled from the shipped #569 code rather than re-decided: it disables
   only the seasonal multiplier. A pinned plant's due date reads `Plant.wateringIntervalDays` literally. It does
   **not** pause adaptive learning — confidence and the applied suggestion keep updating normally, they just never
   get seasonally adjusted.
4. **The auto-apply toggle.** The issue proposed an "auto-apply above/below N%" threshold, then reconsidered: no
   magic number to defend, and the user decides rather than the algorithm. Landed as a plain on/off setting instead.

## Decision

### 1. Fix the reconciliation bug (prerequisite, not deferred)

`applySuggestedInterval()` adopts the same dual-write `setWateringInterval()` already uses for manual edits: when
`SEASONAL_WATERING` is on and the plant isn't pinned, also de-seasonalize the applied value into
`wateringBaseIntervalDays`. A new `currentAdaptiveBaseIntervalDays(plant)` helper (duplicated per-class, mirroring
the existing `deseasonalizedObservedIntervalDays()` precedent — see technical ADR-0021's amendment below) replaces
the raw `Plant.wateringIntervalDays` read at all four `computeAdaptiveInterval()` call sites.

### 2. `watering_adjustments` is a new table, not a `CareLog` replay

`WateringAdjustmentEntity(id, plantId, triggeredAt, trigger, beforeIntervalDays, afterIntervalDays)` — one row per
adaptive-model evaluation while `ADAPTIVE_WATERING` is on, including a no-op `JUST_RIGHT`/neutral observation
(`before == after`) — that's still evidence the model considered. `WateringAdjustmentTrigger` enumerates every
site that writes a row: the three feedback flavors, a silent (`null`-feedback) observation, a Still-moist check, a
dialog dismissal, a dialog apply, and a manual edit. Ships unconditionally regardless of the `adaptive_watering`
flag's state (rows are only ever written while the flag is on, mirroring `wateringConfidence`'s precedent) —
toggling the flag off/on never loses history that's already been recorded.

### 3. Pin's scope is documented, not changed

The sheet reflects what the shipped code already does: a pinned plant still shows Base interval / "learned from N
waterings" / confidence / adjustments — all live — it just never shows the season row, and "Watering every N days"
equals the base interval verbatim (no arithmetic to display).

### 4. "Ask before changing intervals" — a real setting, not a dev-mode flag

`SettingsKeys.ASK_BEFORE_CHANGING_INTERVALS: Boolean`, default `true`, survives disabling developer mode. Only
consulted when `ADAPTIVE_WATERING` is on — inert otherwise, so the ADR-0006 dialog stays the unconditional
behavior for anyone not on the adaptive model.

- **On** (default): byte-for-byte the ADR-0006 `AlertDialog` behavior.
- **Off**: no dialog. The suggestion applies immediately (same dual-write, logged as a `DIALOG_EDIT`-flavored
  adjustment row) and a `Snackbar` offers "Undo," reverting to the row's `beforeIntervalDays`.

This supersedes ADR-0006 for its off-state only. ADR-0006's rationale — "interval changes are infrequent and
consequential" — still holds for the on/default path; it weakens once the adaptive model makes small changes
frequent and low-stakes, which is exactly the case the toggle's off-state addresses. The adjustments list (#2
above) is what makes silent apply acceptable: nothing happens that the user can't go and see, with an undo
available at the point it happens.

### 5. The sheet itself

Reachable from the Plant Detail Water tab's existing inline-settings pattern (product ADR-0023) — a "Why this
date?" button, not a new screen. Textual/numeric only, matching the issue's own mockup: next watering date, base
interval + "learned from N waterings," season row (multiplier + a 3-band reason string — "one string per band, not
per month," per the issue), a divider, the effective interval, last watered, confidence (a labelled indicator —
dots decorative, the bucket label is the accessible content, per #420), and recent adjustments. `ADAPTIVE_WATERING`
off degrades to just the next-watering-date and plain-interval rows — no base/season/confidence/adjustments
invented. Deliberately does not embed #579's seasonal-curve chart (different content, one card away already).

## Consequences

- Every number the sheet shows is either read straight from `Plant`/`WateringAdjustment` or produced by the same
  `CareSchedule` function the due-date math itself calls (`effectiveWateringIntervalDaysForDisplay`, a public
  wrapper around the previously-private `effectiveWateringIntervalDays`) — the sheet cannot drift from the
  schedule by construction, not just by convention.
- The reconciliation bug fix changes real behavior for any plant with both `ADAPTIVE_WATERING` and
  `SEASONAL_WATERING` on: applying a suggestion (via the dialog, or via the new silent-apply path) now actually
  moves the due date, where it silently didn't before. This is a correctness fix, not a new feature, but it does
  change what happens on already-shipped flag combinations.
- A new table means a new migration (`MIGRATION_11_12`, DB v12) and a new backup schema bump (v13) — accepted cost
  for the alternative (a replay that can silently lie).
- `WateringAdjustmentRepository`/DAO follow the existing repository pattern exactly (entity↔domain mapping,
  `runCatching` enum decode) — no new architectural pattern introduced.
