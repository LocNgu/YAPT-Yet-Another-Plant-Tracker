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
  opens `WateringReasonBottomSheet` ("Why now?" → "The plant needed it" / "Just my timing"). The
  `requestWater`/`requestLiquidFertilize` helpers at the bottom of `PlantDetailScreen.kt` own that
  branch, shared with the classic layout's tappable `StatChip`s and the tabs layout's
  `FertilizeDueActionRow` so no surface can disagree.
- **Reschedule watering** — `requestReschedule()` opens `RescheduleReasonBottomSheet` ("Why put it
  off?" → "Soil still moist" / "I can't right now") **first**; `chooseRescheduleReason()` then opens
  `RescheduleWateringDialog`. Dismissing the reason sheet abandons the reschedule entirely.

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
`reschedule_delta_chip_remove_cd` — so tap-to-revert reads as removable rather than relying on the
chip's clickability alone) renders directly above `WateringDueActionsRow` in both the classic layout
and the Water tab, gated on this same field; tapping anywhere on the chip calls
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
