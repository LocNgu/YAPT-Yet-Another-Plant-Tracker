---
description: Plant Detail — hero layout, per-action tabs, inline settings, insights, photo gallery
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/plantdetail/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/domain/insights/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/components/PhotoGallery*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/components/FullScreenPhotoViewer*.kt"
---

# Plant Detail rules

## Layout — Box overlay, NOT Scaffold (technical ADR-0018, supersedes ADR-0005)
280 dp hero photo bleeds behind the status bar; `Box` overlay with overlaid back/edit pill buttons;
`Surface(colorScheme.background)` root for correct dark-mode text. The outer `Scaffold` in `YaptNavGraph` sets
`contentWindowInsets = WindowInsets(0)` so it doesn't double-reserve the status-bar inset here (#29). Tapping the
hero opens `FullScreenPhotoViewer`; the no-cover placeholder has no clickable modifier (#307).

## Per-action tabs — behind a feature flag (#436)
The whole tabs feature (tab strip + inline settings + per-tab insights) sits behind
`FeatureFlagRegistry.PLANT_DETAIL_TABS` (`plant_detail_tabs`, default **off**). `PlantDetailViewModel` exposes
`tabsEnabled: StateFlow<Boolean>`; the screen branches — **on** = `PlantDetailTabStrip` (a `FlowRow` of standalone
`Tab`s, not `TabRow`/`PrimaryTabRow` — see below) as a `LazyColumn` item inside the Box overlay below the hero;
**off** = classic single-page layout. The shared care-history list and `+` FAB render in **both** paths; `StatsRow`
does not — see "Tappable stat chips" below (#603).
> Per ADR-0022's flag-lifecycle rule: when this graduates, delete the registry entry **and** the flag-off branch in
> the graduating PR.

- `PlantDetailTab` enum (6 entries: `WATER, FERTILIZE, REPOT, PHOTO, CUSTOM_REMINDERS, ISSUES`) = per-tab `labelRes`
  + icon; `selectedTab` is `rememberSaveable` (defaults Water).
- Per-tab filtered log lists use prefixed keys (`"fert-"`/`"repot-"`/`"mist-"` + id) so they never collide with the
  shared list's `it.id` keys. Misting is folded into the Water tab.

### Tab row collapse/expand + attention badge (product ADR-0030, #590)
Six tabs don't fit one row at each tab's current fixed width without either shrinking every tab or scrolling
horizontally, so `PlantDetailTabStrip` uses a `FlowRow` of individually-sized `Tab` composables
(`Modifier.fillMaxWidth(0.25f)` each, no `TabRow`/`PrimaryTabRow` wrapper) instead. Collapsed (default) shows only
`PlantDetailTab.entries.take(4)` — today's Water/Fertilize/Repot/Photo, same width/layout as before; expanded shows
all 6, with `CUSTOM_REMINDERS`/`ISSUES` wrapping onto a second row at that same per-tab width.
- `var isTabRowExpanded by rememberSaveable { mutableStateOf(false) }` — screen/session-local like `selectedTab`
  and the care-history `isExpanded` chip, **not** a `DataStore` setting; resets to collapsed on every fresh visit.
- Toggle reuses the care-history `AssistChip`'s exact chevron-rotate pattern (`animateFloatAsState` rotating
  `Icons.Filled.ExpandMore` 180°), with a `contentDescription` that flips between
  `plant_detail_tabs_expand_cd`/`plant_detail_tabs_collapse_cd`/`plant_detail_tabs_expand_attention_cd` (collapsed
  **and** `hasAttention` — folds the "something needs attention" signal into the announced text since the badge
  itself, a bare `Badge` dot, carries no `contentDescription` of its own, #591).
- Attention `Badge` on the toggle when **collapsed** and (`activeIssues.isNotEmpty()` or any
  `CustomReminderStatus.isOverdue`) — both already-collected in `PlantDetailScreen.kt`, no new queries. Hidden once
  expanded.
- Collapsing while `selectedTab` is `CUSTOM_REMINDERS`/`ISSUES` (now hidden) resets `selectedTab` to `WATER`.
- **Selection indicator (#591):** a standalone `Tab()` outside `TabRow`/`PrimaryTabRow` draws no indicator of its
  own — `PrimaryIndicator` is drawn by `TabRow` itself as a separate overlay positioned from real `TabPosition`s,
  unavailable here — and `Tab()`'s `unselectedContentColor` defaults to `selectedContentColor` when neither is
  passed, so the selected/unselected tabs would otherwise render identically. Each `Tab` is given explicit
  `selectedContentColor`/`unselectedContentColor` (`colorScheme.onPrimaryContainer`/`onSurfaceVariant`) plus a
  `colorScheme.primaryContainer` rounded-background (`RoundedCornerShape(12.dp)`, else transparent) scoped to that
  one `Tab`'s own `Modifier` — works per-tab regardless of which row (collapsed or expanded) it wraps onto, unlike
  a shared `TabRow` indicator which needs one `TabPosition` list across the whole row.

## Inline scheduling settings (product ADR-0023 — a new decision, not a supersession)
Water/Fertilize tabs each show an editable `Card` (interval enable `Switch` + `Slider`; Fertilize adds the
liquid-fert toggle). Edits **auto-persist** (no Save button) via `setWateringInterval(Int?)` /
`setFertilizingInterval(Int?)` / `setLiquidFertilizer(Boolean)` → `PlantRepository.updatePlant`; `null` clears the
schedule. Slider commits on release (`onValueChangeFinished`). Shared `InlineIntervalSetting` composable; defaults
`DEFAULT_WATERING_INTERVAL_DAYS`/`DEFAULT_FERTILIZING_INTERVAL_DAYS` = 7/30. Add/Edit Plant stays the canonical
editor for name/species/room/notes/cover.

## Per-tab insights (#436)
`domain/insights/CareInsights.summarize(logs, careType)` → `CareTypeSummary(count, lastAt, averageIntervalDays)`
(mean of consecutive calendar-day gaps via `CareSchedule.daysBetween`, rounded, floored at 1). Photo tab uses
`summarizePhotos(galleryPhotos)` → `PhotoSummary`. JVM-tested (`CareInsightsTest`). Shared `TabInsightsCard` +
`careTypeInsightItems(...)` live in `PlantDetailScreen.kt`.

## Repot and Photo tab quick actions (#658)

With `PLANT_DETAIL_TABS` on, Repot and Photo each start with an always-visible filled action button,
using a leading tab-matching icon and the same 16dp horizontal padding as Water's primary action.
Repot delegates to `PlantDetailViewModel.quickRepot()` → `QuickLogUseCase.quickLog(REPOT)`, preserving
the shared repot confidence-reset/freeze side effect and the existing rule that REPOT is not guarded
against same-day duplicates. Photo navigates to `AddCareLogScreen` with `CareType.PHOTO` preselected,
removing the care-type selection step while keeping image picking, date, notes, and cover-photo updates
in the canonical add-log flow. Neither action renders in the classic flag-off layout. Custom Reminders
and Issues retain their existing add/report controls; no extra duplicate actions are added there.

## Tappable stat chips (#434) — classic layout only (#603)
Watering/Fertilizing `StatChip`s (in `StatsRow`) take optional `onWaterClick`/`onFertilizeClick` (with
`clickable(onClickLabel=…)` for a11y). Water logs directly when on schedule, else opens
`WateringReasonBottomSheet` → `quickWater(reason)`; Fertilize → `quickFertilize()` (regular) or the same
reason-gated path → `quickLiquidFertilize(reason)` (liquid-fert, whose paired WATER log follows the same
rule). All delegate to the shared `QuickLogUseCase`, feed the adaptive suggestion into the
`suggestedWateringInterval` dialog, and emit a `QuickLogMessage`.

**`StatsRow`'s placement is layout-dependent (#603):** in the **classic layout** it stays exactly as before,
an always-visible summary above the (absent) tab strip, unchanged. In the **tabs layout** it is removed
entirely — its watering `StatChip` became a redundant second control once `WateringDueActionsRow`'s Water
button became always-visible (see below), and its fertilizing `StatChip` is replaced by
`FertilizeDueActionRow` (`WateringDueActions.kt`), a single always-visible `OutlinedButton` rendered under
the Fertilize tab, gated on `plant?.fertilizingIntervalDays != null` (mirroring `WateringDueActionsRow`'s
own `wateringIntervalDays` gate) — not on due status, same as the `StatChip` it replaces. It has no
"reschedule" counterpart since fertilizing has no equivalent concept. `careTypeInsightItems(...)`'s
`lastAtLabel` is populated (`R.string.insight_last_watered` / `R.string.insight_last_fertilized`) for
both Water/Fertilize tabs, restoring the "last done" display `StatsRow` used to show above the tab strip
(round-2 fix, #603) — it is no longer `null` there.

## Watering-due actions row: Water / Reschedule watering (#586, product ADR-0030; always-visible since #603)
`WateringDueActionsRow` (`WateringDueActions.kt`) renders **two** buttons in one row — narrowed
from #508's three (ADR-0029) — in both the classic layout and the Water tab, gated only on
`plant?.wateringIntervalDays != null` (**not** on due status — #603 dropped the earlier `status.isOverdue
|| status.isDueSoon` clause, since "Reschedule" had no other entry point and was otherwise unreachable
before the plant's due date). "Did water go in, or not?" is a fact, not a judgement; *why* is asked
afterwards, and only when the action is off schedule.

**Styling (#603 round-3 visual polish):** Water is a filled Material3 `Button` (`colorScheme.primary`,
no hardcoded color — resolves to `SageGreen`/`SageGreenLight` in `Theme.kt`) with a leading
`Icons.Filled.WaterDrop` icon ahead of its text, `Modifier.weight(1f)`. Reschedule watering is an
icon-only `OutlinedIconButton` (`Icons.Filled.MoreTime`, no visible text — `contentDescription` reuses
`R.string.reschedule_watering_title`), sized to its own content so Water's `weight(1f)` takes the rest
of the row. Compose UI tests locate the Reschedule button via `onNodeWithContentDescription`, not
`onNodeWithText`, since it has no visible label (`PlantDetailScreenTest.kt`).

The row uses plain `padding(horizontal = 16.dp)`, same as every other card on the screen (#610,
technical ADR-0022) — an earlier fix (#604) widened this to `88.dp`/`64.dp` trailing/leading insets to
keep the row's clickable bounds clear of the *permanently pinned* Back icon button (top-left), Edit icon
button (top-right), and "Log care" FAB (bottom-right, all Box-overlay buttons per technical ADR-0018)
whenever the row (first item under its tab) scrolled flush against a screen edge, but that traded away
visual consistency with every sibling card for a worst-case-sized buffer paid at every scroll position.
ADR-0022 instead fades the Edit button out once the user has scrolled substantially past the hero photo
— see "Edit button scroll fade" below — so the row's own margins could revert to normal. The residual
collision risk with Back/FAB is a deliberate, accepted trade-off (ADR-0022), not an oversight; do not
reintroduce a smaller "just in case" inset here without a new decision. `FertilizeDueActionRow` uses the
same plain `16.dp` padding for the same reason.

### Edit button scroll fade (technical ADR-0022)
The pinned Edit `IconButton` (`PlantDetailScreen.kt`'s Back/Edit `Row`, `Alignment.TopStart` in the Box
overlay) fades out (`AnimatedVisibility` + `fadeIn()`/`fadeOut()`, matching the `PlantDetailTabStrip`
chevron's `animateFloatAsState` fade/rotate convention) once the `LazyColumn`'s named `LazyListState`
reports `firstVisibleItemIndex > 0` — i.e. the 280dp hero photo (item index 0) has fully scrolled out of
the viewport. `AnimatedVisibility` removes the button from composition (not just alpha) once its exit
animation finishes, so it stops being clickable and disappears from the semantics tree, not just
visually. **Back stays exactly as before** — always pinned, never fades, no visibility logic — and so
does the "Log care" FAB, since persistent visibility across scrolling is the whole point of a FAB. Edit
becomes unreachable via its icon once scrolled past the hero, with no alternative on-screen entry point
today — a real, if narrow, functional regression accepted in ADR-0022.

**Placement in the tabs layout (#603 round-3):** the actions row (and `FertilizeDueActionRow` on the
Fertilize tab) now renders **before** the `InlineIntervalSetting` card on its tab, not after — actions
row → interval card → per-tab insights card. Classic layout has no inline interval settings (ADR-0023 is
tabs-only), so its row position is unchanged.

- **Water** — on schedule, logs immediately (`quickWater(reason = null)`, the fast path); off schedule,
  opens `WateringReasonBottomSheet`, whose two-chip option set is direction-specific (#649, product
  ADR-0033): early ("Why now?" → "The plant needed it" / "Just my timing") vs. late ("Why was it late?"
  → "Soil was still moist" / "Forgot, or no time") — a late gap never offers a shorten attribution. The
  `requestWater`/`requestLiquidFertilize` helpers at the bottom of `PlantDetailScreen.kt` own that
  branch, shared with the classic layout's tappable `StatChip`s and the tabs layout's
  `FertilizeDueActionRow` so no surface can disagree. On the Water tab, this "Water" button **always**
  calls plain `requestWater()`, regardless of `Plant.useLiquidFertilizer` — it never branches (#652).

**Combined Water + Fertilize action on the Water tab (#652):** for a liquid-fertilizer plant
(`plant?.useLiquidFertilizer == true`), a second, visually distinct `OutlinedButton`
(`CombinedWaterFertilizeActionRow`, `WateringDueActions.kt`,
`WATERING_DUE_COMBINED_WATER_FERTILIZE_BUTTON_TEST_TAG`) renders directly below
`WateringDueActionsRow`, wired to `requestLiquidFertilize()`/`showLiquidFertilizeSheet` — the same
combined path `FertilizeDueActionRow` uses — so a liquid-fertilizer plant owner doesn't have to switch
to the Fertilize tab to log the one action they take every time they water. It is additive, not a
replacement: the plain "Water" button stays present and unchanged next to it. Absent entirely for a
non-liquid-fertilizer plant. `FertilizeDueActionRow`'s own button is relabeled "Water + Fertilize"
(shared string `R.string.water_fertilize_combined_button`) under the same `useLiquidFertilizer`
condition, for consistency with the new Water-tab button — its `onClick` behavior was already correct
and unchanged.
- **Reschedule watering** — `requestReschedule()` opens `RescheduleReasonBottomSheet` ("Why put it
  off?" → "Soil still moist" / "I can't right now") **first**; `chooseRescheduleReason()` then opens
  `RescheduleWateringDialog`. Dismissing the reason sheet abandons the reschedule entirely.

**Follow-up (#654):** a plain tap on Water/the combined action no longer logs immediately even when
on schedule — every quick-water entry point (`WateringDueActionsRow`'s Water button in both layouts,
the classic-layout watering `StatChip`, and `CombinedWaterFertilizeActionRow`/`FertilizeDueActionRow`'s
liquid-fert path) first opens `LogWateringDatePickerDialog` (`LogWateringDatePicker.kt`, not-future-only
via `SelectableDates`, pre-selected to today, distinct from `RescheduleWateringDialog`'s custom date —
that one sets `wateringDueDateOverride` on the *next due date*, this one backdates the *logged event*
itself). Confirming with today selected reproduces the old instant-log fast path in one extra confirm
tap; picking an earlier date backfills a forgotten watering. `requestWater`/`requestLiquidFertilize`
now take the picked `loggedAt` and re-evaluate on/off-schedule against **that** date (`CareSchedule
.isWateringOnScheduleAt`/`isWateringGapLongAt`, public wrappers around the same `wateringOnScheduleNow`/
`wateringGapRanLong` comparisons `PlantCareStatus.isWateringOnSchedule`/`isWateringGapLong` already use
against real "now") rather than the plant's precomputed `careStatus`, which is always "now"-relative —
picking today reproduces `careStatus`'s own result exactly, since the underlying gap comparison is
calendar-day granular. `quickWater()`/`quickLiquidFertilize()` and `QuickLogUseCase
.quickWaterWithReason()`/`quickLiquidFertilizeWithReason()` all gained an explicit `loggedAt: Long =
System.currentTimeMillis()` parameter threading through the duplicate-day guard, the `CareLog` write,
and the adaptive-gap math consistently — see `.claude/rules/watering-transparency.md` for why that one
value can't be allowed to drift across those three. Plain (non-liquid) `quickFertilize()` is unchanged
— no date picker, since fertilizing alone has no adaptive-interval/reason-prompt concept for a chosen
date to feed into.

**Review round 1 fix (#654 PR #671):** `loggedAt` threading missed one spot — `QuickLogUseCase
.adaptWateringInterval()`'s call to its private `deseasonalizedObservedIntervalDays()` helper still
evaluated the season at `nowProvider()` (real wall-clock "now") instead of the caller's backdated
`loggedAt`, so a backdated quick-water with `SEASONAL_WATERING` on de-seasonalized the observed gap
using *today's* season factor, not the logged day's. Fixed by adding an explicit `atDate: LocalDate`
parameter (default `nowProvider().toLocalDate()`, so `computeStillMoistAdaptiveInterval()`'s two
callers — which have no backdating concept — are unaffected) that `adaptWateringInterval()` now passes
`now.toLocalDate()` into. `effectiveIntervalForDisplay()` (display-only, feeds the ADR-0006 suggestion
dialog's "different from current" check) had the identical bug and got the same fix via an explicit
`now` parameter threaded from `computeSuggestion()`. `QuickLogUseCaseSeasonalTest`'s pre-existing
adaptive-path calls to `quickWaterWithReason()` had to start passing `loggedAt = peakDay` explicitly to
keep matching their pinned `nowProvider` — they previously relied on the pre-fix code silently reading
`nowProvider()` for season while `loggedAt` (unpassed, defaulting to the real device clock) drove
everything else, which the fix correctly stopped tolerating.

**Review round 2 fixes (#654 PR #671, external bot findings):** two more. (1) `LogWateringDatePickerDialog`'s
`initialSelectedDateMillis` passed `System.currentTimeMillis()` — a raw UTC instant — directly into
Material3's `DatePicker`, which interprets that parameter as a UTC-midnight-encoded calendar date, not
an instant; in timezones where local calendar day differs from UTC's (e.g. early morning in UTC+14, late
evening in UTC−8) the picker could preselect the wrong day. Fixed via a new
`localTodayAsUtcMidnightMillis()` helper (`LogWateringDatePicker.kt`, mirroring `isOnOrBeforeLocalToday`'s
own local-day ↔ UTC-midnight conversion in the opposite direction) that encodes local today as UTC
midnight before handing it to the picker. (2) `QuickLogUseCase.computeSuggestion()` computed the
adaptive-gap observation from `CareLogRepository.getLastTwoWaterings()` — "the two globally newest
waterings by `loggedAt`" — rather than the newly-inserted (possibly backdated) log's own chronological
predecessor. Backdating a new WATER log to a date *before* an already-existing one silently paired the
new log with that later, unrelated log instead of its real neighbor, feeding the adaptive model a wrong
gap. Fixed by adding `CareLogRepository.getLastWateringBefore(plantId, beforeMillis)` (`CareLogDao
.getLastLogOfTypeBefore`, `... WHERE loggedAt < :beforeMillis ORDER BY loggedAt DESC LIMIT 1`) and
switching `computeSuggestion()` to look up the log strictly preceding its own `now`/`loggedAt` argument.
`AddCareLogViewModel`'s independent `getLastTwoWaterings()` call site is untouched — out of scope for
this fix, a pre-existing, separately-reported concern.

**UI feedback fix (#654 PR #671, post-merge-conflict-resolution):** `LogWateringDatePickerDialog`'s
custom `title` slot (`Text(stringResource(R.string.log_watering_date_picker_title))`) replaced
Material3's own default title composable entirely — which normally applies its own internal padding —
so the bare `Text` sat flush against the dialog's rounded top corner, partly clipped. Fixed by dropping
the override and letting `DatePicker` render its default title, exactly matching `AddCareLogScreen`'s
own picker (which never overrides `title` either, and never had this bug). The now-orphaned
`log_watering_date_picker_title` string resource was removed; tests that waited on/asserted that title
text now use the existing `LOG_WATERING_DATE_PICKER_TEST_TAG` instead, which already existed
specifically to locate this dialog in Compose UI tests.

**Follow-up (#679):** `requestWater`/`requestLiquidFertilize`'s on/off-schedule gate had the same class
of bug round 2's fix (2) above fixed for `computeSuggestion()` — it compared the picked date against
`PlantCareStatus.lastWateredAt` (the plant's globally newest watering, always "now"-relative) instead of
that date's own chronological predecessor, and the subsequent `WateringReasonBottomSheet`'s gap-length
wording repeated the same wrong reference point. Fixed via a new `PlantDetailViewModel
.previousWateringBefore(before): Long?` suspend wrapper around the same `CareLogRepository
.getLastWateringBefore()` lookup, called from `rememberCoroutineScope().launch {}` inside both
`LogWateringDatePickerDialog.onConfirm` callbacks; the fetched value is bundled with `loggedAt` into a
`PendingReasonPrompt` so `showWaterSheet`/`showLiquidFertilizeSheet`'s later `isChosenDateGapLong` call
uses the exact same predecessor `requestWater`/`requestLiquidFertilize` already gated on, rather than
re-deriving (or mis-deriving) it a second time. Also fixed in the same issue: `QuickLogUseCase
.quickWaterWithReason()`/`quickLiquidFertilizeWithReason()` cleared an active `wateringDueDateOverride`
unconditionally on every WATER insert, discarding an unrelated reschedule when backfilling an old
watering from before it was made — see `.claude/rules/watering-transparency.md`'s #679 follow-ups for
that fix and the matching cold-start-bootstrap `displayNow` fix.

**Test coverage follow-up (#679 review round 1):** `isChosenDateOnSchedule`/`isChosenDateGapLong` (the
functions backing the gate above) went from `private` to `internal` specifically so `PlantDetailScreenGateTest`
(a plain JVM unit test, `app/src/test/.../ui/screens/plantdetail/`) can exercise the exact "backdate
before an already-existing later watering" scenario directly — passing the real predecessor produces the
correct off-schedule/late result, while passing a reference chronologically *after* the chosen date (the
old, buggy stand-in for `PlantCareStatus.lastWateredAt`) reproduces the wrong-direction ("early") answer
the fix prevents. An instrumented Compose test driving Material3's `DatePicker` day grid to a specific
backdated day had no precedent in this suite and was judged too fragile (no existing test picks a
non-today date; day-of-month arithmetic would depend on when CI happens to run) to be worth adding for
this. `PlantDetailViewModel.previousWateringBefore()` itself has a plain delegation unit test in
`PlantDetailViewModelTest`. Every `mockk<CareLogRepository>()` fixture in `PlantDetailScreenTest.kt`
(instrumented) now also stubs `getLastWateringBefore(any(), any())` (default `null`) — added after CI
caught a `MockKException` on `wateringChip_onSchedule_tapLogsDirectlyWithoutTheReasonPrompt`, since every
"Log watering" date-picker confirm now calls `previousWateringBefore()` regardless of which test triggers
it.

**Follow-up (#675):** `LogWateringDatePickerDialog` moved from a centered Material3 `DatePickerDialog`
to a `ModalBottomSheet` wrapping the same stock `DatePicker` composable, matching the bottom-sheet
convention `WateringReasonBottomSheet`/`RescheduleReasonBottomSheet` (`ReasonBottomSheets.kt`) and
`WateringExplanationSheet` already use elsewhere on this screen — pure UI-consistency, no behavior
change. `rememberModalBottomSheetState(skipPartiallyExpanded = true)` is load-bearing: the default
(`false`) lets a tall sheet — a full calendar grid plus a button row — open only partially expanded on
smaller devices, pushing the OK/Cancel row below the fold with no scroll in the instrumented test that
clicks it. The OK/Cancel `TextButton` row (Cancel leading, OK trailing, end-aligned) sits below the
`DatePicker` inside the sheet's own `Column`, reusing `R.string.ok`/`R.string.cancel` unchanged, and
dismissal still routes through the plain `onDismiss` lambda (no `sheetState.hide()` await), matching
every other sheet's convention. `LOG_WATERING_DATE_PICKER_TEST_TAG` moved onto the `ModalBottomSheet`'s
`modifier`; `TodayOrEarlierSelectableDates`/`localTodayAsUtcMidnightMillis()`/
`utcMidnightMsToLoggedAtMillis()` and the public `LogWateringDatePickerDialog(onDismiss, onConfirm)`
signature are all unchanged, so `PlantDetailScreen.kt`'s two call sites needed no edits. Product
ADR-0034's passing description of this picker as "a plain Material3 `DatePickerDialog`" is now stale —
its substantive decision (no instant-log fast path, not-future-only range, picked-date-drives-everything)
is untouched, so the ADR itself was not edited.

**"Still moist" is no longer a button** — it's the "Soil still moist" answer, and still routes through
`QuickLogUseCase.recordStillMoistCheck()`, the same call site `notification/StillMoistReceiver` uses.

`applyReschedule(newDueAtMillis)` is the single commit point for all three date options:
`SOIL_STILL_MOIST` → `recordStillMoistCheck(plant, newDueAtMillis)` + `QuickLogMessage
.StillMoistChecked`/`.AlreadyCheckedToday`; anything else → a plain `wateringDueDateOverride` write,
never `wateringIntervalDays`/`wateringBaseIntervalDays`/`wateringConfidence` and never a
`watering_adjustments` row (ADR-0029's posture, kept for the half of reschedules that really is about
the user). **The deferral's length is never a model input** — the reason already decided that.

`RescheduleWateringDialog` options: an optional **"In N days (suggested)"** row at the top
(`suggestedDays`, non-null only for `SOIL_STILL_MOIST`, from `QuickLogUseCase
.suggestedStillMoistDeferralDays()`) / **Today** (`confirmRescheduleToday()`, disabled while
`isDueSoon` *and* while the reason is `SOIL_STILL_MOIST` — pulling the date forward would contradict
what the user just said) / **+1 / +2 / +3 days** (`confirmRescheduleRelativeDays(days)`, anchored to
`maxOf(nextWateringDueAt, now)`) / **Custom date…** (`confirmRescheduleCustomDate(dateMillis)`, a
Material 3 `DatePicker` with `SelectableDates` excluding past dates — UTC-vs-UTC comparison, matching
what the picker itself displays, not the device's local "today"). **Never fires the ADR-0006
interval-suggestion dialog** afterward, on either branch; there is no `Event` for a reschedule at all.

### Reschedule delta chip + revert (#630)
`PlantCareStatus.rescheduleDeltaDays: Int?` is computed once inside `CareSchedule.computeWateringDue()`
— non-null only when `plant.wateringDueDateOverride` is the actual `maxOf()` winner over the
schedule-computed due date (`override != null && override > computedNextDueAt`), so a stale,
non-winning override reports no delta and the chip self-hides once the schedule catches back up. A
`RescheduleDeltaChip` `AssistChip` (`WateringDueActions.kt`, "Rescheduled +N days" via the
`watering_reschedule_delta_days` plural, plus a decorative trailing close icon —
`contentDescription = null`, matching this file's convention for a decorative icon inside an
already-labeled clickable unit, since `AssistChip` merges descendant semantics into one TalkBack
announcement — so tap-to-revert reads as removable rather than relying on the chip's clickability
alone) renders directly above `WateringDueActionsRow` in both the classic layout and the Water tab,
gated on this same field; tapping anywhere on the chip calls
`PlantDetailViewModel.revertReschedule()` directly — no confirmation dialog. `revertReschedule()`
clears `wateringDueDateOverride` only (never `wateringIntervalDays`/`wateringBaseIntervalDays`/
`wateringConfidence`, never a `WateringAdjustment` row — same posture `applyReschedule` already keeps)
and emits `Event.RescheduleReverted(previousOverrideAtMillis)`; `PlantDetailScreen` shows a Snackbar
("Reschedule reverted" + `R.string.snackbar_undo`) whose Undo action calls
`undoRevertReschedule(previousOverrideAtMillis)` to restore the captured prior override as-is — mirrors
`Event.SilentIntervalApplied`/`undoSilentIntervalApply()`'s capture-before-write shape exactly, same
accepted race if a newer reschedule lands before Undo is tapped. The "Why this date?" sheet
(`WateringExplanationSheet`/`WateringExplanationBuilder`) gets a matching **display-only** mirror row
(same plural, no tap target) — the chip outside the sheet is the only actionable UI for this.

## Photos
Unified `PhotoGallery` merges `plant_photos` + care-log photos (`GalleryPhoto(uri, timestamp)`,
`.distinctBy { it.uri }`) newest-first (technical ADR-0015, #290). `FullScreenPhotoViewer` is a `HorizontalPager`
over `photos: List<GalleryPhoto>`, solid-black background incl. status-bar area, "N / M" indicator when > 1, per-page
capture date chip (`cd_photo_viewer_date`); trash icon + long-press delete individual photos (cover falls back to
next-most-recent) (#306/#308/#444/#445).

## Care history
Collapses to 5 most recent by default; `AssistChip` with animated chevron expands; hidden when ≤ 5; expanded state
resets on screen open (#253).

## Custom reminders (technical ADR-0019, #232)
`CustomRemindersCard`'s **placement** is layout-dependent (product ADR-0030, #590): in the **classic layout**
(`PLANT_DETAIL_TABS` off) it stays an always-visible card, unchanged — rendered after the watering-due actions row
(#508) / `WateringHistoryChart` / photo gallery block, so it doesn't sit between the watering stat chip and the
actions row (#232 follow-up). In the **tabs layout** it is no longer always-visible — it renders only when
`selectedTab == PlantDetailTab.CUSTOM_REMINDERS`, one of the two tabs hidden behind the collapsed tab row by
default (see "Tab row collapse/expand" above). Same composable, same params, same behavior either way. Backed by
`PlantDetailViewModel.customReminders` (`Flow` from
`CustomReminderRepository`) and `customReminderStatuses` (derived from `careStatus`, since `CareSchedule.computeStatus`
now takes a `customReminders` param and returns `PlantCareStatus.customReminderStatuses: List<CustomReminderStatus>`).
Add/edit uses one shared `CustomReminderDialog` (name + plain-days interval, no months toggle); delete goes through a
confirm `AlertDialog`; "mark done" (`markCustomReminderDone`) writes a `CareType.CUSTOM` `CareLog` linked via
`customReminderId` and resets the reminder's `lastDoneAt` in one ViewModel call. Composables live in a separate
file, `CustomRemindersSection.kt` (not `PlantDetailScreen.kt`), to stay under Detekt's per-file `TooManyFunctions`
threshold — same reasoning as `PlantIssuesSection.kt` below. Row/card composables bundle their callbacks into an
`internal` `CustomReminderActions` data class (needed cross-file, unlike `PlantIssuesSection.kt`'s file-private
`ReminderToggleState`) to stay under Detekt's `LongParameterList` threshold —
follow that pattern rather than adding more individual lambda params. `CareLogItem` takes an optional
`customReminderName: String?` so a `CUSTOM` journal entry shows the reminder's free-text name instead of the generic
label; pass `null` (or omit it) when the linked reminder has since been deleted — never crash on a dangling
`customReminderId`.

## Plant issues (technical ADR-0020, #564)
"Active issues" `PlantIssuesCard`'s **placement** mirrors `CustomRemindersCard` (product ADR-0030, #590): always-
visible, right after `CustomRemindersCard`, in the **classic layout**; rendered only when `selectedTab ==
PlantDetailTab.ISSUES` — the other tab hidden behind the collapsed tab row by default — in the **tabs layout**.
Composables live in a separate file, `PlantIssuesSection.kt` (not `PlantDetailScreen.kt`), to stay under Detekt's
per-file `TooManyFunctions` threshold;
`PlantIssuesCard` is `internal` so `PlantDetailScreen.kt` can call it. Backed by `PlantDetailViewModel.activeIssues`
(`Flow<List<PlantIssue>>` from `PlantIssueRepository.getActiveIssuesForPlant`, already filtered to `resolvedAt ==
null` — the card never shows resolved issues). Each row shows the issue name, "Ongoing for N days" (via
`CareSchedule.daysBetween(issue.startedAt, now)`, never inline date math), and — when `linkedReminderId` resolves
against the already-loaded `customReminders` list — a "Reminder: {name}" line; a dangling `linkedReminderId` (its
`CustomReminder` was deleted) just omits that line, same posture as `CareLogItem`'s `customReminderName`.
"Report an issue" (`ReportIssueDialog`) has an optional "set a treatment reminder" toggle that, when on, creates a
`CustomReminder` **and** links it via `PlantIssue.linkedReminderId` in one `reportIssue()` ViewModel call — this is
a one-way, unenforced link (ADR-0019/ADR-0020): resolving or deleting the issue never touches the linked reminder.
"Mark resolved" (`ResolveIssueDialog`) sets `resolvedAt` + an optional free-text `resolutionNote`; no notification
or `ReminderWorker` involvement — this is a passive visual status only.
