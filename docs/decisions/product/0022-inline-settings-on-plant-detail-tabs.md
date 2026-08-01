# Product ADR-0022: Scheduling settings are editable inline on Plant Detail tabs

**Status**: accepted

**Date**: 2026-08-01

## Context

Issue #436 (Option 2) gives Plant Detail per-action tabs (ADR-0018). Part of that feature is letting the user adjust a plant's **scheduling settings in place** — on the relevant tab — without opening the separate Add/Edit Plant screen.

Until now, all plant settings were edited only on Add/Edit Plant. This was a convention, not a recorded decision: contrary to what #436 and earlier notes stated, there was **no** ADR enshrining an "Add/Edit-only" rule (product ADR-0012 is about cover-photo source and is already superseded by ADR-0015). So inline editing does not supersede an existing decision — it records a new one.

The question: which settings become inline-editable, and how does that coexist with Add/Edit remaining the place a plant is created and fully edited?

Alternatives considered:

- **Keep everything on Add/Edit only.** Simplest, but defeats a core goal of #436 — adjusting a watering interval is the most common tweak and forcing a full-screen detour for it is friction.
- **Make every field inline-editable (name, species, room, notes, photos, schedule).** Turns Plant Detail into a second full editor, duplicates Add/Edit, and muddies which screen is canonical.
- **Inline-edit only the scheduling settings that each tab is already about** — watering interval on the Water tab, fertilizing interval and liquid-fertilizer mode on the Fertilize tab — and leave identity/media fields (name, species, room, notes, cover) to Add/Edit.

## Decision

Three scheduling settings are editable inline on Plant Detail tabs, auto-persisted on change (no Save button):

- **Water tab** — watering interval (`wateringIntervalDays`), including turning the schedule on/off.
- **Fertilize tab** — fertilizing interval (`fertilizingIntervalDays`), including on/off, and the liquid-fertilizer mode toggle (`useLiquidFertilizer`).

Each control mirrors its Add/Edit counterpart (enable `Switch` + `Slider`, same 1–60 / 1–90 day ranges, same liquid-fertilizer semantics) and writes straight through `PlantDetailViewModel` → `PlantRepository.updatePlant`. Slider drags persist on release (`onValueChangeFinished`), not on every frame.

**Add/Edit Plant remains the canonical editor** for everything else — name, species, room, notes, and cover photo — and remains the only place a plant is created. The inline tab controls are a convenience surface for the scheduling fields the tabs already visualise, not a second full editor.

## Rationale

Scheduling settings are the ones a user revisits repeatedly as they learn a plant's needs, and they are exactly what the Water/Fertilize tabs already show insights for — editing them where you read them is the natural flow. Identity and media fields are set once (or rarely) and belong with plant creation, so keeping them on Add/Edit preserves a single clear "edit the plant" entry point and avoids two diverging editors.

## Consequences

- `PlantDetailViewModel` gains `setWateringInterval`, `setFertilizingInterval`, and `setLiquidFertilizer`, each persisting via `updatePlant`. Changes flow back through the existing `plant` StateFlow, so the tab insights (chart, countdowns, StatsRow chips) update immediately.
- A plant's schedule can now be changed from two places (Add/Edit and the tabs). Both write the same fields the same way, so there is no divergence; the tabs simply expose a subset.
- Turning an interval off inline sets the field to `null` (the "Not scheduled" state, per CareSchedule), identical to clearing the reminder switch on Add/Edit.
- Future settings must not drift onto the tabs by default: only add an inline control when the setting is intrinsically about that tab's care action. Identity/media fields stay on Add/Edit unless a future ADR revisits this.
