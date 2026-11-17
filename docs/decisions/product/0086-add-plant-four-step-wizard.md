# Product ADR-0086: Adding a plant is a four-step wizard; editing stays one form

**Status**: accepted

**Date**: 2026-11-17

## Context

Issue #943: Add Plant was one long scrolling form (photo, name, species, room, three care reminders with
sliders, seasons, dormancy, notes). New owners met every advanced setting before their first plant existed.
Edit mode is the opposite case: changing one field must not mean walking a wizard. Add from Propagate
(product ADR-0073) reuses the same screen and prefill. A later onboarding tour will launch the same route.
The owner's review asked for the name before the photo and for a lighter Care step: dormancy, watering strength
and repotting are rarely used and overwhelm first-time users.
Alternatives considered: six steps (one per care type), a merged "Other care" step, and keeping one form with
collapsed sections. Six steps put three nearly identical care screens in a row; collapsed sections still show
everything at once. For the rare settings: removing them from the wizard entirely (they would be reachable only
after the plant exists, and a Propagate copy would be applied unseen) and a three-step layout with the photo
folded into step 1 (the name field would then sit below a large photo control) were both rejected in favour of
a collapsed "More options" section on the Care step.

## Decision

- **Four steps, add mode only:** (1) Name + species + location, (2) Photo, (3) Care, (4) Review. Add and
  Add-from-Propagate use the wizard; **Edit stays the single form** with every setting visible.
- **Care step:** watering and fertilizing (all their controls: switches, sliders, seasons, liquid) are always
  visible. A collapsed **"More options"** section holds dormancy, watering strength/pin and repotting. The
  header is a button that exposes its expanded/collapsed state to accessibility services. Collapsing only hides
  the values; they are kept and saved. The expanded state is part of the saved draft (technical ADR-0044), so it
  survives process death and a restored draft keeps the state it was saved with. On first visit it starts
  collapsed, unless the Propagate prefill changed a setting inside it, in which case it starts expanded.
- **Only the name is required**, checked when leaving step 1 (and again by Save). Photo, species, location and
  every care reminder are optional; care reminders default to off, so an untouched Care step creates a plant
  with no reminders (as the single form did).
- **Review** holds the optional notes field above a read-only summary ("Watering: every 7 days" / "Watering:
  off", name, species, location, photo), plus one line per "More options" setting that differs from its default
  (for example "Dormancy: Nov–Feb"; none for a plain new plant). Each summary line jumps back to its step; a
  "More options" line jumps to Care with the section expanded. **Add plant** sits there and
  is the only write: one `saveNew()`, no partial plant earlier.
- **Back:** the top-bar arrow and system Back both go to the previous step. On step 1 Back leaves at once when
  nothing was entered, otherwise a discard dialog asks. A Propagate prefill counts as entered input.
- **Progress:** "Step N of 4" with a progress bar, announced to TalkBack as a polite live region.
- **The draft and the step survive process death** (technical ADR-0044). Restored input is never overwritten by
  a later load or by the Propagate prefill.
- A photo taken and then discarded is not deleted eagerly; `OrphanPhotoSweeper` reclaims it.

## Consequences

- A user who only wants a name can finish in four taps; the rare care settings stay out of the way until asked for.
- Care settings now sit one step before the summary, so the summary is the place to confirm what will exist.
- Growing conditions (#1003) and the #700 strength control land in step 1 or 3 without a new step.
- Edit and Add diverge in layout but share every section composable and the single save path; the watering
  strength control is split out of the watering section so Edit keeps it inside, unchanged.
