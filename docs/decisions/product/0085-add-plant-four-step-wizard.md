# Product ADR-0085: Adding a plant is a four-step wizard; editing stays one form

**Status**: accepted

**Date**: 2026-11-12

## Context

Issue #943: Add Plant was one long scrolling form (photo, name, species, room, three care reminders with
sliders, seasons, dormancy, notes). New owners met every advanced setting before their first plant existed.
Edit mode is the opposite case: changing one field must not mean walking a wizard. Add from Propagate
(product ADR-0073) reuses the same screen and prefill. A later onboarding tour will launch the same route.
Alternatives considered: six steps (one per care type), a merged "Other care" step, and keeping one form with
collapsed sections. Six steps put three nearly identical care screens in a row; collapsed sections still show
everything at once.

## Decision

- **Four steps, add mode only:** (1) Photo, (2) Name + species + location, (3) Care (watering, dormancy,
  fertilizing, repotting), (4) Review. Add and Add-from-Propagate use the wizard; **Edit stays the single form**.
- **Only the name is required**, checked when leaving step 2 (and again by Save). Photo, species, location and
  every care reminder are optional; care reminders default to off, so an untouched Care step creates a plant
  with no reminders (as the single form did).
- **Review** holds the optional notes field above a read-only summary ("Watering: every 7 days" / "Watering:
  off", name, species, location, photo). Each summary line jumps back to its step. **Add plant** sits there and
  is the only write: one `saveNew()`, no partial plant earlier.
- **Back:** the top-bar arrow and system Back both go to the previous step. On step 1 Back leaves at once when
  nothing was entered, otherwise a discard dialog asks. A Propagate prefill counts as entered input.
- **Progress:** "Step N of 4" with a progress bar, announced to TalkBack as a polite live region.
- **The draft and the step survive process death** (technical ADR-0044). Restored input is never overwritten by
  a later load or by the Propagate prefill.
- A photo taken and then discarded is not deleted eagerly; `OrphanPhotoSweeper` reclaims it.

## Consequences

- A user who only wants a name can finish in four taps; care detail is one step, not the whole screen.
- Care settings now sit one step before the summary, so the summary is the place to confirm what will exist.
- Growing conditions (#1003) and the #700 strength control land in step 2 or 3 without a new step.
- Edit and Add diverge in layout but share every section composable and the single save path.
