# Technical ADR-0021: Multiplicative + confidence-weighted watering interval adaptation

**Status**: accepted

**Date**: 2026-08-19

## Context

`CareSchedule.computeSuggestedInterval()` nudges the stored watering interval by exactly ±1 day per
feedback event. That step size is not scale-invariant (1 day is 25% of a 4-day interval and 5% of a
21-day one), and it treats a single mismatched reading the same as the third consecutive confirmation
of a real trend.

#568 (Part 1 of 5, split from #285) replaces this with a multiplicative correction whose step size is
weighted by a per-plant confidence counter, behind the `adaptive_watering` developer-mode flag. This
ADR records the update rule, the confidence state machine, and the corrected convergence figures — the
kind of detail a later "simplification" would otherwise silently break.

## Decision

### Update rule

```
target = observed × { 1.25   feedback == TOO_SOON (still wet)
                       1.00   feedback == JUST_RIGHT
                       0.82   feedback == TOO_LATE (too dry) }
base   = base + g(confidence) × (target - base)
```

`observed` is the actual gap between the last two waterings, as today. The 1.25/0.82 asymmetry
encodes that chronic overwatering is usually fatal and underwatering is usually recoverable, so the
model lengthens readily and shortens cautiously — deliberately mild; convergence speed comes from the
gain, not the multipliers.

`g` is read from a fixed table indexed by confidence 0-5:

| confidence | 0 | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|---|
| `g` | 0.60 | 0.45 | 0.35 | 0.28 | 0.22 | 0.15 |

Clamps: the result of each step is clamped to ±40% of the pre-step `base`, then the whole result is
`.coerceIn(1, 180)`. The 40% per-step clamp already guarantees the [1, 180] floor/ceiling can't be
crossed from a legal starting value in one step, but the final `coerceIn` is kept as a hard backstop
(and is what actually satisfies the #446 regression guard: `observed == 0` still can't reach a 0-day
result, since the per-step clamp on a `base >= 1` floors the raw result well above 0 before rounding).

The 40% bound is applied to the *continuous* value before rounding to a whole day (the stored unit).
Rounding can then add up to another half-day of slop on top — for a small `base` this is a meaningful
fraction (e.g. `base = 7` clamped to the continuous ceiling `9.8` rounds to `10`, a 42.9% change) — but
this is an unavoidable consequence of a day-granular schedule, not a defect in the clamp: the
`CareScheduleAdaptiveReplayTest` "no runaway" assertion accounts for exactly this rounding bound
(`0.40 + 0.5 / previousBase`) rather than the unrounded 0.40.

### Confidence: derived streak, stored counter

The state machine needs the *direction* of the last few corrections to detect a same-direction run,
but only one new column was added: `Plant.wateringConfidence: Int?` (0-5, `null` = never adapted). The
direction itself is **not** stored. `CareSchedule.correctionStreak(recentFeedback: List<WateringFeedback?>)`
derives a signed run length from the last 3 WATER logs' feedback (`CareLogRepository.getRecentWaterings`),
most-recent-first: `TOO_SOON` → +1, `TOO_LATE` → −1, `JUST_RIGHT`/`null` → 0 and the run breaks; a
direction reversal also stops the run without counting the reversing entry.

A `lastCorrectionDirection` column was rejected for the same reason #285's approach 4 (storing
`interval_days` per care log) was rejected: YAPT supports editing and deleting past logs, and a cached
direction would go stale with nothing able to invalidate it. Re-deriving from a bounded 3-log window
on every observation costs a cheap indexed query and guarantees editing/deleting a past WATER log is
reflected on the very next adaptation — no invalidation logic to get wrong or forget.

### Confidence transitions

Confidence **never** rises as a direct result of the feedback chip's value — JUST_RIGHT is
pre-selected on both logging surfaces (ADR-0016/ADR-0024 product), so deriving confidence from it would
let a user who quick-logs everything hit confidence 5 within five waterings and permanently freeze
their schedule (the exact bug this work exists to fix, landing one issue early if not guarded against).

Confidence only rises from:
- **Gap agreement**: the observed watering gap is within `GAP_AGREEMENT_TOLERANCE` (15%, named
  constant) of `currentBaseIntervalDays` (the interval that predicted the due date the user just
  responded to) → `confidence = min(confidence + 1, 5)`. This is what lets a genuine on-schedule
  JUST_RIGHT raise confidence while a defaulted one on an off-schedule watering does not — the
  discrimination is obtained from timestamps, not the chip (see product ADR-0025).
- **Dialog dismissal**: declining the ADR-0006 suggestion → `confidence = min(confidence + 1,
  DISMISSAL_CONFIDENCE_CEILING)` where the ceiling is 3 (of 5). Capped, not floored: a dismissal never
  *lowers* an already-higher confidence. The cap exists so a user who dismisses out of habit — not
  because the schedule is validated — can't reach the gain-0.15 floor or the (future, #572) "dialed in"
  state on no observed evidence; gap agreement is the only path past the ceiling.

Confidence falls when `correctionStreak()` shows two-or-more same-direction corrections in a row
(`abs(streak) >= 2`): `confidence = max(confidence - 2, 0)`. Streak-decrement takes precedence over a
same-step gap-agreement check (they're evaluated as one `when` branch, not both) — a sustained run in
one direction means the model is repeatedly wrong, which should suppress a coincidental gap match, not
let it cancel out the streak signal.

**First observation** (trigger: `wateringConfidence == null`, not "first-ever WATER log" — this stays
bootstrap-friendly for #571's future history-bootstrap work) sets confidence to 0 without evaluating
any transition — nothing to agree or disagree with yet — but the `base` correction still applies, at
the confidence-0 gain (0.60).

**Manual-edit semantics differ by surface.** An AddEditPlant edit to the watering interval — unprompted,
outside the suggestion flow — is a full reset (`confidence = 0`): the user is asserting a new baseline
with information the app doesn't have (repotted, moved, changed pot size). Editing the number *inside*
the ADR-0006 dialog before tapping Apply is different in kind — it's refinement within the model, not a
rejection of it — so it reuses `GAP_AGREEMENT_TOLERANCE`: within tolerance of the original suggestion,
normal rules apply (fine-tuning); outside it, `confidence = max(confidence - 2, 0)` (materially wrong,
but the model still stands — not a full reset). Both a full reset and a routine in-dialog nudge landing
at the same extreme would have recreated the exact failure mode the dismissal ceiling exists to
prevent, just from the opposite direction.

### Corrected convergence figures — the issue body's numbers do not match this rule

The originating issue body claimed "3 steps ≈ 25 days" for a 7 → 14 day shift, and a run-length-based
acceleration mechanism. Both were based on a **compounding-multiplier** formulation (run length raising
the multiplier itself, e.g. ×1.15 → ×1.30 → ×1.45) that was proposed but never implemented — the rule
that shipped is target+gain, which behaves differently, and the issue thread corrected its own figures
mid-discussion once the specified rule was actually simulated:

| Scenario | Converges to ±1 day of 14 |
|---|---|
| **Obedient** — user waters on the app's due date each time | 5 observations, 46 days |
| **Autonomous** — user waters when the plant is actually ready (14 days) | 2 observations, 28 days |

Both are reproduced exactly by `CareScheduleAdaptiveReplayTest` (scenarios 1a/1b), including the exact
per-observation `base` sequence from the issue thread's own hand-verification.

The same-direction streak mechanism contributes **nothing to acceleration** in the obedient scenario:
confidence is floored at 0 from the first observation (nothing left for "−2" to subtract), so the gain
is already at its maximum (0.60) throughout. Its real function is to *prevent confidence from rising*
while the model is persistently wrong in one direction — it is not itself a source of faster
convergence. The actual fast path is the autonomous scenario, and #570 (a later part of the #285 split)
is what makes a user autonomous in practice: today, tapping "still moist" and waiting is what stops the
app from being fed its own suggestion back as the next observed gap.

### A known-unreachable bound: scenario 3b's "confidence never reaches 5"

The issue thread (comment 5) asserted that for the drifting-defaulted-JUST_RIGHT replay scenario (true
gaps constantly 13 days, `base` starting at 8, JUST_RIGHT every time), confidence should never reach 5
within 20 observations. Simulating the rule exactly as specified (gain table, multipliers, 15%
tolerance) shows this is mathematically unreachable, for a reason worth recording so nobody "fixes" it
later by weakening the gap-agreement check:

With gain 0.60 at confidence 0, `base` closes to within 15% of a *constant* target (13) by observation
3. Because the target never moves, once the gap agreement check starts passing it never lapses again
(the model only gets closer), so confidence climbs 0→5 over five strictly consecutive observations —
reaching 5 by observation 7, not "never." `base` itself reaches 13 exactly by observation 3, well
inside the thread's own "within ±15% of 13 by observation 10" bound (which *is* satisfied and is
reproduced in `CareScheduleAdaptiveReplayTest`).

This mirrors the thread's own earlier correction of the "3 steps ≈ 25 days" claim — an aspirational
number that didn't survive contact with the rule as actually specified. Rather than weaken
`GAP_AGREEMENT_TOLERANCE` or the gain table to force the original claim (which the issue explicitly
asked implementers not to do), the replay test instead asserts the properties that are true and that
matter: confidence stays at the floor for the first two observations (still off-schedule, no chip-driven
shortcut), rises only once the gap genuinely agrees, and never moves by more than 1 per observation. A
user's real habitual interval eventually being recognized as "the schedule now matches reality" is
correct behavior, not a bug — the failure this issue exists to fix is a defaulted JUST_RIGHT *never*
being distinguishable from a genuine one, not a defaulted JUST_RIGHT taking a few extra observations to
earn trust once the model has actually converged.

## Consequences

- Behind the flag, `CareSchedule.computeAdaptiveInterval()` and `CareSchedule.computeSuggestedInterval()`
  are independent pure functions — the legacy ±1-day path is untouched, so flag-off behavior is
  byte-for-byte identical to today (`CareScheduleTest`'s existing coverage of
  `computeSuggestedInterval()` is unaffected).
- `Plant.wateringConfidence` and the `.yapt` backup field ship unconditionally regardless of flag state
  (`PlantDatabase.DB_VERSION` 9→10, `MIGRATION_9_10`, backup schema 10→11) — flipping the flag off and
  back on never loses learned state.
- No `lastCorrectionDirection` column exists or should be added; `correctionStreak()` is the single
  source of truth, re-derived on every observation.
- #569 (season de-seasonalizing), #570 (chosen vs. defaulted JUST_RIGHT), #571 (confidence bootstrap
  from history), and #572 (confidence UI surfacing) are out of scope here and left open by this design —
  in particular, once #569 lands, a value typed in either surface must be divided by the season factor
  before being stored as `base`, or the model will silently re-learn the calendar every year (see the
  issue thread's replay scenario 5, added when #569 lands).
