---
description: Watering overview page (Settings → Watering overview) — groups, builder, day-change wiring
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/watering/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/watering/**/*"
  - "app/src/test/**/watering/**/*"
  - "app/src/androidTest/**/watering/**/*"
---

# Watering overview (#901, product ADR-0063)

A read-only audit of how often every plant needs water. Nested under Settings, in the "Overviews" section (`settings_section_overviews`, between Display and Backup & restore) directly after Repotting overview (`Screen.WateringOverview`, route `watering_overview`); the row's subtitle is static, so `SettingsViewModel` has no pipeline for it. No schema, DataStore or backup change.

- **Pipeline:** `observeWateringOverview` (`domain/watering/WateringOverviewSnapshot.kt`) combines `getAllPlants()`, `careLogRepository.observeLastCareAtByPlant(CareType.WATER)`, the seasonal amplitude flow and a day-change trigger. `WateringOverviewViewModel` injects `dayChange: Flow<LocalDate> = dayChangeTicker()` and `nowProvider`; the ticker's value is ignored and `nowProvider()` is read once per emission, so intervals and due dates share one instant.
- **Builder:** `WateringOverviewBuilder.build()` is pure and the only place groups are decided. It calls `CareSchedule.computeStatus` for `nextWateringDueAt` and `wateringScheduleMode`; it never adds due-date logic or per-plant Room queries.
- **Groups**, in order, empty ones omitted: 1–3 / 4–7 / 8–14 / 15+ days, Resting, No watering schedule. Every active plant is in exactly one; archived plants are never listed or counted.
  - No `wateringIntervalDays` → No watering schedule (even with a dormancy window); no interval or due date shown.
  - `DORMANT_SUSPENDED` → Resting, "Watering paused", no due date.
  - `DORMANT_CADENCE` → Resting, shows the fixed `dormantWateringIntervalDays` (not the seasonal value) plus `nextWateringDueAt`.
  - `NORMAL` → the band of `effectiveWateringIntervalDaysForDisplay()` for today, so a seasonal plant moves between bands; pinned or amplitude Off shows the stored literal.
- **Known limitation:** an unchanged snapshot is conflated by `stateIn`, so relative dates can stay stale across midnight while the page is open and nothing else changed. Accepted by the owner; do not promise midnight refresh in user-facing copy.
- **Order:** interval, then name (case-insensitive), then id. Resting by cadence with suspended last; No schedule by name.
- **Count line:** "N plants · A on a schedule · R resting · S without a schedule"; zero parts are omitted and resting plants are not "on a schedule". The proportion bar uses neutral tints (primary alphas, outline) — never the due-status colours — and its content description reads out the counts.
- **Rows:** more than `WATERING_OVERVIEW_COLLAPSED_ROWS` (5) show five plus "Show all N"; the expanded set is `rememberSaveable` and local to the screen. A row tap opens Plant Detail on `PlantDetailTab.WATER`.
- **Tests:** builder and flow tests use fixed `now` values (`toStartOfDayMillis()` plus 12 h) and a `MutableSharedFlow` ticker; never `advanceUntilIdle()` against the real `dayChangeTicker()`. Compose tests assert visible text, content descriptions and the row tag action, not structure.
