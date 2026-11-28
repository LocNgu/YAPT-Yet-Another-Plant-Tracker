---
description: Collection insights page (Settings → Collection insights) — builder, late-watering rule, day-change wiring
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/domain/insights/CollectionInsights*.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/insights/**/*"
  - "app/src/test/**/insights/CollectionInsights*"
  - "app/src/androidTest/**/insights/**/*"
---

# Collection insights (#1004, product ADR-0089)

A read-only, rolling-30-day page in Settings' "Overviews" section after Watering overview (`Screen.CollectionInsights`, route `collection_insights`; bottom bar hidden, back to Settings). No schema, DataStore or backup change; the Settings row's subtitle is static.

- **Pipeline:** `observeCollectionInsights` (`domain/insights/CollectionInsightsSnapshot.kt`) combines plants, `getAllLogs()`, `getAllIssues()`, `AwayPeriodRepository.observeAll()`, the seasonal amplitude and a day-change trigger (value ignored; `nowProvider()` read once per emission). `CollectionInsightsViewModel` injects `dayChange: Flow<LocalDate> = dayChangeTicker()`; tests use a replaying `MutableSharedFlow`, never `advanceUntilIdle()` against the real ticker.
- **Builder:** `CollectionInsightsBuilder.build()` is pure. Window = `today.minusDays(29)..today` by `toLocalDate()`. Only `Plant.isVisible` plants count anywhere.
- **Late watering:** per consecutive WATER pair `(previous, current)` with `current` in the window, late iff `current`'s day is after `previous`'s day plus the interval in effect on `current`'s day (`effectiveWateringIntervalDaysForDisplay`; dormant cadence: `max(previous + cadence, cycleStart)`; suspended dormancy and no interval: never late). A pair for which `AwayPeriods.spansAway` is true is never late. A plant is listed at `LATE_THRESHOLD` (2) or more; order is count desc, name, id. The previous log may lie before the window.
- **Care counts:** fixed order WATER, FERTILIZE, PRUNE, REPOT, PHOTO, PROPAGATE, CUSTOM; zeros omitted; retired CHECK/MIST/NOTE never counted.
- **Issues:** active only, `daysActive` in calendar days (0 = started today), longest first. Styled with `IssuePurple`; rows open the plant's Issues tab, late rows the Water tab.
- **Empty states:** every section shows its own "Nothing to act on" line when empty; no visible plants shows one page-level empty state. No "currently overdue" marker (it would have to come from `TodayCareRepository`).
- **Tests:** `CollectionInsightsBuilderTest`, `CollectionInsightsViewModelTest`, instrumented `CollectionInsightsContentTest` (visible text and row actions only).
