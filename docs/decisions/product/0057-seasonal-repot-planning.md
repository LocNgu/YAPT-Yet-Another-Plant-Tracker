# Product ADR-0057: Seasonal repot planning — plan a repot for a season, and prefer seasons for the recurring interval

**Status**: accepted

**Date**: 2026-09-29

## Context

Product ADR-0022 made the repotting reminder `(lastRepottedAt ?: createdAt) + interval`, picked in
months. That ignores the one thing that governs when a repot should happen: the season. A plant repotted
in July with a 12-month interval is reminded the following July even if its owner would rather wait for
spring, and there is no way to say "this plant is root-bound, but I'll repot it in spring" — the only
workarounds were a custom reminder with a hand-counted day number or remembering it. Product ADR-0022
also left the next repot date invisible outside the daily notification (#809).

Two additions follow: a **one-off planned repot** ("next spring"), and **preferred seasons** the
recurring interval date may land in ("every 2 years, in spring"). Both reuse the seasons of product
ADR-0049 (fertilizing active seasons): `FertilizingSeason`, `SeasonalFertilizing.season()`, the
`encode`/`decode` storage form, and the hemisphere from `SeasonalWatering.currentHemisphere()`.

Alternatives considered:

- **Plan granularity: a month or an exact date.** Rejected. A season matches how people think about
  repotting; a date is falsely precise, the same argument product ADR-0022 made for months over days.
- **Move the interval date forward only, as product ADR-0049 does for fertilizing.** Rejected. Repotting
  is a rare, deliberate event where the nearest good window matters more than never being early: last
  repot June 2025, two-year interval, spring preferred should read March 2027, not March 2028.
- **Model the plan like `wateringDueDateOverride` (product ADR-0039), which only defers (`maxOf`).**
  Rejected — see "Plan beats interval" below; a plan is intent about *when*, not a deferral of a
  computed date.
- **Store the plan as season + year.** Rejected in favour of a timestamp: the due date must stay fixed if
  the timezone or hemisphere changes later, and the label can be derived from a timestamp but not the
  other way round.
- **Require a repotting interval for a plan.** Rejected. Most plants have no interval set, and a plan
  should stand on its own.

## Decision

### One-off planned repot

- **Season granularity only.** The picker offers the next four seasons after the current one, each with
  the year of its first day (on 2026-09-29 in the northern hemisphere: Winter 2026, Spring 2027, Summer
  2027, Autumn 2027). The current season is not offered — planning it is "repot now".
- **Storage.** Two nullable `plants` columns: `repotPlanSeasonStartAt`, the target season's first day at
  start of day in the system zone, and `repotPlanMadeAt`, the instant the plan was made (`updatedAt`
  cannot serve — it changes on every edit). The season label and year are derived from the stored
  timestamp, hemisphere-aware at read time; the season's end is derived from the nearest season boundary
  to that timestamp, so a timezone shift of up to a day between planning and reading cannot shrink the
  season to a single day. A timezone or hemisphere change therefore never moves the due date.
- **Due for the whole season, overdue only after it ends.** A plan is due from the season's first day
  through its last, and overdue from the first day after the season until repotted or cleared —
  "overdue on March 2" reads wrong for a plan that covers all of spring. `CareSchedule` reports this as
  `isRepottingDueSoon` while in season and `isRepottingOverdue` afterwards, and
  `PlantCareStatus.repottingPlanSeasonEndAt` (the first day after the season; `null` when the due date is
  not a plan) lets a consumer tell "due, in season" from "overdue, season ended". This applies to plans
  only.
- **Plan beats interval.** When a plan exists it wins outright, including over an earlier interval date
  and with no interval configured; clearing it restores the interval date. This deliberately differs from
  product ADR-0039's `wateringDueDateOverride`, which only ever *defers* (`maxOf(computed, override)`): a
  reschedule postpones a date the schedule computed, while a repot plan replaces the interval date with
  the owner's stated intent, even when that is earlier.
- **Clearing.** A newly inserted REPOT log clears the plan when its local calendar day
  (`Long.toLocalDate()`, technical ADR-0013) is on or after the plan-made local day; a REPOT backdated to
  before that day leaves it alone (the same idea as #679). Both write paths do this —
  `QuickLogUseCase` (Plant Detail, Care "Repot…", bulk) and `AddCareLogViewModel` — through one shared
  `RepotPlanReset`, after the lifecycle reset so its full-row write cannot resurrect a stale plan, using
  a column-specific update (`PlantDao.updateRepotPlan`) so it cannot revert a concurrent write to any
  other column. Editing or deleting a REPOT log never clears or resurrects a plan.
- **Where it shows (v1).** The Repot tab, the Care tab (via `TodayQueueAggregator`), and the daily
  notification. A Plant List chip, a Calendar entry, and bulk planning from Plant List multi-select are
  follow-ups, as are month or exact-date plans and a note on the plan.

### Preferred seasons for the recurring interval

- **Storage.** `plants.repottingSeasons: String?`, the comma-separated `FertilizingSeason` names of
  product ADR-0049 via the shared `SeasonalFertilizing.encode`/`decode`; `null` means every season.
- **Every season is an unconditional early-out.** Due dates are then exactly what product ADR-0022 gave,
  for every existing plant.
- **Nearest preferred stretch.** When the raw date `(lastRepottedAt ?: createdAt) + interval` (a
  never-repotted plant's `createdAt` anchor goes through the same shift, mirroring product ADR-0049's
  first-fertilize grace date) lies outside a preferred season, it snaps to the **first day of the nearest
  preferred-season stretch** — a contiguous run of preferred seasons, which may wrap the year boundary.
  Nearest is measured to the stretch's first day, and an equidistant tie goes to the later stretch.
  Guards:
  - A raw date already inside a preferred season is unchanged, past or future.
  - **Minimum gap of half the interval.** A nearest candidate must fall at least half the interval after
    the last repot (or `createdAt`) day — the interval being the calendar days from that anchor to the raw
    date, halved with integer division (never less than one day, so a candidate on or before the anchor is
    always rejected). A rejected candidate falls forward to the next stretch, which is the stretch after
    the raw date and is always far enough away. Without it a plant repotted shortly before a preferred
    stretch would come due far sooner than its interval: repotted Jan 15 2026 with a 6-month interval and
    spring preferred has a raw date of Jul 14 2026, and the nearest spring start, Mar 1 2026, is only 45
    days after the repot (under the 90-day minimum) — so it is due Mar 1 2027 instead. The last repot
    June 2025 with a two-year interval is unaffected: Mar 1 2027 is 624 days after it, over the 365 needed.
- **Time-stable.** The shifted date depends only on the raw date, the preferred seasons, the hemisphere and
  the anchor — never on today's date, so the function takes no clock. A raw date already in the past that
  lies outside a preferred season goes through the same nearest-stretch rule as a future one; it is not
  re-evaluated forward from today. The shifted date can therefore land in the past, and that is the overdue
  state: an overdue plant stays overdue until it is repotted (or the plan or preferred seasons change),
  and never drops out of the Care queue or the notification because a raw date slipped past. Last repot
  June 2025, two-year interval, spring preferred is March 1 2027, overdue from March 2 2027 — and still
  March 1 2027 on June 16 2027, when the raw date (June 15) has passed, rather than jumping to March 2028.
- **Ordinary due-then-overdue rule.** An interval date shifted into a preferred season is due on its first
  day and overdue after it, like any interval date; the shift doesn't drift from day to day.
- **UI.** Season chips on Add/Edit Plant appear only while the repotting reminder is enabled, all four
  selected by default, reusing `FertilizingSeasonsSelector`; the last chip cannot be deselected and
  shows repotting-specific snackbar copy. No inline chips on the Repot tab (product ADR-0023).
- Dormancy (product ADR-0044) does not pause repotting; unchanged.

### Delivery

Delivered as three PRs: the data layer, pure domain logic, and this ADR first (no user-visible change;
nothing writes the new columns yet, so every existing behaviour is unchanged); the reminder and Care
surfaces second; the Repot tab and Add/Edit Plant UI, changelog, and What's New third.

## Consequences

- Product ADR-0022's due-date rule is amended (its Status line records this); its months-based interval,
  first-due anchor, and misting decision stand unchanged.
- The schema gains three nullable `plants` columns (Room DB 16 → 17, `MIGRATION_16_17`); `.yapt` backups
  round-trip them at schema 21, and older backups restore them as unset — no plan, every season.
- A repot plan is the first due date in the app that is stored intent rather than computed from a last
  care date; consumers must read `repottingPlanSeasonEndAt` rather than assume "past due date means
  overdue" for repotting.
- Accepted trade-off: because of the minimum gap, a plant whose nearest preferred stretch falls too soon
  after its last repot can wait up to a further season — repotted Jan 15 2026 with spring preferred it
  waits 13.5 months, not 6, for its repot reminder.
- Accepted trade-off: because the shift is time-stable, a long-neglected plant reads overdue since an old
  preferred season even during an off-season — e.g. overdue since last March while it is now October —
  rather than being pushed to the next stretch. This is deliberately unlike product ADR-0049's fertilizing
  rule, which re-evaluates against today: for a rare, deliberate repot, silently dropping an overdue plant
  from the reminders for months is the worse failure. Revisit if an off-season overdue reads as nagging.
- A plan made for a season is not cleared by time passing; it stays overdue after its season ends until
  the owner repots or clears it, so a forgotten plan keeps nagging rather than silently disappearing.
- Follow-ups: the Plant List chip, a Calendar entry, bulk planning, month or date plans, a plan note, and
  a "Planned for spring" group on the repotting overview page (#525).
