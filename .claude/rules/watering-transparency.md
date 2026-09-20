---
description: "Why this date?" sheet, watering_adjustments table, ask-before-changing-intervals toggle
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/schedule/WateringExplanation.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/entity/WateringAdjustmentEntity.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/db/WateringAdjustmentDao.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/data/repository/WateringAdjustmentRepository.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/model/WateringAdjustment.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/plantdetail/WateringExplanationSheet.kt"
  - "app/src/test/**/WateringExplanation*.kt"
  - "app/src/test/**/*WateringAdjustment*.kt"
  - "app/src/test/**/MigrationTest11To12.kt"
---

# "Why this date?" watering transparency sheet (#572, product ADR-0028)

## Bug fix that gates everything else (also #572)
`PlantDetailViewModel.applySuggestedInterval()` (the product ADR-0006 dialog's Apply button — the only place
#568's adaptive suggestion is ever committed) now dual-writes `wateringBaseIntervalDays` alongside
`wateringIntervalDays`, mirroring `setWateringInterval()`'s existing manual-edit dual-write — see
`.claude/rules/seasonal-watering.md`'s "Interaction with Part 1" section for the read-side half
(`currentAdaptiveBaseIntervalDays`). Before this fix, applying a suggestion with `SEASONAL_WATERING` on
and the plant unpinned silently never moved the due date.

**Follow-up (#626):** the dual-write above only fixed `wateringBaseIntervalDays`; `applyIntervalInternal()`
was still writing the raw base-space `newInterval` straight into the *literal* `wateringIntervalDays`
field, even though every other read site (the dialog's "currently" figure, the Water tab slider/"every N
days" text, `WateringExplanationBuilder`) treats `wateringIntervalDays` as an effective, seasonally-adjusted
value — silently drifting the literal interval a little further on every single apply. `applyIntervalInternal()`
now runs `newInterval` through `CareSchedule.effectiveWateringIntervalDaysForDisplay()` (the same
conversion `pendingWateringSuggestion` already applies for display) before writing it, so
`wateringIntervalDays` and `wateringBaseIntervalDays` genuinely diverge from each other going forward
whenever `SEASONAL_WATERING` is on and the plant is unpinned — that divergence is now correct, not a bug.
The `DIALOG_EDIT` `WateringAdjustment` row's `afterIntervalDays` deliberately stays the raw base-space
`newInterval` (unchanged) — it's the model's base-space accounting, not the user-facing effective value,
and the two are meant to differ. `Event.SilentIntervalApplied` and `undoSilentIntervalApply()` both carry
the plant's actual prior `wateringIntervalDays`/`wateringBaseIntervalDays`, captured once inside
`applyIntervalInternal()` and threaded straight through — undo restores them as-is rather than
recomputing either (recomputing `wateringBaseIntervalDays` from `beforeIntervalDays` relied on
`beforeIntervalDays` coincidentally already being base-space, which stopped being true once this fix
landed).

**Follow-up (#631):** `CalendarViewModel.applySuggestedInterval()` and `PlantListViewModel
.applySuggestedIntervalFromList()` each carried their own independent copy of this write path and had
never received either the #572 or #626 fix above — they wrote the raw base-space suggestion straight
into the literal `wateringIntervalDays` and never touched `wateringBaseIntervalDays` at all. The fix
extracts the write path out of `PlantDetailViewModel.applyIntervalInternal()` into
`QuickLogUseCase.applyWateringIntervalSuggestion(plant, originalSuggestion, newInterval):
QuickLogUseCase.IntervalApplyResult` — the single choke point all three suggestion dialogs (Plant
Detail, Calendar, Plant List) now call, so this class of bug can't recur independently on any one
screen again. `PlantDetailViewModel.applyIntervalInternal()` is now a thin delegation to this function;
its own `Event.SilentIntervalApplied`/`undoSilentIntervalApply()` wrapping (the "ask before changing
intervals" flow below) stays Plant-Detail-specific, since Calendar/Plant List have no silent-apply/undo
equivalent and always show the product ADR-0006 dialog unconditionally. Math-correctness tests for the write
path itself live in `QuickLogUseCaseIntervalApplyTest`; each ViewModel keeps only a thin
delegation/smoke test verifying it calls the shared function with the right arguments.

**Follow-up (#644):** the three product ADR-0006 dialogs' editable text fields were pre-filled from the raw
base-space suggestion (`suggestedWateringInterval`/`QuickWaterSuggestion.suggestedInterval`) while the
dialog's own "Suggested: N days" sentence showed the *effective* (seasonally-converted) value from the
same suggestion — two different numbers presented as one "suggestion", and accepting the untouched field
silently re-derived a third number on Apply (the field's raw value re-run through the base→effective
conversion). `applyWateringIntervalSuggestion(plant, originalSuggestion, newInterval)`'s `newInterval`
parameter is now **effective-space**, matching what the field shows/submits — `PlantDetailScreen`'s
`intervalFieldText` is pre-filled from `pendingWateringSuggestion.effectiveIntervalDays`, and
`CalendarScreen`/`PlantListScreen`'s from `QuickWaterSuggestion.suggestedIntervalEffective`, all three
already-existing fields that previously drove only the dialog's sentence, never the box. The write path
inverted accordingly: `newInterval` is written to `wateringIntervalDays` **directly** (no re-conversion —
that used to be #626's fix, now folded into the derivation below), and `wateringBaseIntervalDays` is
derived from it via `SeasonalWatering.deseasonalize()` — the inverse of `effectiveWateringIntervalDaysForDisplay()`'s
multiplication, mirroring `PlantDetailViewModel`'s existing `deseasonalizedBaseOrNull()`/
`setWateringInterval()` manual-edit precedent — gated on the same `!plant.pinIntervalToBase && amplitude
!= 0.0` condition as before (#584 review round 2 still applies: pinned/zero-amplitude plants keep
`newInterval` as a literal and leave the stored base untouched). `originalSuggestion` is unchanged,
still base-space (this class's own raw adaptive suggestion) — `confidenceAfterDialogEdit()`'s tolerance
check now converts the effective `newInterval` back down to base-space once (`newIntervalBaseSpace`,
reused for both the base write and the `DIALOG_EDIT` row's `afterIntervalDays`) rather than converting
`originalSuggestion` up, since that conversion is already needed regardless of the confidence check.
`applySuggestionOrPrompt()`'s silent-apply branch (no dialog shown) now converts its
own raw suggestion through `effectiveWateringIntervalDaysForDisplay()` before calling
`quickLogUseCase.applyWateringIntervalSuggestion()`, mirroring `pendingWateringSuggestion`'s conversion, so a silent apply commits
the same number the dialog would have shown/pre-filled had it appeared. The `DIALOG_EDIT` row's
`afterIntervalDays` still deliberately stays base-space (unchanged posture from #626) — only the value
it's derived from changed.

**Follow-up (#654 review):** `QuickLogUseCase.adaptWateringInterval()`'s private
`deseasonalizedObservedIntervalDays()` helper (used to de-seasonalize an observed watering gap before
feeding it into the adaptive model) evaluated the season at `nowProvider()` (real wall-clock "now")
rather than the caller's `loggedAt`, so a backdated quick-water (#654's "Log watering" date picker) with
`SEASONAL_WATERING` on de-seasonalized using *today's* season, not the logged day's — contradicting the
"`loggedAt` threads through everywhere" claim documented above. Fixed by giving the helper an explicit
`atDate: LocalDate` parameter, which `adaptWateringInterval()` passes `now.toLocalDate()` into, mirroring
this section's own `loggedAt`-threading pattern. (At the time, the default `atDate = nowProvider()
.toLocalDate()` existed so `computeStillMoistAdaptiveInterval()`'s two still-moist callers — which had
no backdating concept — could keep using it unchanged; that function is deleted by #738, product
ADR-0039, leaving `adaptWateringInterval()` as this helper's one caller, always passing `atDate`
explicitly.) `effectiveIntervalForDisplay()` (display-only, feeds the product ADR-0006 suggestion dialog's
"different from current" check) had the identical bug and got the same fix via an explicit `now`
parameter threaded from `computeSuggestion()`.

**Follow-up (#679):** three more edge cases, all only reachable when backdating to before an
already-existing later watering. (1) `QuickLogUseCase.quickWaterWithReason()`/`quickLiquidFertilizeWithReason()`
called `clearWateringOverrideIfActive()` unconditionally after every WATER insert — backfilling an old,
forgotten watering from before an active reschedule silently discarded that unrelated reschedule. Both
functions now query `CareLogRepository.getLastTwoWaterings(plantId)` *before* the insert and only clear
the override when the new `loggedAt` is at or after the previous newest watering's `loggedAt` (`null` —
no prior WATER log — is treated as `Long.MIN_VALUE`, so a plant's first-ever WATER log still always
clears, matching the old unconditional behavior for that case). (2) `PlantDetailScreen`'s on/off-schedule
gate for the "Log watering" picker compared the chosen date against `PlantCareStatus.lastWateredAt`
(always "now"-relative) rather than that date's own chronological predecessor — the same class of bug
`computeSuggestion()` had already been fixed for above. A new `PlantDetailViewModel.previousWateringBefore(before)`
suspend wrapper around `CareLogRepository.getLastWateringBefore()` now feeds both `requestWater`/
`requestLiquidFertilize`'s gate and the subsequent `WateringReasonBottomSheet`'s gap-length wording,
fetched once per date-picker confirm via `rememberCoroutineScope().launch {}` and threaded through a
`PendingReasonPrompt(loggedAt, previousWateringAt)` so the two can't disagree. (3) See
`WateringLifecycleReset.maybeBootstrap()`'s `displayNow` fix below.

## `watering_adjustments` table (`data/entity/WateringAdjustmentEntity.kt`, `data/db/WateringAdjustmentDao.kt`,
`data/repository/WateringAdjustmentRepository.kt`)
A dedicated table, not a `CareLog` replay (product ADR-0028) — a dialog dismissal, a manual edit, or a
silently-applied suggestion all change `wateringConfidence`/base without ever writing a `CareLog` row,
so a pure replay would misrepresent history. `WateringAdjustmentTrigger` (`domain/model/WateringAdjustment.kt`):
`WATER_TOO_SOON`/`WATER_TOO_LATE`/`WATER_JUST_RIGHT`/`WATER_NEUTRAL`/`WATER_NOT_ATTRIBUTED` (from
`QuickLogUseCase.adaptWateringInterval()`/`AddCareLogViewModel.adaptWateringInterval()`, keyed off the feedback
param — `WATER_TOO_SOON` reachable since #649 (product ADR-0033) via the late-direction reason prompt's
`WateringReason.SOIL_STILL_MOIST` → `TOO_SOON` — plus
`AdaptiveInterval.excludedFromBaseLearning`, which wins and selects `WATER_NOT_ATTRIBUTED`: an
off-schedule watering the user declined to attribute, #586 product ADR-0030, distinct from `WATER_NEUTRAL`'s
on-schedule "nothing to change" so the sheet can explain a row where nothing moved), `CHECK_STILL_MOIST`
(**no longer written, as of #738, product ADR-0039** — a reschedule is model-neutral again and
`QuickLogUseCase.recordStillMoistAdaptiveObservation()` is removed; existing `CHECK_STILL_MOIST` rows
written before that change stay visible, rendering read-only in "Why this date?" → Recent adjustments,
since that surface is model provenance rather than a user journal — distinct from the `CareType.CHECK`
care-history filter, which does hide those rows elsewhere), `DIALOG_DISMISSAL`
(`PlantDetailViewModel.dismissSuggestedInterval()`, `before == after`), `DIALOG_EDIT`
(`QuickLogUseCase.applyWateringIntervalSuggestion()` — shared by the Plant Detail dialog's Apply
button/silent-apply path and the Calendar/Plant List dialogs, #631), `MANUAL_EDIT`
(`AddEditPlantViewModel.saveEdit()`, `PlantDetailViewModel
.setWateringInterval()`), and (#571) `REPOT_RESET`/`ROOM_CHANGE_RESET` (the lifecycle-reset triggers —
`WateringLifecycleReset.applyRepotReset()` and `AddEditPlantViewModel.saveEdit()`'s room-diff check;
`beforeIntervalDays == afterIntervalDays` always, since a reset changes confidence, not the interval
itself), `FROZEN_POST_REPOT` (a WATER/CHECK observation excluded from base-learning by the REPOT freeze
window — distinct from `WATER_NOT_ATTRIBUTED` so the sheet doesn't misrepresent an automatic freeze as
a declined attribution), and `HISTORY_BOOTSTRAP` (the one-time cold-start from watering history,
`WateringLifecycleReset.maybeBootstrap()` — its `seasonFn()` conversion uses a separate `displayNow`
parameter, not the (possibly backdated) `now` used for `triggeredAt`, see the #679 follow-up below). A
row is written **every time one of these is evaluated**,
including a no-op observation (`before == after`) — that's still evidence the model considered.
Unconditional now that `ADAPTIVE_WATERING` graduated (#655), matching where `wateringConfidence`
itself is written.
`WateringAdjustmentRepository.getRecentForPlant(plantId, limit)` is `ORDER BY triggeredAt DESC LIMIT
:limit` — same "collapse to N most recent" posture as care history (`PlantDetailViewModel
.RECENT_ADJUSTMENTS_LIMIT` = 5).

**Follow-up (#654):** Plant Detail's quick-water/quick-liquid-fertilize surfaces can now backdate a
log via a "Log watering" date picker (`.claude/rules/plant-detail.md`'s "Watering-due actions row"
section) rather than always logging "now". `QuickLogUseCase.quickWaterWithReason()`/
`quickLiquidFertilizeWithReason()` gained an explicit `loggedAt: Long = System.currentTimeMillis()`
parameter that replaces every internal `now = System.currentTimeMillis()` those two functions and
`computeSuggestion()`/`adaptWateringInterval()` used to compute independently — the same value now
drives the same-day duplicate guard, the `CareLog.loggedAt` write, `WateringLifecycleReset.isFrozen()`'s
freeze-window check, and the `WateringAdjustment.triggeredAt` this section documents, so a backdated
observation is evaluated (and its adjustment row dated) as of the day it claims to have happened on,
not the day it was actually entered — mirroring `WateringLifecycleReset.applyRepotReset()`'s existing
`resetAnchorMs` precedent ("not necessarily 'now', since a REPOT log can be backdated"). `hasLoggedToday()`
was widened from an implicit `System.currentTimeMillis()` to an explicit `dayTimestampMs` parameter for
the same reason. Every other call site of these two functions (`PlantListViewModel`, `CalendarViewModel`,
`QuickLogUseCase.bulkLog()`) is unaffected — they never pass `loggedAt`, so they keep using real "now" by
default.

**Follow-up (#679):** a backdated quick-water can trigger the #571/#662 cold-start history bootstrap
with `now` being the historical `loggedAt` rather than real wall-clock time — reintroducing #662's exact
staleness bug for that one path, since `Plant.wateringIntervalDays` is read everywhere else as a
*today*-effective value. `WateringLifecycleReset.maybeBootstrap()` gained a `displayNow: Long =
System.currentTimeMillis()` parameter used **only** for the `seasonFn()` conversion producing the
written `wateringIntervalDays`; `WateringAdjustment.triggeredAt`/`Plant.updatedAt` are unchanged, still
the historical `now`. `QuickLogUseCase.adaptWateringInterval()`/`maybeApplyHistoryBootstrap()` thread a
real `System.currentTimeMillis()` value down as `displayNow` alongside their existing (possibly
backdated) `now`; `AddCareLogViewModel`'s own copy of this helper has no backdating concept (its `now`
is already always real wall-clock time), so it's unaffected and keeps calling `maybeBootstrap()` without
a `displayNow` argument.

**Follow-up (#674):** `CalendarViewModel.dismissSuggestedInterval()` and `PlantListViewModel
.dismissSuggestedIntervalFromList()` both raised `Plant.wateringConfidence` via `CareSchedule
.confidenceAfterDismissal()` on a dialog dismissal but neither ever wrote the matching
`DIALOG_DISMISSAL` row — only Plant Detail's own copy of this logic did, so dismissing from Calendar
or Plant List silently changed confidence with nothing appearing in "Recent adjustments". Fixed by
extracting the confidence bump + row write into a new shared `QuickLogUseCase
.recordWateringSuggestionDismissal(plant): Plant`, mirroring the #631 consolidation of
`applyWateringIntervalSuggestion()` for the sibling "apply" action on the same dialog — the same
base-space row shape (`beforeIntervalDays == afterIntervalDays`, via `currentAdaptiveBaseIntervalDays()`,
guarded on `plant.wateringIntervalDays != null`). All three dismiss-suggestion call sites
(`PlantDetailIntervalActions.dismissSuggestedInterval()`, `CalendarViewModel
.dismissSuggestedInterval()`, `PlantListViewModel.dismissSuggestedIntervalFromList()`) now delegate to
this one function; Plant Detail's own wrapper keeps only its ViewModel-scoped bits (reading the current
`plant`, clearing `suggestedWateringInterval`).

**Follow-up (#714), superseded by #738 (product ADR-0039) — kept as history, not current behaviour:**
`QuickLogUseCase.recordStillMoistCheck()`'s same-day `CareType.CHECK` duplicate guard used to return
early *before* writing `Plant.wateringDueDateOverride` at all, so a second same-day "Soil still moist"
reschedule silently discarded the date the user just picked. The duplicate branch was fixed to commit
the override, skipping only the CHECK log / adaptive observation / `WateringAdjustment` row it would
otherwise duplicate. **This entire function, its duplicate guard, and the branch described here are
deleted by #738** — a reschedule no longer writes a `CareType.CHECK` log at all, so there is nothing
left to guard against re-logging. `QuickLogUseCase.recordReschedule(plant, newDueAtMillis)` is the
current write path: an unconditional column write with no duplicate check of any kind. The reworded
`quick_log_already_checked` string this follow-up introduced is deleted along with it.

**Follow-up (#714 review round 1) — the DAO method survives, the rationale for *why* it's used has
changed:** the duplicate branch above did *not* use a full-row `updatePlant()` — two overlapping
`StillMoistReceiver` deliveries (e.g. a doubled broadcast) could interleave, so a duplicate-branch write
built off a `plant` snapshot taken before the other delivery's own `updatePlant()` call could silently
revert that write's `wateringConfidence` (or any other column). It instead called a new column-specific
`PlantDao.updateWateringDueDateOverride(id, wateringDueDateOverride, updatedAt)` (mirrored by a thin
`PlantRepository` wrapper) — same rationale as `PlantDao.updateWateringBaseInterval` (#703 review round
3): a statement that can't touch a column it doesn't name eliminates the race entirely rather than just
narrowing its window. **That DAO method is not deleted by #738** — `recordReschedule()` still calls it,
now simply because it's the correct minimal-surface write for a reschedule, not because of a
duplicate-delivery race that no longer has a guard branch to protect. The success path's single combined
`updatePlant()` call (#612) is unrelated and unaffected either way — that invariant only ever governed
the WATER-log adaptive-observation path, which still writes confidence and an override together when
relevant.

**Follow-up (#715), superseded by #738 (product ADR-0039) — kept as history, not current behaviour:**
`recordStillMoistAdaptiveObservation()` used to write the `CHECK_STILL_MOIST` (and `FROZEN_POST_REPOT`)
row's `afterIntervalDays` as `result.intervalDays` — the value the adaptive model computed but that this
path deliberately never wrote to the plant (only `Plant.wateringConfidence` was persisted there).
Whenever that rounded to something different from `beforeIntervalDays`, the sheet's Recent adjustments
list claimed an interval change ("7 → 8 days") that was never applied. Fixed per the chosen posture
(Option A over a distinct "considered, not applied" wording): `afterIntervalDays` was made to always
write as `currentBase`, so the row rendered "unchanged" for both trigger branches uniformly.
**`recordStillMoistAdaptiveObservation()`, `computeStillMoistAdaptiveInterval()`, and
`suggestedStillMoistDeferralDays()` are all deleted by #738** — a reschedule no longer evaluates the
adaptive model at all, so there is no `WateringAdjustment` row left for this fix to apply to. Existing
`CHECK_STILL_MOIST` rows written before #738 keep whatever `afterIntervalDays` this fix gave them
(read-only history, per the `CHECK_STILL_MOIST` entry above); no new row of that trigger is ever
written again.

**Schema**: `MIGRATION_11_12`, `PlantDatabase.DB_VERSION` 11→12, `app/schemas/.../12.json`. `.yapt`
backup schema v12→v13: `BackupRoot.wateringAdjustments: List<BackupWateringAdjustment>` (default
`emptyList()`) + `BackupSettings.askBeforeChangingIntervals: Boolean` (default `true`) — see
`.claude/rules/backup.md`.

## "Ask before changing intervals" (`SettingsKeys.ASK_BEFORE_CHANGING_INTERVALS`, default `true`)
A plain settings key, not a `FeatureFlagRegistry` entry — survives disabling developer mode. Always
consulted (`PlantDetailViewModel.shouldShowIntervalDialog()`) — `ADAPTIVE_WATERING` graduated (#655),
so the confidence-weighted model and this toggle are both unconditional now.
- **On** (default): today's product ADR-0006 `AlertDialog`, byte-for-byte unchanged.
- **Off**: `applySuggestionOrPrompt()` (an extension on `PlantDetailViewModel` in
  `PlantDetailIntervalActions.kt`, #641) calls `quickLogUseCase.applyWateringIntervalSuggestion()`
  directly (same dual-write, logged as `DIALOG_EDIT`) and emits `Event.SilentIntervalApplied(beforeIntervalDays,
  beforeBaseIntervalDays, afterIntervalDays)` — `afterIntervalDays` is the effective value actually
  written (#626), not the raw suggestion; `before*` are the plant's actual prior values, not derived.
  `PlantDetailScreen` shows a `Snackbar` (`R.string.interval_auto_applied_snackbar` +
  `R.string.snackbar_undo`, mirroring `PlantListScreen`'s archive-undo convention) whose action calls
  `undoSilentIntervalApply(beforeIntervalDays, beforeBaseIntervalDays)` — a plain revert restoring both
  captured values as-is, not a new `WateringAdjustment` row.
- Settings UI row lives on the main Settings screen (not Developer section), always visible now that
  `ADAPTIVE_WATERING` graduated (#655).
- The `AddCareLogScreen` save-flow suggestion (routed through `NavGraph`'s `savedStateHandle`) goes
  through the same toggle via `PlantDetailViewModel.handleSuggestedWateringInterval()` — `NavGraph`
  never sets `vm.suggestedWateringInterval.value` directly anymore.

## The sheet (`ui/screens/plantdetail/WateringExplanationSheet.kt`)
Entry point: a "Why this date?" `TextButton` (`testTag("why_this_date_button")`) in the Water tab's
inline-settings card (product ADR-0023), visible whenever `plant.wateringIntervalDays != null`. Content
built by `WateringExplanationBuilder.build()` (`domain/schedule/WateringExplanation.kt`) — a pure
function that takes `nextWateringDueAt`/`lastWateredAt` from `PlantCareStatus` (already computed by
`CareSchedule.computeStatus`) rather than recomputing them, and calls the same
`CareSchedule.effectiveWateringIntervalDaysForDisplay()` public wrapper the due-date math itself uses
for "Watering every N days" — the sheet's numbers cannot drift from the schedule by construction.
`PlantDetailViewModel.wateringExplanation: StateFlow<WateringExplanation?>` combines `plant`,
`careStatus`, `waterLogCount` (derived from `careLogs`), `seasonalAmplitudeValue`, and
`recentWateringAdjustments`. `ADAPTIVE_WATERING` graduated (#655) — `baseIntervalDays`/`confidenceLevel`
are always populated now; there is no flag-off degraded state to gate on anymore.
- Season row (`WateringExplanationSeason`) shown only when `seasonalAmplitude != 0.0 &&
  !plant.pinIntervalToBase` — hidden entirely (never `× 1.00`) when amplitude is Off or pinned.
  `SeasonBand` (`SLOWER_GROWTH`/`FASTER_GROWTH`/`TRANSITIONAL`) is a 3-way bucket of the outer/middle
  thirds of `[1-amplitude, 1+amplitude]` — "one string per band, not per month" per the issue.
- `WateringConfidenceLevel` (`STILL_LEARNING`/`GETTING_THERE`/`DIALED_IN`, buckets of `wateringConfidence`
  0-1/2-3/4-5) is the sheet's accessible content for confidence — the dots (`ConfidenceDots`) are
  decorative Compose `Box`es with no semantics of their own; the label `Text` is what a Compose UI test
  must assert (#420 — never dot count via tree structure).
- Recent adjustments: date via `DateUtils.formatRelative()`, trigger via `WateringAdjustmentTrigger
  .labelRes()` (`ui/util/EnumResources.kt`), and `before → after` (or "unchanged" when equal).
- Deliberately textual/numeric only — does not embed `SeasonalWateringCurveChart` (#579), which lives
  one card away in the same inline-settings card and shows only the multiplier curve, no day-based
  numbers.
- **Reschedule delta row (#630):** `WateringExplanation.rescheduleDeltaDays` mirrors
  `PlantCareStatus.rescheduleDeltaDays` verbatim (populated unconditionally,
  same as `nextWateringDueAt`/`lastWateredAt`) — a plain `ExplanationRow` using the same
  `watering_reschedule_delta_days` plural as the actionable chip outside the sheet. Display-only, no
  tap target here — see `.claude/rules/plant-detail.md`'s "Reschedule delta chip + revert" for the
  chip/revert flow itself.
