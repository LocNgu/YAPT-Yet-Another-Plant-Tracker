# Product ADR-0049: Fertilizing active seasons

**Status**: accepted — disabled-last-chip enforcement and visible current-season label clauses amended by [ADR-0051](0051-fertilizing-season-chip-selection-cues.md)

**Date**: 2026-09-23

## Context

Product ADR-0045 (#286) shipped four independent per-season fertilizing intervals
(`fertilizingIntervalSpring/Summer/Autumn/Winter`), each falling back to `fertilizingIntervalDays`,
plus a "Same for all seasons" switch on Add/Edit Plant. That PR merged after the 0.31.0 release cut and
was never tried on a device. Once reviewed against real usage, the four-slot model was judged more than
anyone needs: almost nobody wants a *different cadence* per season, they want fertilizing to simply stop
during the seasons a plant doesn't need it, at the one cadence they already set. ADR-0045's Decision
section is explicit that "no season slot means pause; dormancy is the only pause mechanism" — the new
model reverses exactly that clause: an inactive season *is* itself a fertilizing-only pause, independent
of the per-plant dormancy window (product ADR-0044).

Because #791 (which shipped ADR-0045) merged after the 0.31.0 release (#789/#790), DB v15 and `.yapt`
backup v18 exist only on `develop`, and the #286 changelog entry was still under `[Unreleased]`. No
released build ever saw the four-column shape, so this reversal could redefine those versions in place
rather than adding a new migration on top of dead columns — see #795's issue body for the three
migration options considered; option 1 (redefine in place) was chosen specifically because nothing
released depends on the old shape.

## Decision

Replace the four discrete interval slots with the existing single `fertilizingIntervalDays` (unchanged:
1–180 days, existing slider, existing on/off switch) plus one new column: a per-plant set of which
hemisphere-aware seasons fertilizing is active in. `Plant.fertilizingSeasons: Set<FertilizingSeason>`
defaults to every season, so an existing plant (or one created with every chip left on) behaves
bit-for-bit as it did before #286 ever shipped.

**Storage.** `PlantEntity.fertilizingSeasons: String?` — comma-separated `FertilizingSeason` names in
canonical enum order (`SPRING,SUMMER,AUTUMN,WINTER`). All four selected is written as `null`, so the
default state has exactly one storage form matching every migrated row. Reading drops unknown tokens
(`runCatching { FertilizingSeason.valueOf(...) }`, the standard "enums stored as String" convention); an
empty or entirely unparseable string falls back to every season rather than a permanently-paused empty
set. `SeasonalFertilizing.encode()`/`decode()` is the one place this conversion lives; the repository and
`BackupManager` both call it rather than each keeping their own copy.

**Due-date rule.** The raw due date is unchanged from today: `lastFertilizedAt + fertilizingIntervalDays`,
or `createdAt + FIRST_FERTILIZE_GRACE_DAYS` before the first FERTILIZE log. The grace date goes through
the identical rule below as any other raw date — a deliberate reversal of ADR-0045, which explicitly
exempted the grace path from season slots; under the new active-season model, exempting it would let a
plant become due during a season the user just said fertilizing shouldn't happen in. All four seasons
selected is an unconditional early-out: the raw date is returned unchanged, however overdue, so every
existing plant is bit-for-bit identical to before this ADR.

The raw date's own season is not enough on its own: a raw date in an active month can be followed by an
inactive stretch, so the rule branches on whether the raw date is still in the future relative to *today*:
- **Future raw date** (after today): the simple forward shift — unchanged if its own hemisphere-aware
  season (`SeasonalFertilizing.season()`, unchanged from ADR-0045) is active, else the start of day
  (system default zone) of the 1st of the first following month whose season is active, checked at most
  12 months ahead (the active set is never empty by construction, so this always terminates well before
  the bound).
- **Past-or-present raw date** (on or before today): evaluated against *today's* season instead. If
  today's season is inactive, the result is the same forward shift as above, starting the search from
  today's month — a future date, so the plant reads not due and not overdue right now. If today's season
  is active, the rule finds the start of the *contiguous run* of active months ending at today's month —
  walking backward one month at a time while the previous month's season is also active, which correctly
  spans a run that wraps the year boundary (e.g. Autumn+Winter active bridges Dec→Jan). The raw date is
  used unchanged when it already falls on or after that run's start (still overdue from the real raw date,
  exactly as before this correction); otherwise the run's start itself is the due date — due today while
  still in that first calendar month, overdue once past it.

Consequence: a plant is never due or overdue during an inactive season, and on re-entry it is due exactly
on the first day of the newly active season rather than carrying forward however overdue the raw date had
become — including across a gap that started inside a still-active month.

Dormancy (product ADR-0044) is unchanged and stays independent: it suppresses `isFertilizingOverdue`/
`isFertilizingDueSoon` on top of whatever this rule computes, the same as it always suppressed the
single-interval model before #286. Active seasons and dormancy answer different questions — one is "does
this plant fertilize this time of year at all," the other is "is this plant asleep right now" — and a
plant can independently be in an inactive season, dormant, both, or neither.

**UI.** One shared composable, `FertilizingSeasonsSelector` (`ui/components/`), a labeled row of four
Material 3 `FilterChip`s (Spring/Summer/Autumn/Winter) — replacing both the four number fields plus "Same
for all seasons" switch on Add/Edit Plant and, per this ADR (reversing ADR-0045's own reasoning that a
four-field editor was too dense for inline use), the read-only `SeasonalFertilizingSummary` on Plant
Detail's Fertilize tab. With chips instead of four text fields, the density problem ADR-0045 cited for
keeping the tab read-only no longer applies — the selector auto-saves inline exactly like the tab's
existing interval slider. The chip for the current hemisphere-aware season is visibly and audibly marked
(its own visible label, not a separate always-present affordance). The last remaining selected chip cannot
be deselected — enforced in both the composable (a disabled chip, structurally unable to fire a tap) and
the ViewModel layer (rejecting an empty set), so the invariant holds regardless of which surface is
driving it. When the current season is inactive, the Fertilize tab additionally shows a short
"Out of season — next fertilizing `<date>`" line built from the already-computed due date.

**Migration.** DB v15 and backup schema v18 are redefined in place rather than superseded by a new
version, since neither shipped in a release: `MIGRATION_14_15` becomes a single
`ALTER TABLE plants ADD COLUMN fertilizingSeasons TEXT`, and `BackupPlant`'s four nullable interval fields
become one nullable `fertilizingSeasons: String?`. `DB_VERSION` stays 15; `CURRENT_SCHEMA_VERSION` stays
18. The one accepted cost: a device that already installed a `develop` build at v15 fails Room's identity-
hash check on next launch and needs Clear Data or a reinstall — acceptable because no released build ever
reached v15, so no real user's data is at risk of that failure.

## Consequences

- Every existing plant (every chip on, i.e. `fertilizingSeasons == null`) behaves exactly as it did in
  0.31.0 — same due dates, same reminders, no migration-day behavior change.
- A plant with a genuinely narrower active-season set is never flagged due or overdue outside it, and
  reappears on the calendar exactly on the first day it becomes relevant again, not "overdue since
  whenever."
- The four-cadence-per-season use case ADR-0045 supported (e.g. feed lightly in spring, heavily in
  summer) is gone; a user who wants that now has to accept one cadence for however many seasons they mark
  active. This trade was made deliberately, on the belief that almost no one actually wanted per-season
  *cadence* — the fertilizing-only-pause use case is by far the common one, and it's what this model
  optimizes for.
- Inline editability on Plant Detail (chips, not a four-field editor) removes the "edit in Add/Edit Plant"
  round-trip ADR-0045 required — a strict UX improvement for the case this ADR keeps.
- No adaptive learning, confidence, or per-plant audit history for fertilizing, same as before — this ADR
  only changes what a plant's fertilizing schedule is configured to be, not whether it's ever adjusted
  automatically.
- Liquid-fertilizer pairing and notification wording are otherwise unchanged; the only behavioral change
  visible there is not being due at all while out of season.
