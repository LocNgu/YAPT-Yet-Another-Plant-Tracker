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

**`CalendarViewModel` has two consumers of the ticker (`plantsWithStatus` and `plantsByDay`), and they
must never disagree on what day it is (#550 review round 1) — and a day must never be observed paired
with a statuses list computed for a *different* day (#550 review round 2).** Two related-but-distinct
bugs, fixed in two steps:

*Round 1 — two independent ticker collections.* The ticker (`dayChangeTicker()`, the constructor's
`dayChangeFlow`) is cold — each `combine()` that collects it independently re-runs the flow builder's
own `while (true) { …; delay(…) }` loop from scratch, on its own clock read, computing its own delay to
the next midnight. Folding the raw `dayChangeFlow` into *two* separate `combine()`s (as the first
version of this ADR did) therefore ran two independent ticker loops that could tick a moment apart in
real usage — invisible in the original ViewModel tests because they inject one *hot*
`MutableSharedFlow`, which naturally broadcasts one value to every collector regardless of how many
`combine()`s read it. Fixed by hoisting a single shared, hot source: `private val today:
StateFlow<LocalDate> = dayChangeFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
LocalDate.now())`, so the cold ticker is collected at most once regardless of how many derived flows
subscribe to it.

*Round 2 — the shared `today` alone wasn't enough.* With `plantsByDay = combine(plantsWithStatus,
_visibleMonth, today)`, that `combine()` re-runs as soon as **either** upstream flow emits — and at
midnight, `today` ticks *before* `plantsWithStatus` finishes rebuilding (`buildStatus()`'s suspend
`careLogRepository` calls take real time). `plantsByDay` could therefore momentarily fire with the
*new* `today` paired with the *old*, not-yet-rebuilt `plantsWithStatus` value, grouping stale
due/overdue statuses under the new day — the same class of inconsistency the round-1 fix was meant to
remove, just one level further down the chain. Fixed by combining the day and its statuses into one
atomic value that travels together: a private `StatusesForDay(day: LocalDate, statuses:
List<PlantCareStatus>)` built by `combine(allPlants, logCount, seasonalAmplitude, today) { ... ->
StatusesForDay(day, statusList) }`. Both public flows are now *derived* from this single
`statusesForDay: StateFlow<StatusesForDay>` rather than each reading `today` independently:
`plantsWithStatus = statusesForDay.map { it.statuses }.stateIn(...)`, and `plantsByDay =
combine(statusesForDay, _visibleMonth) { forDay, month -> computePlantsByDay(forDay.statuses, month,
forDay.day) }`. Since a day and its statuses are only ever produced together, by the same `combine()`
invocation, they can no longer be observed out of step with each other — there is no longer a second,
independent read of `today` anywhere downstream of `statusesForDay` to race against.

`PlantListViewModel.plantsWithStatus` has only one consumer of its own `dayChangeFlow` (folded into
`sortOrderOrDayChanged`) and no `plantsByDay`-shaped second stage reading a date derived from it, so
neither the round-1 nor round-2 fix applies there — both are specific to `CalendarViewModel`'s two-stage
shape.

`CalendarViewModelTest` carries a regression test for each round: `` `plantsWithStatus and plantsByDay
share one ticker collection, never two independent ones` `` (round 1) uses a cold flow that emits a
*different* date on each independent collection to assert the ticker's collection count stays at 1 no
matter how many of the two derived `StateFlow`s are subscribed to. Round 2's test, `` `plantsByDay's
day key always matches the day its statuses were actually built for` ``, asserts the resulting invariant
directly (a day tick's single `plantsByDay` emission pairs the new day with the fully-rebuilt statuses,
never a stale day1-era pairing under the day2 key) rather than forcing the exact race window — a
timing-based repro (gating a mocked `careLogRepository` suspend call mid-rebuild after a day tick, with
`runTest`'s scheduler unified with `Dispatchers.Main`'s per the `PlantDetailScheduleSettingsActionsCoalescingTest`
precedent) was tried and, empirically, could not be made to fail against the pre-round-2-fix code: MockK's
`coEvery`/`coAnswers` resolve synchronously by default, and even with a real `delay()` standing in for
`buildStatus()`'s asynchronous Room queries, the interleaving needed to observe the stale pairing never
reproduced under the shared test scheduler. The round-1 bug (two independent ticker collections) *was*
directly reproducible with a cold flow because it only requires two independent *subscriptions*, not a
suspended-mid-rebuild race; round 2's bug requires a genuine asynchronous gap inside a single rebuild,
which the test harness could not be made to open reliably. The structural fix (one atomic `combine()`
producing `StatusesForDay`) stands regardless — the invariant test is the practical regression guard.

**The VM's `today` is public for exactly one reason: the UI layer needs the same value, not its own
independent read of "now" (#550 review round 3).** `CalendarScreen` used to seed `val today = remember {
LocalDate.now() }` once per composition, driving `CalendarDayCell`'s `isToday` highlight and
`CalendarDaySheet`'s "Today" title/section header. A `remember` block never re-evaluates on its own, so a
Calendar screen left open and foregrounded across midnight kept both highlighting and labelling
yesterday's cell as "Today" even after `plantsByDay` had already rolled over — the same underlying gap
(no day-change trigger) this whole ADR exists to close, just one layer further out, in the UI rather than
a `combine()`. Fixed by making `today: StateFlow<LocalDate>` public on `CalendarViewModel` (previously
`private`) rather than adding a second `dayChangeFlow` collection for the screen to read from — the
screen now collects it via `collectAsStateWithLifecycle()`, same as every other VM-owned `StateFlow` it
reads. `currentMonth = remember { YearMonth.now() }` (which only seeds the calendar's ±1200-month range
and initial visible month) is deliberately untouched — moving the visible month out from under the user
at midnight is not the desired behavior, unlike the highlight/label, which is expected to track the real
current day live.

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
- A multi-stage derived-flow pipeline (a "day" value feeding a second flow that also depends on a
  first flow built from that same day) needs the day and the derived value combined into one atomic
  emission, not read from two independently-updating flows — the round-2 fix's general shape, not
  specific to dates. `CalendarViewModel`'s `plantsWithStatus`/`plantsByDay` split is the first place
  this app hit that shape; any future two-stage `combine()` chain sharing an upstream trigger should
  default to the same "combine once, derive twice" pattern rather than re-reading the trigger flow at
  each stage.
- A ViewModel's day-change value is exposed publicly once the UI layer itself needs to know "what day is
  it" for anything beyond what a derived `StateFlow` already encodes — `CalendarScreen`'s `isToday`
  highlight and "Today" title/section label are UI-owned decisions that can't be pushed down into
  `plantsByDay`'s `Map<LocalDate, DayEntry>` shape, so they need their own read of the same `today`
  rather than a screen-local `remember { LocalDate.now() }`.
