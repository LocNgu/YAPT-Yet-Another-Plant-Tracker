# Product ADR-0018: Date-group dividers on date-sorted plant list

**Status**: accepted

**Date**: 2026-07-04

## Context

The plant list supports date-based sorts (Watering due, Fertilizing due, Both due) alongside Alphabetical and Recently added. When sorted by a date-based order, the list is a flat scroll of cards — it's hard to see at a glance which plants are due *today* vs *tomorrow* vs later in the week (see #399). Users want to spot at a glance "these two are due Monday — I'll just water them Saturday instead" without reading each countdown chip individually.

Several decisions needed to be made:

1. **Which sorts get dividers?** All date-based sorts, or only some?
2. **Label format for the tail buckets** — exact weekday+date vs relative ("In 5 days")?
3. **Overdue granularity** — one bucket for everything overdue, or one per overdue day?
4. **Per-day cap** — how many individual day buckets before collapsing into a catch-all?
5. **DESC direction** — does the group sequence stay fixed or reverse with the ASC/DESC toggle?
6. **Header interaction style** — sticky/collapsible, or plain?

## Decision

**Q1 — Which sorts get dividers:** Watering due, Fertilizing due, and Both due. Alphabetical and Recently added stay flat (no dividers, visually unchanged) — grouping by add-date is not obviously useful and alphabetical order has no natural day-based bucketing.

**Grouping key** = the due date of the active care type: `nextWateringDueAt` for Watering due and Both due; `nextFertilizingDueAt` for Fertilizing due.

**Q2 — Tail label format:** Weekday + date, e.g. `Sat, Jul 11`, via a localized `SimpleDateFormat("EEE, MMM d")`. Near-term buckets use plain labels: `Overdue`, `Today`, `Tomorrow`.

**Q3 — Overdue granularity:** A single `Overdue` group covers everything past due, regardless of how many days overdue.

**Q4 — Per-day cap:** Per-day buckets for Today, Tomorrow, +2 days, and +3 days out. Everything ≥4 days out collapses into a single `Later` bucket.

**New edge case surfaced during spec:** plants with no due date for the active care type (no interval configured) are currently sorted nulls-last. They get a terminal `Not scheduled` bucket.

**Bucket order (ascending sort direction):** `Overdue → Today → Tomorrow → +2 → +3 → Later → Not scheduled`.

**Q5 — DESC direction:** The group sequence reverses along with the toggle, including the `Not scheduled` bucket moving from the tail to the head. Grouping is a pure transform layered on top of the already-sorted list: it partitions statuses into buckets (preserving each item's relative order within its bucket, which always follows the existing per-item sort order), then orders the buckets themselves — canonical (`Overdue → … → Later → Not scheduled`) for the default direction, fully reversed for the toggled direction. Both due has no direction toggle (ADR-0004), so it always reads in canonical order.

**Q6 — Header style:** A plain, non-sticky, non-clickable inline header row: a label (primary-tinted) plus a thin `HorizontalDivider`. No `stickyHeader`, no tap target.

Only non-empty buckets render a header. Grouping recomputes over the already-filtered, already-sorted list on every change (room filter, Unassigned filter, Both-due filter, ASC/DESC toggle).

See `PlantListItem.kt` (`DateBucket`, `PlantListItem`, `groupPlantsByDueDate`), `PlantListViewModel.plantListItems`, and `PlantListScreen.DateGroupHeader`.

## Consequences

- Users sorting by Watering due / Fertilizing due / Both due get an at-a-glance view of which plants cluster on which day, supporting the "water a day early" workflow described in #399.
- No existing ADR is superseded: ADR-0004 (default sort order and direction toggle rules) and technical ADR-0013 (calendar-day comparisons) both stand unchanged — this feature is a display-only layer on top of the existing sort.
- Grouping logic lives in a small, pure, directly-unit-testable function (`groupPlantsByDueDate`) rather than being baked into the Composable, keeping the LazyColumn rendering dumb.
- Bucket order is derived independently of `applySortOrder()`'s nulls-last comparator (which always sorts null-due plants last within the flat `plantsWithStatus` list, regardless of direction — an existing, tested invariant per ADR-0004 that is left unchanged). `groupPlantsByDueDate` re-partitions the already-sorted list by bucket and reorders the buckets themselves, so `Not scheduled` correctly moves to the front when the toggle is ASC even though the underlying flat sort never physically moves null-due plants ahead of scheduled ones.
- Alphabetical and Recently added sorts are entirely unaffected — no headers are shown and card layout is unchanged.
