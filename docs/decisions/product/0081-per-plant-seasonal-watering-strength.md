# Product ADR-0081: A plant can follow its own seasonal watering strength

**Status**: accepted

**Date**: 2026-11-05

**Amends**: [ADR-0026](0026-computed-seasonal-watering-factor.md) (its "single global setting, not per-plant" clause).

## Context

Issue #700 (split from #580). Product ADR-0026 made the seasonal watering curve a computed function of the
calendar with one global amplitude (Off / Mild / Standard / Strong) and a per-plant `pinIntervalToBase` as the only
plant-level escape hatch. That is binary: follow the curve exactly or opt out entirely. A plant whose real interval
barely changes through the year drifts 12-35% early or late on the curve (#580's simulation), and the learner
(which de-seasonalises each observed gap) corrects it only slowly. Nothing sat between "the household strength" and
"flat".

Considered:

- **A. An explicit per-plant picker (chosen).** A nullable per-plant strength.
- **B. Detect a plant fighting the curve and prompt.** Needs A's storage to record the answer, so it is not an
  alternative to it. Split out to #995.
- **Learn a per-plant amplitude.** Rejected: it needs two or more years of data per plant and is the per-plant
  seasonal learning that #285 was closed for. The strength stays something the user states, never something the app
  learns.

## Decision

- **Storage:** a nullable `wateringAmplitudeOverride` (a `SeasonalAmplitude` name, `TEXT` in Room, `String?` in
  backup). `null` means "App default", which is every existing plant and every pre-v25 backup. An unknown stored
  value reads as `null`.
- **One 3-way control: App default / Custom / Pin.** Custom offers Mild, Standard and Strong; Pin is the existing
  `pinIntervalToBase` column, so pinned plants and old backups are untouched. Pin and Custom are mutually exclusive in
  the UI **and** in the setters: choosing Pin clears the override, choosing Custom clears the pin. The control is on
  Add/Edit Plant and the Plant Detail Water tab, and the Water tab's curve preview draws the plant's own strength.
- **Custom has no Off.** A custom Off and Pin behave identically everywhere: both resolve to amplitude 0, so the
  due date is the literal `wateringIntervalDays`, the stored base is never rewritten by a manual edit or an observed
  gap, the learner's input and its de-seasonalisation are the literal and the raw gap, and "Why this date?" hides the
  season row. Only the name and intent differ, so offering both would be two controls for one behaviour. A stray
  `OFF` override (only reachable from a hand-edited backup) still resolves to 0 and is shown as Pin.
- **One resolver.** `resolveWateringAmplitude(pinned, override, global)` /
  `Plant.effectiveWateringAmplitude(global)` (`domain/schedule/WateringAmplitude.kt`): Pin gives 0, a custom strength
  gives its own value, otherwise the global value. Every per-plant amplitude read goes through it, at the lowest
  layer that has the plant (`CareSchedule`'s effective-interval step, `LiveWateringInterval`, the "Why this date?"
  builder, `QuickLogUseCase`, `AdaptiveWateringObservation`, `SeasonalGraduationFixup`, the Add/Edit and Plant Detail
  manual-edit paths). Callers keep passing the *global* amplitude they already read; they never pre-resolve it. This
  keeps the schedule and the learner unable to disagree (the class of bug #572 and #631 fixed).
- **A strength change does not touch the base.** The season-neutral `wateringBaseIntervalDays` is kept, no
  `WateringAdjustment` row is logged, and the effective interval and due date may move. Typing a new interval in the
  same save de-seasonalises with the strength the form now shows.
- **Not changed:** no per-plant peak day (a summer-dormant species is a dormancy window, product ADR-0044/0046);
  dormancy cadence ignores strength; a global Off with a plant on Custom Strong still swings, because the override
  wins.

## Consequences

- Product ADR-0026's "single global setting" clause no longer holds; its Status line points here. The curve, the
  computed-not-learned principle and `pinIntervalToBase` stand.
- One Room migration (`MIGRATION_21_22`, a pure nullable `ALTER TABLE`) and backup schema 25.
- Any new code that reads the global amplitude for a plant's interval must resolve it per plant first; a raw global
  read for a plant is now a bug.
- #995 can build detect-and-prompt on this storage without a second schema change.
