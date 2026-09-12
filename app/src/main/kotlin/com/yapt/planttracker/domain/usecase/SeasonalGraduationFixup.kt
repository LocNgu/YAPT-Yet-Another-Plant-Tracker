package com.yapt.planttracker.domain.usecase

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
     * The pre-graduation `FeatureFlagRegistry.SEASONAL_WATERING` flag's DataStore key (`key =
     * "seasonal_watering"`, prefixed `"feature_flag_"` by [com.yapt.planttracker.domain.featureflag
     * .FeatureFlags.preferenceKeyFor]) — removing the flag from the registry (#656) never deleted this
     * preference from any install that had ever touched it, since DataStore doesn't garbage-collect
     * keys just because code stops referencing them. Read directly by its literal name here (not
     * reintroduced into the registry) purely to detect "was this ever turned on" — see [maybeRun]'s
     * doc for why that matters.
     */
    private val LEGACY_SEASONAL_WATERING_FLAG_KEY = booleanPreferencesKey("feature_flag_seasonal_watering")

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

    private data class PlantUpdate(val plant: Plant, val recomputed: RecomputedBase)

    /**
     * Recomputes [Plant.wateringBaseIntervalDays] for every unpinned plant in [FixupRequest.plants]
     * with a non-null [Plant.wateringIntervalDays], anchoring [FixupRequest.today] as the new season
     * reference day — exactly like `MIGRATION_10_11` anchored migration day — so the effective interval
     * immediately after this runs equals the plant's current (correct) literal interval. A no-op
     * (skipped entirely, no [WateringAdjustment] row) for: a pinned plant, a plant with no
     * `wateringIntervalDays` set, [FixupRequest.amplitude] of `0.0` (nothing to de-seasonalize), or a
     * plant whose recomputed base is already exactly the stored value (nothing actually changed — this
     * intentionally compares the raw, unrounded `Double`, not the rounded display value, since two
     * bases that round to the same day count can still diverge meaningfully once multiplied by the
     * seasonal curve, #703 review). Only [Plant.wateringBaseIntervalDays] and [Plant.updatedAt] are
     * ever touched — the literal [Plant.wateringIntervalDays] is already correct and is never rewritten
     * here, and `wateringConfidence`/`wateringResetAt`/`wateringFreezeUntil` are left alone entirely.
     *
     * Each plant in [FixupRequest.plants] is treated as a mere id lookup, not authoritative data: this
     * runs asynchronously from [YaptApplication.onCreate] on a background dispatcher while the UI can
     * be concurrently editing/archiving/quick-logging against the same plants, so [FixupRequest.plants]
     * (snapshotted once by the caller) can go stale between snapshot and write. Both the eligibility
     * check and the write re-fetch the plant fresh via [PlantRepository.getPlantById] immediately
     * before acting on it (mirroring `AddEditPlantViewModel.saveEdit()`'s `getPlantById(...).first()`
     * precedent) — the same bug class already fixed elsewhere for a full-row `.copy()` racing a
     * concurrent edit (see `.claude/rules/watering-transparency.md`'s "#612/#613/#614" note). A plant
     * deleted since the snapshot was taken (fetch returns `null`) is silently skipped.
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
        for (snapshotPlant in request.plants) {
            val update = computeUpdate(snapshotPlant, request, plantRepository) ?: continue
            plantRepository.updatePlant(
                update.plant.copy(wateringBaseIntervalDays = update.recomputed.newBase, updatedAt = now)
            )
            wateringAdjustmentRepository.addAdjustment(
                WateringAdjustment(
                    plantId = update.plant.id,
                    triggeredAt = now,
                    trigger = WateringAdjustmentTrigger.SEASONAL_GRADUATION_FIXUP,
                    beforeIntervalDays = update.recomputed.beforeIntervalDays,
                    afterIntervalDays = update.recomputed.afterIntervalDays
                )
            )
            changedCount++
        }
        return changedCount
    }

    /**
     * Re-fetches [snapshotPlant] by id and, if it still exists and still needs fixing up, bundles the
     * fresh plant with its [RecomputedBase]. `null` when the plant was deleted since the snapshot was
     * taken, or [recomputeIfNeeded] finds nothing to do for its *current* state.
     */
    private suspend fun computeUpdate(
        snapshotPlant: Plant,
        request: FixupRequest,
        plantRepository: PlantRepository
    ): PlantUpdate? {
        val currentPlant = plantRepository.getPlantById(snapshotPlant.id).first()
        return if (currentPlant == null) {
            null
        } else {
            recomputeIfNeeded(currentPlant, request)?.let { PlantUpdate(currentPlant, it) }
        }
    }

    /** `null` when [plant] should be skipped entirely — see [run]'s doc for every no-op case. */
    private fun recomputeIfNeeded(plant: Plant, request: FixupRequest): RecomputedBase? {
        val literalInterval = plant.wateringIntervalDays
        return if (plant.pinIntervalToBase || literalInterval == null) {
            null
        } else {
            val newBase = SeasonalWatering.deseasonalize(
                literalInterval.toDouble(),
                request.today,
                request.amplitude,
                request.hemisphere
            )
            val beforeBase = plant.wateringBaseIntervalDays
            if (beforeBase == newBase) {
                null
            } else {
                RecomputedBase(
                    beforeIntervalDays = beforeBase?.roundToInt() ?: literalInterval,
                    afterIntervalDays = newBase.roundToInt(),
                    newBase = newBase
                )
            }
        }
    }

    /**
     * Gates [run] on [SettingsKeys.SEASONAL_BASE_GRADUATION_FIXUP_DONE] so it fires at most once ever
     * per install, and never re-clobbers a base a user has since adjusted normally through a real
     * edit or suggestion-apply (both of which now correctly dual-write the base themselves, post-#702)
     * — running [run] a second time on a later calendar day would otherwise treat the literal
     * `wateringIntervalDays` as ground truth and re-derive a *different* base purely because "today"
     * moved, even though the currently-stored base is already correct. Returns `0` without touching
     * the flag or any plant when the marker is already set.
     *
     * Two more conditions gate whether this actually *marks* the DONE flag (#703 review), independent
     * of whether [run] itself found anything to change:
     * - **`request.plants.isEmpty()` or `request.amplitude == 0.0` never marks it done.** A brand-new
     *   install has nothing to iterate yet, and an amplitude of Off has nothing to de-seasonalize —
     *   [run] naturally returns `0` in both cases, but that's "nothing to do *yet*", not "verified
     *   correct". Marking done regardless would permanently block a later `.yapt` restore (which can
     *   import pre-graduation, potentially-stale bases) or a later switch to a non-Off amplitude from
     *   ever being reconciled. The flag is only set once a real pass over a non-empty, amplitude-on
     *   install has happened — retried on every subsequent app start until that's true at least once.
     * - **[LEGACY_SEASONAL_WATERING_FLAG_KEY] having ever been `true` skips [run] entirely** (for every
     *   plant, unconditionally) rather than reconciling anything. If the old dev-mode flag was ever on,
     *   the pre-#656 write paths were already correctly dual-writing `wateringBaseIntervalDays` for
     *   whatever plants were edited/suggested while it was — this function has no way to tell a
     *   genuinely-stale base (frozen since `MIGRATION_10_11`) apart from one that's already correctly
     *   anchored to some other, unrecorded edit day (only a coincidence would make the two agree), so
     *   blindly overwriting risks discarding a valid anchor. This is a deliberate, permanent
     *   "can't safely auto-fix this install" decision — the flag is still marked done afterward (gated
     *   by the same non-empty/amplitude-on condition above), not a deferred retry. A user on such an
     *   install can self-correct any genuinely-stale plant by making one real edit to its interval,
     *   which dual-writes correctly via the post-graduation code.
     */
    suspend fun maybeRun(
        request: FixupRequest,
        plantRepository: PlantRepository,
        wateringAdjustmentRepository: WateringAdjustmentRepository,
        dataStore: DataStore<Preferences>,
        now: Long = System.currentTimeMillis()
    ): Int {
        val prefs = dataStore.data.first()
        val alreadyDone = prefs[SettingsKeys.SEASONAL_BASE_GRADUATION_FIXUP_DONE] ?: false
        if (alreadyDone) return 0

        val everHadLegacyFlagOn = prefs[LEGACY_SEASONAL_WATERING_FLAG_KEY] ?: false
        val changedCount = if (everHadLegacyFlagOn) {
            0
        } else {
            run(request, plantRepository, wateringAdjustmentRepository, now)
        }

        val canMarkDone = request.amplitude != 0.0 && request.plants.isNotEmpty()
        if (canMarkDone) {
            dataStore.edit { it[SettingsKeys.SEASONAL_BASE_GRADUATION_FIXUP_DONE] = true }
        }
        return changedCount
    }
}
