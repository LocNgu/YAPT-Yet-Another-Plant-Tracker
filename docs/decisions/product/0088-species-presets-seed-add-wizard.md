# Product ADR-0088: Species presets seed the Add wizard from an explicit pick

**Status**: accepted

**Date**: 2026-11-27

## Context

Issue #1002: a new user has to guess a watering and fertilizing cadence for every plant. Product ADR-0044 ruled
out suggesting dormancy from `Plant.species` because species is free text with no backing data, so any inference
would be a guess dressed as knowledge. The owner chose to relax that rule only for an explicit choice from a small
bundled list. Alternatives considered: inferring from the typed species (rejected, the ADR-0044 reason), a
download or online lookup (the app is offline-first, no network), and a preset reference stored on the plant
(rejected: it would couple existing plants to future list edits and need a migration and a backup change).

## Decision

- **Explicit pick only.** The Add wizard's species field (step 1) suggests bundled presets as the user types
  (case- and accent-insensitive, on the common and the botanical name). Tapping one sets the species text to its
  botanical name and pre-fills the Care step. Free text stays allowed and never seeds anything. This supersedes
  product ADR-0044 only where it forbids any species-based suggestion; nothing is ever inferred from free text.
- **Add mode only.** Edit mode gets no suggestions and no re-apply. Add-from-Propagate gets none either: it already
  copies the parent's care settings (product ADR-0073), and a preset must not overwrite them.
- **Seed once, store nothing.** Values are copied into the form and saved through the normal save path: no new
  column, no migration, no backup change, no link back to the preset. Changing the list later never affects
  existing plants. The watering value is the season-neutral base interval; the slider shows today's effective
  equivalent and saving keeps the preset's exact base while the slider is untouched.
- **Never overwrite the user.** Changing watering, fertilizing or dormancy by hand marks that group touched; a
  later pick (preset B after A) replaces only untouched groups. Editing the species text after a pick keeps the
  seeded values. The picked preset and the touched groups are part of the saved draft.
- **Dormancy** can be seeded, only by an explicit pick, and More options opens when it changes so it is never
  applied unseen. The window is written for the northern hemisphere and shifted six months in the southern.
- **Uncertainty:** one label on the pre-filled Care step, "Suggested starting values, adjust to your home".
- Display names are string resources; botanical names are in code.

### Sources and honesty

The values are rough starting points drawn from general, widely published houseplant care guidance (Royal
Horticultural Society plant profiles and houseplant advice; university extension houseplant guides such as those
of the Universities of Minnesota, Illinois and Missouri; Clemson Cooperative Extension). Guides disagree and
conditions vary (light, pot, room temperature), so each value is a middle-of-the-road interval, not a rule. The
owner reviews the list and values in the PR; the adaptive watering model (product ADR-0006) corrects them over time.

## Consequences

- A new user gets a plausible cadence in two taps; every value stays editable.
- The list is small and curated: adding a plant means a code change and a string resource, and translation
  (#1005) covers display names through the normal string pipeline.
- Dormancy months follow the user's timezone-derived hemisphere, the same heuristic as the seasonal curve.
- The flat-list design does not scale to hundreds of species; that would need a different decision.
