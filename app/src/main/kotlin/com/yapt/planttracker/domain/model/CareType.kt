package com.yapt.planttracker.domain.model

enum class CareType {
    WATER,
    FERTILIZE,
    PRUNE,
    MIST,
    REPOT,
    NOTE,
    PHOTO,
    CUSTOM,

    /**
     * A "Still moist" observation from the (now-removed) check-reminders notification action
     * (#570) and, later, the Reschedule reason prompt — the user checked the soil and did *not*
     * water. **Retained for historical data only (#738, product ADR-0039): no longer written by
     * any code path.** Existing installs and `.yapt` backups carry rows with this value, persisted
     * as a String and read back via `runCatching { Enum.valueOf(...) }.getOrDefault(fallback)` —
     * removing this constant would make those rows silently coerce to the wrong fallback. Hidden
     * from Plant Detail's care-history list (a display filter) but never deleted.
     */
    CHECK
}
