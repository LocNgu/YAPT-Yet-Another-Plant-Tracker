# ADR-0011: Enums stored as strings in Room with runCatching deserialization

**Status**: accepted

**Date**: 2024-01-01

## Context

Room stores enum fields using `@TypeConverter` functions. The two main options are:

- **Store as `Int` (ordinal)**: compact, but fragile. Reordering enum values in code silently corrupts historical data. Adding a value in the middle shifts all subsequent ordinals.
- **Store as `String` (name)**: readable in database exports and backup JSON files, and order-independent. Removing an enum value will cause `Enum.valueOf()` to throw at read time, but that failure is explicit.

A second concern is the deserialization call itself. `CareType.valueOf("UNKNOWN")` throws `IllegalArgumentException` if the string doesn't match any enum constant. This can happen if:
- A backup from a future app version is restored into an older version that doesn't have that enum value yet.
- The database was manually edited.

Plain `.valueOf()` would crash the app for any such row.

## Decision

All enums (`CareType`, `WateringFeedback`) are stored as their string `name`. Reading uses `runCatching { Enum.valueOf(storedString) }.getOrDefault(fallback)`:

- If the stored string matches a valid enum constant, it's used.
- If not (unknown value, null string, future value), the fallback is used and the row is still readable.

See `CareLogRepository.kt` and the type converter implementations.

## Consequences

- Database exports and backup JSON files are human-readable.
- Reordering enum values in code never corrupts historical data.
- Removing an enum value does not crash the app — existing rows fall back gracefully.
- Adding new enum values is safe: old app versions reading new values fall back rather than crash.
- The fallback value must be chosen carefully. For `CareType`, there is no safe no-op fallback; this is an acknowledged gap. For `WateringFeedback`, `JUST_RIGHT` is used as the fallback.
