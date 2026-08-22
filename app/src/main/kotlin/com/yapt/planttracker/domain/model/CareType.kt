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
     * A "Still moist" observation from the check-reminders notification action (#570, `check_reminders`
     * feature flag) — the user checked the soil and did *not* water. Always carries
     * [com.yapt.planttracker.domain.model.WateringFeedback.TOO_SOON] as its [CareLog.wateringFeedback];
     * reuses the existing care-log pipeline rather than a new table (see product ADR-0027). Never
     * offered as a manually-loggable type on [com.yapt.planttracker.ui.screens.addcarelog.AddCareLogScreen]
     * — it is only ever written by [com.yapt.planttracker.domain.usecase.QuickLogUseCase.recordStillMoistCheck].
     */
    CHECK
}
