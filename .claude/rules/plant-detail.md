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
**off** = classic single-page layout. The `StatsRow` quick-log chips, shared care-history list, and `+` FAB render
in **both** paths.
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
`PlantDetailTab.entries.take(4)` — today's Water/Fertilize/Repot/Photo, pixel-identical to before; expanded shows
all 6, with `CUSTOM_REMINDERS`/`ISSUES` wrapping onto a second row at that same per-tab width.
- `var isTabRowExpanded by rememberSaveable { mutableStateOf(false) }` — screen/session-local like `selectedTab`
  and the care-history `isExpanded` chip, **not** a `DataStore` setting; resets to collapsed on every fresh visit.
- Toggle reuses the care-history `AssistChip`'s exact chevron-rotate pattern (`animateFloatAsState` rotating
  `Icons.Filled.ExpandMore` 180°), with a `contentDescription` that flips between
  `plant_detail_tabs_expand_cd`/`plant_detail_tabs_collapse_cd`.
- Attention `Badge` on the toggle when **collapsed** and (`activeIssues.isNotEmpty()` or any
  `CustomReminderStatus.isOverdue`) — both already-collected in `PlantDetailScreen.kt`, no new queries. Hidden once
  expanded.
- Collapsing while `selectedTab` is `CUSTOM_REMINDERS`/`ISSUES` (now hidden) resets `selectedTab` to `WATER`.

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

## Tappable stat chips (#434)
Watering/Fertilizing `StatChip`s take optional `onWaterClick`/`onFertilizeClick` (with `clickable(onClickLabel=…)`
for a11y). Water opens `WaterFeedbackBottomSheet` → `quickWater()`; Fertilize → `quickFertilize()` (regular) or the
combined sheet → `quickLiquidFertilize()` (liquid-fert). All delegate to the shared `QuickLogUseCase`, feed the
adaptive suggestion into the `suggestedWateringInterval` dialog, and emit a `QuickLogMessage`. Left in place
alongside the watering-due actions row below (#508) — its removal is a deferred follow-up, out of scope there.

## Watering-due actions row: Water / Still moist / Reschedule watering (#508, product ADR-0029)
When a watering is due (`status.isOverdue || status.isDueSoon`), `WateringDueActionsRow` (`WateringDueActions.kt`)
renders three `OutlinedButton`s in one row — replacing the old single full-width "Skip watering" button — in both
the classic layout and the Water tab. Each means exactly one thing:
- **Water** — `showWaterSheet = true`, same `WaterFeedbackBottomSheet` → `quickWater()` flow the stat chip above uses.
- **Still moist** — `PlantDetailViewModel.recordStillMoist()` calls `QuickLogUseCase.recordStillMoistCheck(plant)`
  directly, the same function `notification/StillMoistReceiver` calls for the #570 notification action — the
  in-app and notification paths can't drift since they share one call site. Emits `QuickLogMessage
  .StillMoistChecked`/`.AlreadyCheckedToday` (same-day CHECK dedupe, mirroring `AlreadyWateredToday`).
- **Reschedule watering** (renamed from "Skip watering", shared string with the `ReminderWorker` notification
  action label) — `requestReschedule()` shows `RescheduleWateringDialog` (`WateringDueActions.kt`): Today
  (`confirmRescheduleToday()`, disabled while `isDueSoon`, enabled while `isOverdue` — a due-today plant pulling to
  today would be a no-op) / +1 / +2 / +3 days (`confirmRescheduleRelativeDays(days)`, anchored to
  `maxOf(nextWateringDueAt, now)`) / Custom date… (`confirmRescheduleCustomDate(dateMillis)`, a Material 3
  `DatePicker` with `SelectableDates` excluding past dates — UTC-vs-UTC comparison, matching what the picker itself
  displays, not the device's local "today"). Every option writes `wateringDueDateOverride` only — never
  `wateringIntervalDays`/`wateringBaseIntervalDays`/`wateringConfidence`, and never a `watering_adjustments` row.
  **Never fires the ADR-0006 interval-suggestion dialog afterward** (the superseded skip flow did, bypassing the
  #572 "Ask before changing intervals" toggle entirely — a bug, not a preserved behavior); there is no `Event` for
  a reschedule at all.

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
`customReminderId` and resets the reminder's `lastDoneAt` in one ViewModel call. Row/card composables bundle their
callbacks into a private `CustomReminderActions` data class to stay under Detekt's `LongParameterList` threshold —
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
