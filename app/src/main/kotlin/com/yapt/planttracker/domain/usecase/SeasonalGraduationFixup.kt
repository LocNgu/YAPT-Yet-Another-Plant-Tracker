package com.yapt.planttracker.domain.usecase

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * One-time app-start backfill for #702: graduating `SEASONAL_WATERING` out of developer mode (#656)
 * made `seasonalAmplitudeFlow()`/`seasonalAmplitudeOnce()` start returning the real stored
 * `SettingsKeys.SEASONAL_AMPLITUDE` preference unconditionally, where it used to hard-return `0.0`
 * whenever the dev-mode flag was off (the default for every install). Every write path that keeps
 * [Plant.wateringBaseIntervalDays] in sync with a manual edit or an accepted suggestion only
 * dual-writes it when `amplitude != 0.0`, so on any install that never enabled the flag, that gate
 * always failed silently — `wateringBaseIntervalDays` stayed frozen at whatever `MIGRATION_10_11`
 * (#569) set it to, while the visible [Plant.wateringIntervalDays] kept moving on every subsequent
 * edit/suggestion-apply. Now that amplitude ships unconditionally, [com.yapt.planttracker.domain
 * .schedule.CareSchedule] multiplies that stale, frozen base by the seasonal curve for real due-date
 * math instead of the plant's actual current interval.
 *
 * Mirrors [WateringLifecycleReset.maybeBootstrap]'s cold-start-reconciliation shape (including logging
 * a [WateringAdjustment] row per affected plant), but is a one-time, app-wide reconciliation rather
 * than a per-plant lifecycle event — see [WateringAdjustmentTrigger.SEASONAL_GRADUATION_FIXUP]'s KDoc
 * for why it's a distinct trigger from [WateringAdjustmentTrigger.HISTORY_BOOTSTRAP].
 */
object SeasonalGraduationFixup {

    /**
     * Bundles [run]/[maybeRun]'s per-invocation inputs (to stay under Detekt's `LongParameterList`
     * threshold, mirroring [WateringLifecycleReset.BootstrapRequest]'s precedent): [plants] is every
     * plant to consider (active and archived alike — the bug this fixes doesn't care about archive
     * state), [amplitude]/[hemisphere] are the current global seasonal settings, and [today] is the
     * anchor day the recomputed base should make "current" (real wall-clock "today" for every
     * production caller; a fixed value in tests).
     */
    data class FixupRequest(
        val plants: List<Plant>,
        val amplitude: Double,
        val hemisphere: Hemisphere,
        val today: LocalDate = LocalDate.now()
    )

    private data class RecomputedBase(val beforeIntervalDays: Int, val afterIntervalDays: Int, val newBase: Double)

    /**
     * Recomputes [Plant.wateringBaseIntervalDays] for every unpinned plant in [FixupRequest.plants]
     * with a non-null [Plant.wateringIntervalDays], anchoring [FixupRequest.today] as the new season
     * reference day — exactly like `MIGRATION_10_11` anchored migration day — so the effective interval
     * immediately after this runs equals the plant's current (correct) literal interval. A no-op
     * (skipped entirely, no [WateringAdjustment] row) for: a pinned plant, a plant with no
     * `wateringIntervalDays` set, [FixupRequest.amplitude] of `0.0` (nothing to de-seasonalize), or a
     * plant whose recomputed base already rounds to the same value it currently has (nothing actually
     * changed). Only [Plant.wateringBaseIntervalDays] and [Plant.updatedAt] are ever touched — the
     * literal [Plant.wateringIntervalDays] is already correct and is never rewritten here, and
     * `wateringConfidence`/`wateringResetAt`/`wateringFreezeUntil` are left alone entirely.
     *
     * Returns the count of plants actually changed. Pure aside from the repository calls — no
     * DataStore access — so this is unit-testable without any Android framework dependency; see
     * [maybeRun] for the DataStore-gated, exactly-once wrapper actually called from app start.
     */
    suspend fun run(
        request: FixupRequest,
        plantRepository: PlantRepository,
        wateringAdjustmentRepository: WateringAdjustmentRepository,
        now: Long = System.currentTimeMillis()
    ): Int {
        if (request.amplitude == 0.0) return 0

        var changedCount = 0
        for (plant in request.plants) {
            val recomputed = recomputeIfNeeded(plant, request) ?: continue
            plantRepository.updatePlant(plant.copy(wateringBaseIntervalDays = recomputed.newBase, updatedAt = now))
            wateringAdjustmentRepository.addAdjustment(
                WateringAdjustment(
                    plantId = plant.id,
                    triggeredAt = now,
                    trigger = WateringAdjustmentTrigger.SEASONAL_GRADUATION_FIXUP,
                    beforeIntervalDays = recomputed.beforeIntervalDays,
                    afterIntervalDays = recomputed.afterIntervalDays
                )
            )
            changedCount++
        }
        return changedCount
    }

    /** `null` when [plant] should be skipped entirely — see [run]'s doc for every no-op case. */
    private fun recomputeIfNeeded(plant: Plant, request: FixupRequest): RecomputedBase? {
        val literalInterval = plant.wateringIntervalDays
        return if (plant.pinIntervalToBase || literalInterval == null) {
            null
        } else {
            val before = plant.wateringBaseIntervalDays?.roundToInt() ?: literalInterval
            val newBase = SeasonalWatering.deseasonalize(
                literalInterval.toDouble(),
                request.today,
                request.amplitude,
                request.hemisphere
            )
            val after = newBase.roundToInt()
            if (before == after) {
                null
            } else {
                RecomputedBase(beforeIntervalDays = before, afterIntervalDays = after, newBase = newBase)
            }
        }
    }

    /**
     * Gates [run] on [SettingsKeys.SEASONAL_BASE_GRADUATION_FIXUP_DONE] so it fires exactly once ever
     * per install, and never re-clobbers a base a user has since adjusted normally through a real
     * edit or suggestion-apply (both of which now correctly dual-write the base themselves, post-#702)
     * — running [run] a second time on a later calendar day would otherwise treat the literal
     * `wateringIntervalDays` as ground truth and re-derive a *different* base purely because "today"
     * moved, even though the currently-stored base is already correct. Returns `0` without touching
     * the flag or any plant when the marker is already set.
     */
    suspend fun maybeRun(
        request: FixupRequest,
        plantRepository: PlantRepository,
        wateringAdjustmentRepository: WateringAdjustmentRepository,
        dataStore: DataStore<Preferences>,
        now: Long = System.currentTimeMillis()
    ): Int {
        val alreadyDone = dataStore.data.first()[SettingsKeys.SEASONAL_BASE_GRADUATION_FIXUP_DONE] ?: false
        if (alreadyDone) return 0

        val changedCount = run(request, plantRepository, wateringAdjustmentRepository, now)
        dataStore.edit { it[SettingsKeys.SEASONAL_BASE_GRADUATION_FIXUP_DONE] = true }
        return changedCount
    }
}
