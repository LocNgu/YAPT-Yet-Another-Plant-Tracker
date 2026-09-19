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

## Repot and Photo tab quick actions (#658, date-first per #694)

With `PLANT_DETAIL_TABS` on, Repot and Photo each start with an always-visible filled action button,
using a leading tab-matching icon and the same 16dp horizontal padding as Water's primary action.
Neither action renders in the classic flag-off layout. Custom Reminders and Issues retain their
existing add/report controls; no extra duplicate actions are added there.

**Repot** opens a date picker first (`CareDatePickerBottomSheet`, `REPOT_DATE_PICKER_TEST_TAG`,
`showRepotDatePicker` state in `PlantDetailScreen.kt`), defaulting to today. Confirming calls
`PlantDetailViewModel.quickRepot(loggedAt)` → `QuickLogUseCase.quickLog(plant, CareType.REPOT,
loggedAt)`, preserving the shared repot confidence-reset/freeze side effect and the existing rule that
REPOT is not guarded against same-day duplicates — the picked date is also the reset anchor
(`WateringLifecycleReset.applyRepotReset`'s `resetAnchorMs`), so `wateringResetAt`/`wateringFreezeUntil`
follow a backdated repot rather than always landing on "now". Cancelling the sheet creates no log and
applies no reset. `QuickLogUseCase.quickLog()`'s `loggedAt: Long = System.currentTimeMillis()` parameter
(mirroring #654's identical threading on `quickWaterWithReason`) is what makes this possible — it drives
the duplicate-day check (WATER/FERTILIZE only; REPOT is never guarded), the `CareLog` write, the paired
liquid-fertilizer WATER insert, the post-watering reminder debounce, and the reset anchor together, so
none of them can drift from each other. Only `quickRepot()` passes a non-default value; `bulkLog`, Plant
List, Calendar, and plain `quickFertilize()` all keep using real "now" — no other quick-log surface
changed behavior.

**Photo** opens `AddPhotoBottomSheet` (`PlantDetailPhotoCapture.kt`, `ADD_PHOTO_SHEET_TEST_TAG`) — one
sheet combining a date row (defaulting to today, editable in place) with **Take photo** / **Choose from
gallery** — rather than navigating to `AddCareLogScreen` (product ADR-0038, replacing the Photo half of
#658/#693). Whichever source returns an image, `PlantDetailViewModel.savePhotoLog(uri, loggedAt)` writes
the PHOTO `CareLog` at the picked date and updates the plant's cover photo directly; it deliberately never
calls `plantPhotoRepository.addPhoto()` (the unified `PhotoGallery` already merges `plant_photos` with
care-log photos, technical ADR-0015 — writing both would list the same image twice) and `Plant.updatedAt`
stays real wall-clock time even for a backdated photo. The trade this makes explicit: the quick path has
**no notes field** — `AddCareLogScreen` remains the untouched canonical full-entry flow (its route,
`CareType` preselection argument, `consumeNewLogCareType()`, and the `+` FAB all still work exactly as
before). Cancelling the sheet or abandoning image selection creates no log — `pendingPhotoLoggedAt`
(`rememberSaveable`, since the camera app can kill this Activity mid-capture) is only consumed by the
camera/gallery result callbacks, never by the sheet's own dismissal.

### The shared date-picker sheet (`CareDatePicker.kt`, #654/#675/#694)

`LogWateringDatePickerDialog`'s body is split three ways so ADR-0037's two load-bearing structural
details (`skipPartiallyExpanded = true`; the `DatePicker` in its own `weight(1f, fill = false)
.verticalScroll(...)` inner `Column` with the Cancel/OK row pinned outside it) live in exactly one place
rather than being copy-pasted per caller:
- `CareDatePickerContent(initialSelectedDateMillis, onCancel, onConfirm)` — the actual `DatePicker` +
  button row, an extension on `ColumnScope` (not a sheet of its own) so a caller that already owns a
  `ModalBottomSheet` can swap it in as an internal content state without nesting two sheets. The
  Add-photo sheet does exactly this.
- `CareDatePickerBottomSheet(testTag, onDismiss, onConfirm, initialSelectedDateMillis =
  localTodayAsUtcMidnightMillis())` — a `ModalBottomSheet` wrapping the content above; Repot's own
  picker uses this directly.
- `LogWateringDatePickerDialog(onDismiss, onConfirm)` — a one-line delegate onto
  `CareDatePickerBottomSheet` passing `LOG_WATERING_DATE_PICKER_TEST_TAG`. Its function name and that
  tag's string value (`"log_watering_date_picker_dialog"`) are deliberately unchanged, so every existing
  water/liquid-fertilize call site and instrumented test needed no edits.

`localDayToUtcMidnightMillis(loggedAt)` (the inverse of `localTodayAsUtcMidnightMillis()`) re-encodes an
already-picked `loggedAt` instant's local calendar day as UTC midnight, so a picker can be re-opened
pre-selected on a previously chosen date rather than always defaulting back to today — the Add-photo
sheet's date-edit state uses this to preselect whatever date the sheet is currently showing.

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
- **Reschedule watering** — as of #738 (product ADR-0039), a reschedule is model-neutral again and
  asks no reason at all: `requestReschedule()` opens `RescheduleWateringDialog` directly.
  `RescheduleReasonBottomSheet`/`chooseRescheduleReason()` (which used to open first and ask "Why put
  it off?" → "Soil still moist" / "I can't right now", dismissible to abandon the reschedule entirely)
  are removed.

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
smaller devices. But `skipPartiallyExpanded` only removes that partial-expansion anchor — it does not
shrink oversized content to fit the viewport, so on its own it does not guarantee the OK/Cancel row is
reachable. What actually guarantees that (external review on PR #696) is that the `DatePicker` sits in
its own inner scrollable `Column` (`Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())`)
below the sheet's outer `Column`, with the OK/Cancel `TextButton` row (Cancel leading, OK trailing,
end-aligned) always rendered last, outside that inner scroll — the calendar scrolls internally on a
viewport shorter than its own ~568dp (landscape, a resized multi-window), while the buttons stay pinned
and visible; `weight(1f, fill = false)` also means the sheet does not stretch to full height on a normal
portrait phone where the calendar comfortably fits. Reusing `R.string.ok`/`R.string.cancel` unchanged,
and dismissal still routes through the plain `onDismiss` lambda (no `sheetState.hide()` await), matching
every other sheet's convention. `LOG_WATERING_DATE_PICKER_TEST_TAG` moved onto the `ModalBottomSheet`'s
`modifier`; `TodayOrEarlierSelectableDates`/`localTodayAsUtcMidnightMillis()`/
`utcMidnightMsToLoggedAtMillis()` and the public `LogWateringDatePickerDialog(onDismiss, onConfirm)`
signature are all unchanged, so `PlantDetailScreen.kt`'s two call sites needed no edits. Product
ADR-0034's passing description of this picker as "a plain Material3 `DatePickerDialog`" is now stale —
its substantive decision (no instant-log fast path, not-future-only range, picked-date-drives-everything)
is untouched, so the ADR itself was not edited.

**Follow-up (#694):** `LogWateringDatePicker.kt` was renamed to `CareDatePicker.kt` (and its test file to
`CareDatePickerTest.kt`) once Repot and the Add-photo sheet needed the same "pick a date, then confirm"
sheet — see "The shared date-picker sheet" above for the `CareDatePickerContent`/
`CareDatePickerBottomSheet` split this introduced. `LogWateringDatePickerDialog`'s signature and
`LOG_WATERING_DATE_PICKER_TEST_TAG`'s string value are deliberately unchanged despite the file move, so
every existing water/liquid-fertilize call site and instrumented test still compiles and passes
untouched. This section's two load-bearing details (`skipPartiallyExpanded = true`; the pinned-button-
row-plus-scrollable-calendar structure) now live in `CareDatePickerContent`/`CareDatePickerBottomSheet`
rather than directly in `LogWateringDatePickerDialog`'s own body — any future edit to either detail
belongs there, not in the now-trivial delegate.

**"Still moist" is retired (#738, product ADR-0039).** It is no longer a button (that happened back
at #508/ADR-0029) and, as of ADR-0039, it is no longer an answer either — the reschedule reason
prompt it lived in as "Soil still moist" is removed entirely, and the notification's own "Still
moist" action (`StillMoistReceiver`) is dropped rather than reworked. `QuickLogUseCase
.recordStillMoistCheck()`, `recordStillMoistAdaptiveObservation()`, `suggestedStillMoistDeferralDays()`,
and `StillMoistReceiver` itself are all deleted.

`applyReschedule(newDueAtMillis)` is the single commit point for every date option, unconditionally —
a plain `Plant.wateringDueDateOverride` write via `QuickLogUseCase.recordReschedule(plant,
newDueAtMillis)`, never `wateringIntervalDays`/`wateringBaseIntervalDays`/`wateringConfidence` and
never a `watering_adjustments` row, and no `QuickLogMessage` emitted. There is no reason branch left
to distinguish — every reschedule behaves the way ADR-0029 originally described for the half of
reschedules that really was about the user. **The deferral's length is never a model input** — there
is no model input at all.

`RescheduleWateringDialog` options: **Today** (`confirmRescheduleToday()`, disabled via
`isRescheduleTodayEnabled` — see "Today button's own gate" below, #746)
/ **+1 / +2 / +3 days** (`confirmRescheduleRelativeDays(days)`, anchored to `maxOf(nextWateringDueAt,
now)`) / **Custom date…** (`confirmRescheduleCustomDate(dateMillis)`, a Material 3 `DatePicker` with
`SelectableDates` excluding past dates and — since #720 — dates on or before the schedule-computed due
date, see below). **Never fires the ADR-0006 interval-suggestion dialog**
afterward; there is no `Event` for a reschedule at all. The "(suggested)" row and its source
(`suggestedStillMoistDeferralDays()`) and `PlantDetailViewModel.confirmRescheduleSuggestedDays()`
(#719's handler) are removed — a reschedule no longer teaches the model anything for that row to
preview, leaving Today/+1/+2/+3/Custom date as the full option set.

**The two-anchor confusion this file used to document under "#719" is resolved by #738, not by
patching it.** The removed "(suggested)" row's from-today anchor and `confirmRescheduleRelativeDays()`'s
due-date anchor only ever needed reconciling because that row existed; removing it removes the second
anchor entirely, leaving `confirmRescheduleRelativeDays()`'s due-date anchor as the only one left. See
`.claude/rules/adaptive-watering-cluster.md` for the fuller history of that anchor pair and how it
interacted with #719/#720.

### Custom-date picker's due-date floor (#720)
`CareSchedule.computeWateringDue()` resolves the due date as `maxOf(computedNextDueAt, override)`, so an
override earlier than the schedule-computed date can never win — it would be written to the database
and then silently discarded, with no chip, snackbar, or error. The "Custom date…" picker's
`SelectableDates` therefore ANDs two independent floors, never just one:
`WateringDueActions.isSelectableRescheduleDate(utcTimeMillis, computedNextWateringDueAt, zoneId, today)`
combines the existing `isOnOrAfterLocalToday` (local-today floor) with a new due-date floor — a candidate
is only selectable when its local calendar day is **strictly after** `computedNextWateringDueAt`'s local
calendar day (same-day can only tie or lose the `maxOf()`). Both floors are independently load-bearing: a
plant overdue since January with today in September needs the today floor to reject a February pick that
the due-date floor alone would accept, and a plant not yet due needs the due-date floor to reject "today"
where the today floor alone would accept it. `computedNextWateringDueAt == null` (no interval configured)
makes the due-date floor vacuous.

`PlantCareStatus.computedNextWateringDueAt: Long?` carries `computeWateringDue()`'s private
pre-override local out to the UI layer for exactly this comparison — populated once inside `CareSchedule`,
never re-derived, same posture `rescheduleDeltaDays` already documents. It is a real epoch-millis instant
(unlike the picker's own `utcTimeMillis`, which Material3 always encodes as UTC midnight regardless of
device timezone) and must be converted via the caller's `zoneId`, not `ZoneOffset.UTC`, to compare local
calendar days consistently. `TodayOrLaterSelectableDates` is a class (not the earlier stateless `object`)
parameterized by `computedNextWateringDueAt`, `remember`ed keyed on that value in
`RescheduleDatePickerDialog` so `rememberDatePickerState` isn't handed a fresh instance every
recomposition; `RescheduleWateringDialog` threads the value down from `PlantDetailScreen`'s
`careStatus?.computedNextWateringDueAt`.

Today/+1/+2/+3 need no such gate — they anchor to `maxOf(nextWateringDueAt, now)` and only ever add
forward time, so they cannot produce an ineffective date by construction; only the free-form custom date
can land on or before the computed due date. `computeWateringDue()`'s `maxOf()` itself is untouched by
this fix — constraining the picker was the chosen option (A) over letting an earlier override win (C),
which ADR-0029/ADR-0039's forward-only invariant doesn't contemplate. A related, separate bug in the
"Today" button's own gate (`todayEnabled = careStatus?.isOverdue == true`, which could be `false` in a
state where tapping Today would actually pull the due date in) was out of scope here and filed
separately as #746 — now fixed, see "Today button's own gate (#746)" below.

**Review round 1 fix (#720 PR #748):** excluding a date from the day grid isn't the whole picket —
Material3's `rememberDatePickerState` re-validates its retained state's grid against a fresh
`SelectableDates` instance on recomposition, but it does **not** clear an already-tapped
`selectedDateMillis` that a since-moved `computedNextWateringDueAt` would now reject (e.g. a watering
logged from another surface, such as a notification action, while the dialog sits open). Without a
second check, OK would still forward that stale selection to `onConfirm`, reproducing the exact
silent-no-op bug #720 exists to prevent. `RescheduleDatePickerDialog`'s OK `TextButton` now also gates
`enabled` on `isRescheduleConfirmEnabled(selectedDateMillis, computedNextWateringDueAt)` — a pure
predicate (`null` selection stays enabled, matching the documented "OK closes the picker either way"
behavior; a non-null selection is re-validated via `isSelectableRescheduleDate`) — disabling the
affordance rather than silently discarding the tap, per this codebase's convention: a disabled control
is visible feedback, a silently-dropped confirm is the exact bug class being fixed. This is not
redundant with the grid's own `SelectableDates` — do not delete it as apparently so.

`isOnOrAfterLocalToday`, `isSelectableRescheduleDate`, `isRescheduleConfirmEnabled`,
`TodayOrLaterSelectableDates`, and `utcMidnightMsToLocalStartOfDayMillis` live in a separate file,
`RescheduleDateSelection.kt` (not `WateringDueActions.kt`), split out in this same round specifically to
stay under Detekt's per-file `TooManyFunctions` threshold once `isRescheduleConfirmEnabled` was added —
same reasoning as `CustomRemindersSection.kt`/`PlantIssuesSection.kt` elsewhere in this file.
`RescheduleDatePickerDialog` itself stays in `WateringDueActions.kt`, calling into the split-out file's
top-level functions (same package, no import needed).

### Today button's own gate (#746)
`RescheduleWateringDialog`'s "Today" option used `todayEnabled = careStatus?.isOverdue == true` —
derived from the **post-override, effective** due date, which is always `>= computedNextDueAt` since
an override only ever wins `maxOf()` when it's greater. That left a window where a winning *future*
override made "Today" disabled even though tapping it (`override = now`) would beat
`computedNextDueAt` in `maxOf()` and genuinely pull the due date in — the opposite failure mode from
#720 (offered-then-discarded vs. conservatively withheld).

Fixed with a new pure predicate, `isRescheduleTodayEnabled(computedNextWateringDueAt,
effectiveNextWateringDueAt, zoneId, today)` (`RescheduleDateSelection.kt`) — `true` whenever local
today clears **both** of two independent floors: local today is strictly after
`computedNextWateringDueAt`'s local calendar day (the schedule-computed floor, mirroring
`isSelectableRescheduleDate`'s own due-date floor), **and** `effectiveNextWateringDueAt`
(`PlantCareStatus.nextWateringDueAt`, the post-override date actually in effect) is not already today.
Both `null` (no interval configured) are treated as vacuously clearing their own floor, matching
`isSelectableRescheduleDate`'s convention — unreachable in practice, since a non-null interval forces
`computeWateringDue()` to always populate both fields. No UTC round-trip is needed here, unlike
`isSelectableRescheduleDate`'s `utcTimeMillis` parameter — "Today" never goes through the Material3
picker, it writes a plain `System.currentTimeMillis()`, so `LocalDate.now(zoneId)` is already the real
local day being written. `isOverdue` still implies this predicate (a strict superset — this change can
only flip Today from disabled to enabled, never the reverse): `isOverdue` means the effective due
date's local day is strictly before today, which both clears the computed floor
(`computedNextDueAt <= effective due date` always) and rules out the "already due today" no-op guard.

**Review round 1 (#752), a verified Codex finding:** the first (single-parameter) version of this
predicate only checked the computed-due-date floor, which is the *pre*-override date and stays frozen
in the past forever once a plant has gone overdue — it never re-tracks a since-applied Today tap or an
active override. Sequence: overdue plant, no override, tap Today (`override = now`) → effective due
date becomes today, `isOverdue` correctly flips to `false`, but the old fix's predicate still read the
same (already-cleared) computed floor and stayed `true` — reopening the dialog offered Today again for
a tap that would be a genuine no-op. The second `effectiveNextWateringDueAt` parameter (`PlantCareStatus
.nextWateringDueAt`) closes this: it's the "already due today" check the computed floor alone can't
express, since the computed floor doesn't move once an override or a same-day Today tap has already
resolved it.

**This is no longer provably identical to `isSelectableRescheduleDate`'s decision for the picker's own
"today" cell — only a one-way implication holds.** Whenever `isRescheduleTodayEnabled` is `true`, the
picker would also accept its own today cell (feeding local-today in as `isSelectableRescheduleDate`'s
`utcTimeMillis` makes its `isOnOrAfterLocalToday` term trivial and its due-date floor reduce to exactly
`isRescheduleTodayEnabled`'s own computed-floor term). The reverse can fail: when the plant's effective
due date is already today, the button's no-op guard disables it while the picker's day grid — which has
no per-cell "already exactly this value" concept, since it's a many-valued grid rather than one fixed
target — still offers that same cell as selectable. This is an accepted, low-severity gap distinct from
#720's: the picker cell's date can still win or tie `computeWateringDue()`'s `maxOf()`, it just doesn't
*move* anything when it ties, so nothing is silently discarded. Not fixed here.

`PlantDetailScreen.kt` calls it as `careStatus?.let { isRescheduleTodayEnabled(it.computedNextWateringDueAt,
it.nextWateringDueAt) } == true` rather than unwrapping `careStatus` at the call site directly — the
latter would conflate "`careStatus` hasn't loaded yet" with "no computed/effective due date" and, since
both fields' `null` is vacuously enabled, would flip a not-yet-loaded `careStatus` from disabled
(today's behaviour) to enabled. The two nulls are deliberately not the same case.

**Known limitation, accepted, not fixed:** a never-watered plant (`lastWateredAt == null`) with a
winning future override keeps Today disabled — `computeWateringDue()` pins `computedNextDueAt = now`
for that branch, which re-tracks "today" indefinitely, so the computed floor is never cleared no matter
how far out the override sits. Not a regression (the old gate disabled it too) and not a disagreement
with the picker (its own today-cell floor degenerates identically for that plant).

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
