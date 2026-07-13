# Technical ADR-0017: Add `kizitonwose/Calendar` for the calendar screen

**Status**: accepted

**Date**: 2026-07-12

## Context

Product ADR-0019 introduces a Calendar top-level tab (issue #414). The screen needs:
- A month grid with locale-aware first-day-of-week.
- Next/previous month navigation via swipe or arrows.
- Fully custom day cells (day number + count-badge pill, "today" highlight, tap target).
- Compose-native rendering with straightforward integration into the existing Material 3 theme.

Options considered:

1. **Hand-rolled `LazyVerticalGrid`** with month-boundary math. Cheap in dependencies but reinvents month paging, locale-aware first-day-of-week, and day-across-month-boundary rendering — all of which have subtle correctness pitfalls (leap years, week rows, locale weekday order).
2. **Material 3 `DatePicker`** repurposed as a display surface. Not designed for custom day cells; badge rendering fights the component's internal layout. Would be visually inconsistent with the rest of the app.
3. **`boguszpawlowski/ComposeCalendar`** — a similar-purpose library. Less active maintenance than kizitonwose; smaller community; API is less flexible for custom cells.
4. **`com.kizitonwose.calendar:compose`** — Android-only Compose artifact of the widely-used kizitonwose/Calendar library. Explicitly designed around custom cell composables (`dayContent = { day -> ... }`), month/year paging, locale-aware week layout, and Compose lazy scrolling. Well-maintained (regular releases, active issues).
5. **`com.kizitonwose.calendar:compose-multiplatform`** — same library, Compose Multiplatform artifact. Useful only if the project ever expands beyond Android/Compose, which is not on the roadmap.

## Decision

Add **`com.kizitonwose.calendar:compose`** as a new dependency. Pin the latest stable version at implementation time inline in `app/build.gradle.kts` (per the project's "no `libs.versions.toml`" convention documented in the top-level CLAUDE.md).

The library exposes `HorizontalCalendar` / `VerticalCalendar` composables whose `dayContent`, `monthHeader`, and `monthFooter` slots accept arbitrary composables. This gives us complete freedom over day-cell styling (badge pill, `SageGreen` / `OverdueRed` colour switch on today, "today" ring), matching the two-tier badge decision per issue #414.

**API level.** The library uses `java.time.YearMonth` and `java.time.LocalDate`. YAPT already assumes `java.time` types are available (`toLocalDate()` in `DateUtils.kt`, `ChronoUnit` in `CareSchedule.kt`) with `minSdk = 26` — no `coreLibraryDesugaring` is added or needed.

**Compose BOM compatibility.** The library declares its own Compose Foundation dependency; the Compose BOM (2026.05.01) aligns transitive versions. We do not override the BOM.

## Consequences

- One new third-party dependency (~small footprint, single-purpose, actively maintained). Committed to `app/build.gradle.kts` with an explicit version string.
- All month/paging correctness (leap years, locale first-day-of-week, week rows) is delegated to a well-tested library rather than hand-rolled — reduces the surface area of new correctness bugs.
- Day cells stay under our control (custom Compose slot), so styling matches the app's nature-themed palette and the badge decisions per issue #414 without fighting the library.
- If the library ever goes unmaintained, migration to option (1) — hand-rolled `LazyVerticalGrid` — is scoped to the Calendar screen and doesn't leak into other layers.
- No ProGuard rules are needed: the library ships Compose composables and no reflection-based initialisation.
- No `libs.versions.toml` is introduced; the project's inlined-dependency convention is preserved (see top-level CLAUDE.md "Patterns & Conventions").
