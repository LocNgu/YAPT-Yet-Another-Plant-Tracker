# Product ADR-0009: Watering feedback labels reframed as observable soil state

**Status**: accepted
**Date**: 2026-06-02

## Context

The original feedback chip labels — "Too soon", "Just right", "Too late" — framed the watering relative to the stored schedule. This caused usability confusion:

- "Too soon" implies the user watered before they were supposed to, but the user may not know what the stored interval is and cannot judge "too soon" relative to it.
- "Too late" is similarly opaque — the user watered, the reminder has been dismissed, and now they are asked whether they were "too late."
- The framing conflates the outcome (what the plant's soil felt like) with the schedule (when the app expected them to water).

The enum values `TOO_SOON`, `TOO_LATE`, `JUST_RIGHT` are stored in the Room database and used directly in `CareSchedule.computeSuggestedInterval()`. Renaming the enums would require a DB migration and could break backups from older app versions.

## Decision

Only the **display layer** is changed. The UI labels are updated to:

- `TOO_SOON` → **"Still wet"** — the soil was visibly moist when the user watered
- `JUST_RIGHT` → **"Just right"** — unchanged
- `TOO_LATE` → **"Too dry"** — the soil was dry or the plant was showing stress

The feedback question text changes from "How was the timing?" to **"What did you find?"**

The enum values `TOO_SOON`, `TOO_LATE`, `JUST_RIGHT` are preserved in the database and in all business logic. No DB migration is required. Old backups importing `TOO_SOON`/`TOO_LATE` string values continue to deserialize correctly via `runCatching { Enum.valueOf(...) }`.

## Consequences

- Users describe what they observed (soil state) rather than judging their own timing relative to an opaque schedule.
- The adaptive interval algorithm (`CareSchedule.computeSuggestedInterval`) is fully unchanged — it still receives `WateringFeedback.TOO_SOON` / `JUST_RIGHT` / `TOO_LATE` and behaves identically.
- Full backward compatibility: existing DB rows and backup files require no changes.
- The whats-new copy in older releases (e.g. "interval suggestions based on Still wet / Just right / Too dry feedback") is retroactively accurate because the enum semantics are unchanged.
