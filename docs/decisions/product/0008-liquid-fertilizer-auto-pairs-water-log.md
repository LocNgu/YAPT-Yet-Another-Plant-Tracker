# Product ADR-0008: Liquid fertilizer mode auto-creates a paired WATER log

**Status**: accepted
**Date**: 2026-06-02

## Context

Liquid fertilizer is dissolved in water and applied during a normal watering. If a user logs a FERTILIZE event separately, the plant's watering history is left with a gap — the watering that carried the fertilizer is not recorded. This has two downstream consequences:

1. The adaptive interval algorithm sees fewer watering data points, making suggestions less accurate.
2. The reminder worker may re-fire a watering reminder for a plant that was just watered (as part of fertilizing), confusing the user.

Alternatives considered:

- **Require the user to log both manually**: accurate, but adds friction and is error-prone (users forget one of the two logs).
- **Auto-create a paired WATER log**: transparent to the user; the app does the right thing automatically based on the plant's fertilizer type setting.
- **Treat FERTILIZE as implicitly watering for liquid plants**: simpler, but loses the explicit WATER record that drives the chart and interval logic.

## Decision

Plants have a `useLiquidFertilizer: Boolean` flag (added in DB v3, `MIGRATION_2_3`). When this flag is true, every FERTILIZE care log creation path automatically inserts a paired WATER log at the same timestamp with `WateringFeedback.JUST_RIGHT`. This happens in:

- The Add Care Log screen (on save)
- The quick-fertilize button on the plant list card

The Add Care Log screen shows a Fertilizer type selector (Liquid / Solid chips) so the user can see and override the default for a specific log. The PlantCard and PlantDetail fertilizing chip shows "With watering" when the plant uses liquid fertilizer, so the behaviour is visible in the UI.

Reminder notifications for liquid-fertilizer plants append "Fertilize with watering" to the watering alert body instead of posting a standalone fertilizing notification.

The `useLiquidFertilizer` flag and `fertilizerType` on care logs are included in backup schema v2 for round-trip fidelity.

## Consequences

- Watering history for liquid-fertilizer plants is automatically correct — no manual double-logging required.
- The adaptive interval algorithm receives watering data for every fertilizing session, keeping suggestions accurate.
- Users who fertilize more frequently than they water (unusual but possible) will see watering logs they did not explicitly create; the "With watering" label makes this transparent.
- Solid-fertilizer plants are unaffected.
