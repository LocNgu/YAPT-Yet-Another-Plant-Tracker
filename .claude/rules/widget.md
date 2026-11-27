---
description: Care home-screen widget (Glance) — data source, mapping, deep links, scope
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/ui/widget/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/navigation/AppDeepLink.kt"
  - "app/src/main/res/xml/care_widget_info.xml"
  - "app/src/test/**/widget/**/*"
---

# Care widget (product ADR-0088, #923)

- **Scope: watering only.** WATER and WATER_AND_FERTILIZE tasks from Care's **default** `TodayCareRepository.observeQueue()`
  (never a caller horizon). Every other kind stays in the app; widening the scope needs a new ADR.
- **Data path:** `CareWidget.provideGlance()` takes `observeQueue().first()` from `YaptApplication.todayCareRepository`
  and maps it with the pure `careWidgetState()` (`CareWidgetState.kt`). The mapper reuses `careTypeSections()` and keeps
  only the WATERING entries, so groups (Overdue / Today / Tomorrow), order and distinct-plant counts equal Care's. Never
  derive dates, buckets or extra Room queries here. `dueNowCount` = Overdue + Today; Tomorrow is listed, not counted.
- **States:** `NoPlants` (activePlantCount 0 or no snapshot) → first-use message; `Watering(dueNowCount = 0)` →
  "Nothing to water today" plus any Tomorrow rows. No overdue colouring (#842); colours come from the app's
  `LightColorScheme`/`DarkColorScheme` via `glance-material3` `ColorProviders`, never dynamic colour or due-status colours.
- **Taps (`CareWidgetIntents`):** header and message states → `openCare()` (`EXTRA_OPEN_CARE`); a row →
  `openPlantDetail(plantId, row.plantDetailTab)` (Care's `TodayCareKind.plantDetailTab()`). `MainActivity` reads every
  launch intent through `Intent.appDeepLink()` (`ui/navigation/AppDeepLink.kt`), which also covers the reminder
  notification (`EXTRA_PLANT_ID`) and the post-watering "cared for today" link. The Home tab (product ADR-0089) is the
  sentinel `Screen.Today.CARE_DEEP_LINK_ID` → `navigateToRootTab(Screen.Today.route)`; a plant id only counts when > 0.
- **Accessibility:** each row's content description is Care's tile description (`care_tile_water_cd` /
  `care_tile_water_fertilize_cd`); the header's (`widget_care_open_care_cd`) names the count and that it opens Home.
  All strings are `widget_care_*` in `strings.xml`.
- **Size:** `care_widget_info.xml` sets a 2×2 minimum, resizable both ways; the list is a weighted Glance `LazyColumn` (row item ids
  are positive per-task ids, group headers use negative ids).
- **Freshness:** for now only the system `updatePeriodMillis` (30 min) re-runs `provideGlance`. The `WidgetUpdater` hook
  (write paths, midnight, boot, app update, restore) and the **Watered** / **Not now** actions are the next #923
  slices; their rules are fixed in product ADR-0088 (Watered through `QuickLogUseCase` + Care's reason gate; Not now =
  notification snooze semantics).
- **Tests:** `CareWidgetStateTest` (JVM, through the real aggregator: order, count, hidden/archived, non-watering
  kinds, parity with `careTypeSections()`), `CareWidgetIntentsTest` (Robolectric, intent round-trips).
