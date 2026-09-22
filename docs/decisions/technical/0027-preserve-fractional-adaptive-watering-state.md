# Technical ADR-0027: Preserve fractional adaptive-watering state

**Status**: accepted

**Date**: 2026-09-19

## Context

Technical ADR-0021 made the adaptive model whole-day granular: it rounded every computed base before
the next observation. Later seasonal watering deliberately stored `wateringBaseIntervalDays` as a
`REAL`, but the model continued rounding that value both on input and output. Consequently the
low-gain neutral channel specified by product ADR-0027 could not accumulate a correction for bases of
26 days or less (#717).

The same loss appeared when applying a seasonal suggestion (#718). The UI correctly displays an
effective whole-day interval, but converting that rounded value back to base space amplifies its
rounding residual. Repeated upward seasonal-threshold crossings could therefore ratchet the base.

The approval and transparency contracts also need an explicit interpretation. Silently storing every
fractional result would bypass “Ask before changing intervals” when a visible whole-day change is
pending. Conversely, refusing to store an invisible fractional result would recreate #717. The
`watering_adjustments` schema is intentionally day-granular and cannot display fractional movement.

## Decision

`CareSchedule.AdaptiveInterval` carries both the unrounded, clamped `baseIntervalDays: Double` and the
rounded `intervalDays: Int`. Subsequent observations read the precise stored base whenever seasonal
adjustment is active. The rounded value remains the UI and adjustment-ledger representation.

An observation whose precise result does not alter today's displayed effective interval persists the
fractional base immediately. This is model state refinement, not a user-visible schedule change, so it
does not require approval. If the displayed interval would change, only confidence is persisted and
the precise base travels with the pending suggestion; it is committed only when the user applies the
unchanged suggestion or when the setting permits silent application. Editing the dialog field remains
a manual effective-space value and is de-seasonalized normally.

Pinned plants and amplitude Off retain their existing literal-interval semantics: observations must
not overwrite the dormant seasonal base. The integer adjustment ledger remains unchanged; fractional
movement becomes visible there only after it crosses a whole-day boundary.

## Consequences

- Neutral in-band observations accumulate for ordinary houseplant intervals.
- Applying an unchanged seasonal suggestion cannot round-trip through effective space and ratchet the
  base.
- Suggestion carriers and navigation state must transport the precise base together with the rounded
  display value.
- The replay harness now evolves a `Double` base between observations. Existing whole-day UI and
  adjustment-history contracts remain intact.
- A future fractional adjustment ledger would require a schema and presentation decision; this ADR
  deliberately does not introduce one.
