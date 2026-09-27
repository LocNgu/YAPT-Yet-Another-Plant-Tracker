# Technical ADR-0035: A shared day-change ticker flow triggers midnight recomputation

**Status**: accepted

**Date**: 2026-09-27

## Context

`PlantListViewModel.plantsWithStatus`'s `combine()` (its `CARED_FOR_TODAY` sort branch, which calls
`DateUtils.todayRangeMillis()`) and `CalendarViewModel.plantsWithStatus`/`plantsByDay` (due/overdue
status via `CareSchedule.computeStatus()`'s default `now`, and `plantsByDay`'s own direct
`LocalDate.now()` call) all depend on the current wall-clock date, but none of their `combine()`
upstream flows (plant list, care-log count, sort order, room filter, seasonal amplitude, visible
month) ever emits purely because time advances. The date math inside each block is calendar-day
correct in isolation (technical ADR-0013/0034), but the block itself only re-runs when some unrelated
state changes — a new care log, a room-filter change, a sort toggle. A plant watered late at night
under an active "Cared for today" sort stays shown as cared-for-today indefinitely past midnight until
something unrelated forces a recompute; Calendar's today/overdue groupings go stale the same way
(#550).

Two mechanisms were considered besides a ticker: an `ON_RESUME` lifecycle refresh, and a
`BroadcastReceiver` for `ACTION_DATE_CHANGED`/`ACTION_TIME_CHANGED`/`ACTION_TIMEZONE_CHANGED`. Both
were rejected for this issue — resume-triggered refresh doesn't cover a screen left open and
foregrounded across midnight (the most direct read of the bug report), and a broadcast receiver adds a
manifest-registered component and a second recomputation trigger to reason about for a problem a plain
coroutine flow already solves. Manual system clock/date/timezone changes while the app is foregrounded
are explicitly out of scope.

## Decision

A single reusable primitive, `dayChangeTicker(zone, nowProvider, maxPollIntervalMs): Flow<LocalDate>`
(`util/DayChangeTicker.kt`), mirroring `QuickLogUseCase`'s injectable `nowProvider: () -> Long =
System::currentTimeMillis` convention:

```kotlin
fun dayChangeTicker(
    zone: ZoneId = ZoneId.systemDefault(),
    nowProvider: () -> Long = System::currentTimeMillis,
    maxPollIntervalMs: Long = DAY_CHANGE_TICKER_MAX_POLL_INTERVAL_MS
): Flow<LocalDate> = flow {
    var lastEmitted: LocalDate? = null
    while (true) {
        val today = /* nowProvider() as LocalDate in zone */
        if (today != lastEmitted) {
            emit(today)
            lastEmitted = today
        }
        val untilMidnightMs = /* Duration to next local midnight, via ZonedDateTime */
        delay(untilMidnightMs.coerceAtMost(maxPollIntervalMs))
    }
}
```

- **Emits immediately on collection**, so folding it into a `combine()` never blocks that combine
  waiting on a first value.
- **Recomputes the delay from a fresh clock read every loop**, using `ZonedDateTime`-based
  calendar-day math (technical ADR-0013/0034) — never a fixed 24h span.
- **Caps each delay** at `maxPollIntervalMs` (a few minutes) and **only emits on an actual date
  change**. Android's `delay()` on the main dispatcher rides on `SystemClock.uptimeMillis()`, which
  pauses during deep sleep — a single long-scheduled delay can therefore fire late once the device
  wakes. The cap bounds how late; re-checking the date (rather than assuming a wake means midnight
  passed) is what makes the ticker self-correcting regardless of exactly when a wake happens — an
  early or late wake with no real date change just re-arms the delay, never emits a duplicate.
- **`stateIn(..., SharingStarted.WhileSubscribed(...))` gives a second, independent correction for
  free**: leaving a screen stops the upstream collection; returning re-subscribes and re-collects the
  ticker from scratch, re-emitting today's date immediately — so a process backgrounded across
  midnight is already correct on the next collection, even before the ticker's own in-flight delay
  would have fired.

Each `combine()` folds the ticker in with its emitted value ignored (`_`). `combine()`'s typed
overloads cap out at 5 flows; where a consumer was already at that limit
(`PlantListViewModel.plantsWithStatus`), the ticker is folded into one existing flow first
(`combine(_sortOrder, dayChangeTicker()) { sort, _ -> sort }`) rather than restructuring to the
array-based `combine()` overload, keeping every existing typed lambda parameter unchanged.
`CalendarViewModel.plantsByDay`'s `today` parameter is now sourced from the ticker's own emitted
value instead of a second, independent `LocalDate.now()` call inside the lambda — using the flow's own
value (rather than a fresh real-clock read that happens to be correct whenever the block runs) is what
actually ties the recomputation to the trigger.

Both `PlantListViewModel` and `CalendarViewModel` constructor-inject the ticker as `Flow<LocalDate>`
defaulting to the real `dayChangeTicker()`, so `Factory` needs no change (the default applies), while a
test can substitute a controllable `MutableSharedFlow<LocalDate>`/`MutableStateFlow<LocalDate>`.

**Never call `advanceUntilIdle()` in a test that collects the real ticker.** Its `while (true) { …;
delay(…) }` always re-schedules another delayed continuation before suspending, so
`advanceUntilIdle()` finds infinite future work and hangs. `DayChangeTickerTest` instead drives it with
`advanceTimeBy(ms)` + `runCurrent()` using an explicit, bounded amount per step.

**Every `PlantListViewModel`/`CalendarViewModel` test needs an explicit non-real ticker, full stop —
there is no free pass for a test that doesn't touch day-change behavior.** The initial implementation
assumed `MainDispatcherRule`'s `Dispatchers.Main` (an `UnconfinedTestDispatcher` with its own
`TestCoroutineScheduler`) and a plain `runTest {}`'s own coroutine scope were independent enough that
the real default ticker's `delay()` would simply park on a scheduler nothing in a test ever advances.
That assumption was wrong in practice: `advanceUntilIdle()` called inside a `Turbine` `.test { }` block
against a VM built with the real default ticker genuinely hung (`kotlinx.coroutines.test
.UncompletedCoroutinesError`, 60 real seconds) across a dozen-plus of the pre-existing tests in both
files once the real ticker was wired into the default constructor value — `UnconfinedTestDispatcher`'s
eager/inline execution model runs enough of the combine chain's setup synchronously as part of a
collector's own call stack that the two schedulers end up entangled for this exact
Turbine-plus-`advanceUntilIdle()` shape, not cleanly decoupled. The fix is not a scheduler-sharing trick
but the plain one: every `PlantListViewModelTest`/`CalendarViewModelTest` test passes a class-level
`private val dayChangeFlow = flowOf(LocalDate.now())` (a single-emission, non-delaying stand-in) as the
constructor's last argument, including the ~140 tests that have nothing to do with day-change behavior;
the handful of tests that actually exercise the ticker's effect inject their own controllable
`MutableSharedFlow<LocalDate>` instead.

## Consequences

- `PlantListViewModel.plantsWithStatus` (`CARED_FOR_TODAY` membership, and by extension every
  due/overdue/due-soon status derived in the same combine) and `CalendarViewModel.plantsWithStatus`/
  `plantsByDay` (due/overdue status and today/future day groupings) all now recompute at local midnight
  with no other state change required.
- `dayChangeTicker()` is the shared primitive for any future screen with the same shape — a `combine()`
  whose output depends on wall-clock date needs this flow folded in, not a fresh ad hoc `LocalDate.now()`
  call inside the lambda.
- Manual system clock/date/timezone changes while the app is foregrounded, and an `ON_RESUME` redundant
  refresh, remain explicitly out of scope — `WhileSubscribed` re-subscription already covers the
  backgrounded-through-midnight case, and a broadcast-receiver-driven immediate reaction to a manual
  clock change was judged not worth the added component for this issue.
- Every `PlantListViewModel`/`CalendarViewModel` test constructor call needed a non-real ticker
  argument, not just the tests that exercise day-change behavior — a one-time, mechanical touch of
  every pre-existing test in both files, not a design cost this pattern otherwise carries forward: a
  future ViewModel adopting `dayChangeTicker()` only needs its own tests to follow the same one-line
  `flowOf(LocalDate.now())` convention from the start.
