# ADR-0026: Computed (not learned) seasonal watering factor

**Status**: accepted

**Date**: 2026-08-20

## Context

#285 originally proposed making the watering interval season-aware by *learning* twelve independent
monthly values per plant, adjusting each month's stored interval from the user's own logged
waterings. That issue was closed and re-scoped into a 5-part series; the per-month-learning approach
and its rejected alternatives live in #285's own closing comment/context, not repeated here. This ADR
covers Part 2 of that series (#569): making watering seasonal at all, and the decision to compute the
seasonal factor from the calendar rather than learn it from data.

The per-month-learning design has three structural problems, not implementation bugs:

- **Data is too sparse per slot.** A plant on a 10-day interval produces ~36 waterings/year across 12
  monthly slots — about 3 samples per slot per year, and only the ones where the user actually
  engaged the interval-suggestion dialog. Reaching a usable sample size (5) in a given month takes on
  the order of 20 months. The feature would be invisible for a plant's first year or two.
- **The shape is shared, not slot-specific.** The seasonal *shape* (stretch in winter, compress in
  summer) is close to identical for every plant in a home; only the amplitude and the plant's own base
  thirst differ. Twelve independent slots force each month to rediscover the same underlying curve
  separately, sharing nothing — twelve times the data requirement for what is really one-dimensional
  information (a single amplitude).
- **Month boundaries and outliers.** Learned monthly slots produce hard discontinuities at month
  boundaries (an interval jumping from 7 to 12 days overnight, twelve times a year) that correspond to
  nothing physical, and a single unusual heatwave permanently distorts that month's slot with no decay
  mechanism to correct it.

## Decision

Make the seasonal factor a **pure, computed function of the calendar**, not a per-plant learned model:

```
season(date) = 1 + amplitude · cos(2π · (dayOfYear − peakDay) / 365)
effectiveInterval(date) = round(base × season(date)), clamped to [1, 180]
```

- `amplitude` is a single global setting (Off / Mild 0.2 / Standard 0.35 / Strong 0.5), not per-plant
  and not learned — it needs no training data, is explainable in one sentence, and a user with grow
  lights or stable indoor conditions can flatten it directly rather than waiting for a model to
  converge.
- `peakDay` ≈ day 5 (northern hemisphere reference), shifted +182 days for the southern hemisphere.
  Hemisphere is derived from the device's timezone id (`TimeZone.getDefault().id`) against a
  maintained allowlist of southern-hemisphere prefixes — no location permission, no network, staying
  offline-first.
- `base` (`Plant.wateringBaseIntervalDays`) is a season-neutral reference value, separate from the
  literal `Plant.wateringIntervalDays` the rest of the app already reads. This is what makes the
  `seasonal_watering` feature flag genuinely reversible: flag off reads `wateringIntervalDays` exactly
  as before, and `base` sits unused, ready if the flag is re-enabled later. Reinterpreting the existing
  column instead would make the flag one-way.
- Per-plant `pinIntervalToBase` opts a specific plant out of the curve entirely (e.g. a plant on a grow
  light with genuinely no season), independent of the global amplitude setting.
- A computed curve cannot overfit a single unusual year, has no month-boundary discontinuities, and —
  critically — a correction the user makes in July also improves the January prediction, because both
  read the same `base` rather than independent monthly slots.

Migrating existing plants de-seasonalizes each one's stored `wateringIntervalDays` to *migration day*
(`base = wateringIntervalDays / season(migrationDay)`), not face value and not the (unknowable) day the
user originally set it — this is the only reading under which the *effective* interval is unchanged on
the day the migration runs, regardless of what month it happens to ship in.

## Consequences

- The seasonal factor never needs a bootstrapping/cold-start period — it applies correctly to a
  plant added five minutes ago just as well as one added five years ago.
- The tradeoff is that the curve is a *model* of typical indoor seasonal light/water demand, not a
  fit to any individual plant's actual behavior — two plants with identical `wateringIntervalDays` get
  identical seasonal adjustment regardless of which room, window orientation, or microclimate they're
  actually in. `pinIntervalToBase` and the amplitude setting are the two escape hatches for plants or
  homes where the model doesn't fit.
- Part 1's adaptive-interval learning (#568, product ADR-0025) still operates on the literal,
  already-seasonal `wateringIntervalDays`/`wateringConfidence` columns; only the *observed gap* fed
  into its update rule is de-seasonalized first (`observedBase = observedGap / season(dateOfGap)`), so
  a correction made in one season isn't misread as a permanent change in the plant's thirst. The two
  features' state remains otherwise independent, and both are flag-gated separately so either can be
  disabled without touching the other's learned/computed state.
- If device latitude ever becomes available, amplitude could instead be derived from real daylight
  hours (e.g. Forsythe et al.'s CBM daylight model) — noted here as a future refinement, not pursued
  now because a fixed, user-facing amplitude control is more transparent and needs no permissions.
