package com.yapt.planttracker.domain.model

data class Plant(
    val id: Long = 0,
    val name: String,
    val species: String? = null,
    val room: String? = null,
    val coverPhotoUri: String? = null,
    val notes: String? = null,
    val wateringIntervalDays: Int? = null,
    val fertilizingIntervalDays: Int? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val wateringDueDateOverride: Long? = null,
    val useLiquidFertilizer: Boolean = false,
    val archivedAt: Long? = null,
    val repottingIntervalDays: Int? = null,
    /**
     * 0-5, `null` = never adapted (#568). Ships unconditionally (no schema/backup flag-gating) even
     * though `FeatureFlagRegistry.ADAPTIVE_WATERING` gates whether it's ever written or read for
     * scheduling — flipping the flag off must not lose learned state.
     */
    val wateringConfidence: Int? = null,
    /**
     * Season-neutral reference interval (#569, product ADR-0026): `REAL`, not rounded at rest, since
     * it's multiplied by [com.yapt.planttracker.domain.schedule.SeasonalWatering.season] to derive
     * the *effective* interval — only that result is rounded. `null` means this plant has never had a
     * base recorded (created while `SEASONAL_WATERING` was off); due-date computation then falls back
     * to [wateringIntervalDays] directly as the base.
     */
    val wateringBaseIntervalDays: Double? = null,
    /** Opts this plant out of the seasonal curve entirely — due dates use [wateringIntervalDays] as-is (#569). */
    val pinIntervalToBase: Boolean = false,
    /**
     * Anchor timestamp of the most recent lifecycle reset (a REPOT log or a qualifying [room] change,
     * #571) — written once as a side effect at reset time, never derived live from querying REPOT log
     * history (so editing/deleting a past REPOT log can never spuriously re-trigger a reset). `null`
     * outside a pending post-reset bootstrap opportunity: cleared once
     * [com.yapt.planttracker.domain.usecase.WateringLifecycleReset.maybeBootstrap] successfully applies,
     * or if this plant never had a lifecycle reset.
     */
    val wateringResetAt: Long? = null,
    /**
     * REPOT-only marker (#571): `wateringResetAt + 28 days`, gating the live-learning freeze — never
     * set on a room-change reset, so freeze exclusion structurally can't fire for that trigger. `null`
     * once the freeze window has never been active or has already elapsed (elapsed is still a real,
     * non-null past timestamp; callers compare it against "now", they don't need to clear it).
     */
    val wateringFreezeUntil: Long? = null
)
