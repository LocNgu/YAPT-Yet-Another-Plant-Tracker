package com.yapt.planttracker.domain.model

enum class CareType {
    WATER,
    FERTILIZE,
    PRUNE,

    /**
     * Misting. **Retained for historical data only (#875, product ADR-0061): no longer written by
     * any code path** — Add Care Log only edits a stored MIST log (its type stays fixed, shown as a
     * read-only header), and the bulk bar, quick-log surfaces and demo data never create one. Unlike
     * [CHECK] these rows are entries the user typed in, so they are not hidden: Plant Detail's
     * Home history still lists them (editable, deletable) and the watering chart still draws
     * their markers. Persisted as a String, so removing this constant would make existing rows
     * and `.yapt` backups coerce to the wrong fallback.
     */
    MIST,
    REPOT,

    /**
     * Free-text note. **Retained for historical data only (#532, product ADR-0062): no longer written
     * by any code path** — Add Care Log only edits a stored NOTE log (its type stays fixed, shown as a
     * read-only header), and the bulk bar, quick-log surfaces and demo data never create one. Existing
     * rows still list in Plant Detail's Home history (editable, deletable) and persist as a String, so
     * removing this constant would make them and `.yapt` backups coerce to the wrong fallback.
     */
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
