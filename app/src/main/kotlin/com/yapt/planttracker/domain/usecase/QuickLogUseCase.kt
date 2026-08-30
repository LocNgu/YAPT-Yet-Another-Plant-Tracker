package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.withTransaction
import com.yapt.planttracker.R
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.PhotoReminderRequest
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.model.WateringReason
import com.yapt.planttracker.domain.reminder.PhotoReminderPolicy
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeOnce
import com.yapt.planttracker.ui.util.labelRes
import com.yapt.planttracker.util.toLocalDate
import kotlinx.coroutines.flow.first
import kotlin.math.roundToInt

/**
 * Shared quick-log business logic used by both `PlantListViewModel` (PlantCard quick-log buttons)
 * and `CalendarViewModel` (day-sheet quick-log buttons), extracted to avoid duplicating the same
 * ~150 lines of care-log + adaptive-interval + photo-reminder logic across both ViewModels.
 *
 * Each ViewModel resolves its own [Plant] from its own `plantsWithStatus` state, calls the
 * relevant method here, and maps the returned domain object (message string, [QuickWaterSuggestion],
 * or [PhotoReminderRequest]) onto its own StateFlow/SharedFlow. This use case owns no UI-facing
 * state itself — every method is a plain suspend function.
 */
// #568 added two small adaptive-watering helpers to this already-cohesive choke point; splitting
// them out would scatter closely related logic across files for no readability gain.
@Suppress("TooManyFunctions", "LongParameterList")
class QuickLogUseCase(
    private val application: Application,
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    private val dataStore: DataStore<Preferences>,
    private val database: PlantDatabase,
    private val wateringAdjustmentRepository: WateringAdjustmentRepository,
    private val nowProvider: () -> Long = System::currentTimeMillis
) {

    /**
     * Result of a quick-log attempt. [logged] is false when the log was skipped because [plant]
     * already has a WATER/FERTILIZE log for the current calendar day (#509) — callers should skip
     * side effects (adaptive-interval suggestion, photo-reminder check) in that case. [waterPaired]
     * is true only when a liquid-fertilizer quick action actually inserted the paired WATER log
     * (it's suppressed, but FERTILIZE still proceeds, when the plant was already watered today).
     */
    data class QuickLogOutcome(
        val message: String,
        val logged: Boolean,
        val waterPaired: Boolean = false,
        val suggestion: QuickWaterSuggestion? = null
    )

    /** Summary of a [bulkLog] run: how many of [totalCount] plants were actually logged vs. skipped. */
    data class BulkLogResult(val loggedCount: Int, val skippedCount: Int, val totalCount: Int)

    /**
     * Logs [careType] for every plant in [plants] inside a single Room transaction, so a bulk
     * care action is applied atomically — a killed process can't leave some of the selected
     * plants logged and others not (#448). Watering carries no reason (see below); other
     * care types route through [quickLog] so liquid-fertilizer plants still get a paired watering.
     * Plants that already have today's log for [careType] are skipped (#509) rather than aborting
     * the whole batch. Per-plant interval-suggestion and photo-reminder side effects are
     * intentionally not surfaced here — bulk callers skip those dialogs.
     *
     * A bulk watering carries no reason: the user never saw a per-plant prompt, so it passes `null`
     * (#586, product ADR-0030 — replacing the defaulted [WateringFeedback.JUST_RIGHT] this used to
     * write). For an on-schedule plant that is the same quiet gap observation as before; for an
     * off-schedule one the adaptive model excludes it from base learning, which is the safe direction
     * for an action the user took without attributing anything to any particular plant.
     */
    suspend fun bulkLog(plants: List<Plant>, careType: CareType): BulkLogResult {
        var loggedCount = 0
        database.withTransaction {
            for (plant in plants) {
                val outcome = if (careType == CareType.WATER) {
                    quickWaterWithReason(plant, reason = null)
                } else {
                    quickLog(plant, careType)
                }
                if (outcome.logged) loggedCount++
            }
        }
        return BulkLogResult(
            loggedCount = loggedCount,
            skippedCount = plants.size - loggedCount,
            totalCount = plants.size
        )
    }

    /**
     * Logs [careType] for [plant]. Returns [QuickLogOutcome.logged] = false without inserting
     * anything if [plant] already has a [careType] log today (WATER/FERTILIZE only, #509). For a
     * liquid-fertilizer plant, the "already watered today" check runs before the FERTILIZE insert
     * so the paired WATER insert can be suppressed without racing against itself.
     */
    suspend fun quickLog(plant: Plant, careType: CareType): QuickLogOutcome {
        if (isDuplicateGuarded(careType) && hasLoggedToday(plant.id, careType)) {
            return QuickLogOutcome(message = alreadyLoggedMessage(plant, careType), logged = false)
        }
        val alreadyWateredToday = careType == CareType.FERTILIZE &&
            plant.useLiquidFertilizer &&
            hasLoggedToday(plant.id, CareType.WATER)

        val now = System.currentTimeMillis()
        val log = CareLog(
            plantId = plant.id,
            careType = careType,
            loggedAt = now,
            wateringFeedback = null,
            fertilizerType = if (careType == CareType.FERTILIZE && plant.useLiquidFertilizer) FertilizerType.LIQUID else FertilizerType.UNSPECIFIED
        )
        careLogRepository.addLog(log)
        val waterPaired = careType == CareType.FERTILIZE && plant.useLiquidFertilizer && !alreadyWateredToday
        if (waterPaired) {
            // No reason: the user fertilized, and the watering came along with it (ADR-0008) — they
            // were never asked why they watered, so nothing is attributed (#586).
            careLogRepository.addLog(
                CareLog(
                    plantId = plant.id,
                    careType = CareType.WATER,
                    loggedAt = now,
                    wateringFeedback = null
                )
            )
            clearWateringOverrideIfActive(plant.id)
        }
        val message = when (careType) {
            CareType.FERTILIZE -> if (waterPaired) {
                application.getString(R.string.quick_log_watered_and_fertilized, plant.name)
            } else {
                application.getString(R.string.quick_log_fertilized, plant.name)
            }
            else -> application.getString(
                R.string.quick_log_other,
                application.getString(careType.labelRes()),
                plant.name
            )
        }
        return QuickLogOutcome(message = message, logged = true, waterPaired = waterPaired)
    }

    /**
     * Logs a watering with the given [reason] (#586, product ADR-0030), clears any active skip
     * override, and returns a [QuickLogOutcome] with a [QuickWaterSuggestion] if the adaptive
     * interval system produces one. Returns [QuickLogOutcome.logged] = false without inserting
     * anything if [plant] already has a WATER log today (#509).
     *
     * [reason] is `null` for an on-schedule watering (no prompt appears at all — the fast path), for
     * a watering the user logged without choosing a reason, and for surfaces that never ask (bulk
     * log, the notification's "Watered" action). Which of those it was is never stored: the model
     * separates them from timing alone (see [CareSchedule.computeAdaptiveInterval]).
     */
    suspend fun quickWaterWithReason(plant: Plant, reason: WateringReason?): QuickLogOutcome {
        if (hasLoggedToday(plant.id, CareType.WATER)) {
            return QuickLogOutcome(message = alreadyLoggedMessage(plant, CareType.WATER), logged = false)
        }
        val feedback = reason?.toWateringFeedback()
        val now = System.currentTimeMillis()
        careLogRepository.addLog(
            CareLog(
                plantId = plant.id,
                careType = CareType.WATER,
                loggedAt = now,
                wateringFeedback = feedback
            )
        )
        clearWateringOverrideIfActive(plant.id)
        val suggestion = computeSuggestion(plant, feedback)
        return QuickLogOutcome(
            message = application.getString(R.string.quick_log_watered, plant.name),
            logged = true,
            suggestion = suggestion
        )
    }

    /**
     * Logs a paired FERTILIZE + WATER entry for liquid-fertilizer plants, mirroring
     * [quickWaterWithReason] — the paired watering is a watering like any other, so the same #586
     * reason prompt governs it. Returns [QuickLogOutcome.logged] = false without inserting
     * anything if [plant] already has a FERTILIZE log today. If [plant] was already watered today,
     * the paired WATER insert is suppressed (checked before the FERTILIZE insert so it can't race
     * against a WATER row inserted earlier in this same call) but the FERTILIZE log still proceeds
     * (#509).
     */
    suspend fun quickLiquidFertilizeWithReason(plant: Plant, reason: WateringReason?): QuickLogOutcome {
        if (hasLoggedToday(plant.id, CareType.FERTILIZE)) {
            return QuickLogOutcome(message = alreadyLoggedMessage(plant, CareType.FERTILIZE), logged = false)
        }
        val feedback = reason?.toWateringFeedback()
        val alreadyWateredToday = hasLoggedToday(plant.id, CareType.WATER)

        val now = System.currentTimeMillis()
        careLogRepository.addLog(
            CareLog(
                plantId = plant.id,
                careType = CareType.FERTILIZE,
                loggedAt = now,
                wateringFeedback = null,
                fertilizerType = FertilizerType.LIQUID
            )
        )

        return if (alreadyWateredToday) {
            QuickLogOutcome(
                message = application.getString(R.string.quick_log_fertilized, plant.name),
                logged = true,
                waterPaired = false
            )
        } else {
            careLogRepository.addLog(
                CareLog(
                    plantId = plant.id,
                    careType = CareType.WATER,
                    loggedAt = now,
                    wateringFeedback = feedback
                )
            )
            clearWateringOverrideIfActive(plant.id)
            QuickLogOutcome(
                message = application.getString(R.string.quick_log_watered_and_fertilized, plant.name),
                logged = true,
                waterPaired = true,
                suggestion = computeSuggestion(plant, feedback)
            )
        }
    }

    /**
     * Records a "Soil still moist" observation: a [CareType.CHECK] log (`wateringFeedback = TOO_SOON`
     * — the plant was checked and not watered) and a [Plant.wateringDueDateOverride] set to
     * [newDueAtMillis]. Reached from the Reschedule reason prompt in the app (#586, product ADR-0030)
     * and from the check-reminders notification's Still-moist action (#570, `check_reminders` feature
     * flag, `StillMoistReceiver`) — one call site, so the two paths cannot drift.
     *
     * [newDueAtMillis] replaces #570's flat `+1 day` constant, which could not clear "due" for a plant
     * overdue by two or more days while the same-day guard blocked a second tap. In the app the user
     * picks the date; the notification, which has no picker, passes
     * [suggestedStillMoistDeferralDays] applied to now. Returns `false` without inserting anything if
     * [plant] already has a CHECK log today — a notification firing the action twice in one day (e.g.
     * the "Run reminder check now" debug action) shouldn't double-log (#509-style guard via
     * [isDuplicateGuarded]).
     *
     * Feeds the observation into [CareSchedule.computeAdaptiveInterval] only when `adaptive_watering`
     * is on, and only updates [Plant.wateringConfidence] — it never silently rewrites the stored
     * interval itself, mirroring every other quick-log surface's "confidence updates regardless of
     * whether a suggestion is ever shown/applied" rule (no dialog is ever shown here, so there is no
     * "apply" step to silently substitute for). The *length* of the deferral is never an input to the
     * model — only the reason is (#586): a "soil still moist" reschedule of +1 day and one of +5 teach
     * exactly the same thing.
     *
     * The override and (when adaptive watering is on) confidence are written in a single
     * [PlantRepository.updatePlant] call built off this same [plant] snapshot (#612) — two sequential
     * `.copy()`/`updatePlant` calls off the same stale snapshot let the second silently revert the
     * first's [Plant.wateringDueDateOverride] write.
     */
    suspend fun recordStillMoistCheck(plant: Plant, newDueAtMillis: Long): Boolean {
        if (isDuplicateGuarded(CareType.CHECK) && hasLoggedToday(plant.id, CareType.CHECK)) {
            return false
        }
        val now = System.currentTimeMillis()
        careLogRepository.addLog(
            CareLog(
                plantId = plant.id,
                careType = CareType.CHECK,
                loggedAt = now,
                wateringFeedback = WateringFeedback.TOO_SOON
            )
        )

        val updatedConfidence = if (isAdaptiveWateringEnabled()) {
            recordStillMoistAdaptiveObservation(plant, now)
        } else {
            null
        }

        plantRepository.updatePlant(
            plant.copy(
                wateringDueDateOverride = newDueAtMillis,
                wateringConfidence = updatedConfidence ?: plant.wateringConfidence,
                updatedAt = now
            )
        )
        return true
    }

    /**
     * How many days a "Soil still moist" observation suggests deferring by (#586, product ADR-0030),
     * derived from the interval the adaptive model would land on *after* this observation rather than
     * from a constant: "come back when the freshly-lengthened interval says it is due", i.e.
     * `newBase - observedGap`, floored at one day so it always moves the date forward.
     *
     * Falls back to [DEFAULT_STILL_MOIST_DEFERRAL_DAYS] (#570's flat +1 day) whenever there is nothing
     * to derive from: adaptive watering off, no interval configured, or no previous watering. This is
     * a preview — it writes nothing — so the in-app picker can open on it and the notification action,
     * which has no picker, can apply it directly.
     */
    @Suppress("ReturnCount")
    suspend fun suggestedStillMoistDeferralDays(plant: Plant): Int {
        if (!isAdaptiveWateringEnabled()) return DEFAULT_STILL_MOIST_DEFERRAL_DAYS
        val currentInterval = plant.wateringIntervalDays ?: return DEFAULT_STILL_MOIST_DEFERRAL_DAYS
        val lastWatering = careLogRepository.getLastLogOfType(plant.id, CareType.WATER)
            ?: return DEFAULT_STILL_MOIST_DEFERRAL_DAYS
        val observedIntervalDays = CareSchedule.daysBetween(lastWatering.loggedAt, nowProvider())
        if (observedIntervalDays <= 0) return DEFAULT_STILL_MOIST_DEFERRAL_DAYS
        val result = computeStillMoistAdaptiveInterval(plant, observedIntervalDays)
        return (result.intervalDays - observedIntervalDays).coerceAtLeast(DEFAULT_STILL_MOIST_DEFERRAL_DAYS)
    }

    /**
     * Returns the new [Plant.wateringConfidence] if this observation changes it, or `null` when
     * there's nothing to derive from or confidence is unchanged — [recordStillMoistCheck] folds the
     * result into its single combined `updatePlant` call rather than writing here (#612).
     */
    @Suppress("ReturnCount")
    private suspend fun recordStillMoistAdaptiveObservation(plant: Plant, now: Long): Int? {
        val currentInterval = plant.wateringIntervalDays ?: return null
        val lastWatering = careLogRepository.getLastLogOfType(plant.id, CareType.WATER) ?: return null
        val actualIntervalDays = CareSchedule.daysBetween(lastWatering.loggedAt, now)
        if (actualIntervalDays <= 0) return null

        val currentBase = currentAdaptiveBaseIntervalDays(plant, currentInterval)
        val result = computeStillMoistAdaptiveInterval(plant, actualIntervalDays)
        wateringAdjustmentRepository.addAdjustment(
            WateringAdjustment(
                plantId = plant.id,
                triggeredAt = now,
                trigger = WateringAdjustmentTrigger.CHECK_STILL_MOIST,
                beforeIntervalDays = currentBase,
                afterIntervalDays = result.intervalDays
            )
        )
        return result.confidence.takeIf { it != plant.wateringConfidence }
    }

    /**
     * The one place a "soil still moist" observation is turned into an [CareSchedule.AdaptiveInterval]
     * — shared by [suggestedStillMoistDeferralDays] (preview, writes nothing) and
     * [recordStillMoistAdaptiveObservation] (the real write), so the deferral the picker suggests and
     * the interval the model actually learns are computed by the same code (#586).
     */
    private suspend fun computeStillMoistAdaptiveInterval(
        plant: Plant,
        observedIntervalDays: Int
    ): CareSchedule.AdaptiveInterval {
        val recentFeedback = careLogRepository.getRecentWaterings(plant.id, limit = RECENT_WATERINGS_WINDOW)
            .map { it.wateringFeedback }
        return CareSchedule.computeAdaptiveInterval(
            feedback = WateringFeedback.TOO_SOON,
            observedIntervalDays = deseasonalizedObservedIntervalDays(observedIntervalDays, plant.pinIntervalToBase),
            currentBaseIntervalDays = currentAdaptiveBaseIntervalDays(
                plant,
                plant.wateringIntervalDays ?: observedIntervalDays
            ),
            currentConfidence = plant.wateringConfidence,
            recentFeedback = recentFeedback
        )
    }

    /**
     * Builds a [PhotoReminderRequest] for [plantId] if the feature is enabled, the plant hasn't
     * already been reminded this session (shared across surfaces via
     * [PhotoReminderPolicy.shownThisSession]), and the newest photo across plant photos and
     * care-log photos is at least [PhotoReminderPolicy.PHOTO_REMINDER_INTERVAL_DAYS] days old.
     * Returns null when no reminder should be shown. Callers decide what to do with the result
     * (e.g. suppress it while another dialog is showing) — that UI-level rule stays in the screen.
     */
    suspend fun maybeBuildPhotoReminderRequest(plantId: Long): PhotoReminderRequest? {
        val enabled = dataStore.data.first()[SettingsKeys.PHOTO_REMINDER_ENABLED] ?: false
        if (!enabled) return null
        if (plantId in PhotoReminderPolicy.shownThisSession) return null
        val plant = plantRepository.getPlantById(plantId).first() ?: return null
        val lastPlantPhotoTs = plantPhotoRepository.getPhotosForPlantOnce(plantId)
            .maxOfOrNull { it.capturedAt }
        val lastCareLogPhotoTs = careLogRepository.getPhotoLogsForPlant(plantId).first()
            .mapNotNull { log -> log.photoUri?.let { log.loggedAt } }
            .maxOrNull()
        val lastPhotoTs = listOfNotNull(lastPlantPhotoTs, lastCareLogPhotoTs).maxOrNull()
        val daysSince = PhotoReminderPolicy.lastPhotoDaysSince(lastPhotoTs, plant.createdAt)
        if (daysSince >= PhotoReminderPolicy.PHOTO_REMINDER_INTERVAL_DAYS) {
            PhotoReminderPolicy.shownThisSession.add(plantId)
            return PhotoReminderRequest(plantId, plant.name, daysSince)
        }
        return null
    }

    private fun isDuplicateGuarded(careType: CareType) =
        careType == CareType.WATER || careType == CareType.FERTILIZE || careType == CareType.CHECK

    private suspend fun hasLoggedToday(plantId: Long, careType: CareType): Boolean =
        careLogRepository.hasLogOfTypeOnDay(plantId, careType, System.currentTimeMillis())

    private fun alreadyLoggedMessage(plant: Plant, careType: CareType): String = when (careType) {
        CareType.WATER -> application.getString(R.string.quick_log_already_watered, plant.name)
        CareType.FERTILIZE -> application.getString(R.string.quick_log_already_fertilized, plant.name)
        else -> error("No duplicate guard defined for $careType")
    }

    /**
     * [feedback] may be `null` (#570, product ADR-0027) — the quick-water sheet's chip collapsed to
     * one optional flag, so `null` is now the dominant case. The legacy (flag-off) branch still
     * needs an explicit value to produce a suggestion; the adaptive branch accepts `null` directly.
     */
    private suspend fun computeSuggestion(plant: Plant, feedback: WateringFeedback?): QuickWaterSuggestion? {
        val lastTwo = careLogRepository.getLastTwoWaterings(plant.id)
        if (lastTwo.size < 2) return null
        val current = plant.wateringIntervalDays ?: return null
        val actual = CareSchedule.daysBetween(lastTwo[1].loggedAt, lastTwo[0].loggedAt)
        if (actual <= 0) return null
        val suggestion = if (isAdaptiveWateringEnabled()) {
            adaptWateringInterval(plant, feedback, actual, current)
        } else {
            feedback?.let { CareSchedule.computeSuggestedInterval(it, actual, current) } ?: return null
        }
        return if (suggestion != current) QuickWaterSuggestion(plant.id, plant.name, suggestion) else null
    }

    /**
     * Applies the multiplicative + confidence-weighted model (#568, technical ADR-0021) and
     * persists the resulting [Plant.wateringConfidence] immediately, independent of whether the
     * caller ends up surfacing/applying the returned suggestion. [feedback] may be `null` (#570) — a
     * silent gap-only observation, capped at [CareSchedule.NEUTRAL_OBSERVATION_GAIN].
     */
    private suspend fun adaptWateringInterval(
        plant: Plant,
        feedback: WateringFeedback?,
        actualIntervalDays: Int,
        currentInterval: Int
    ): Int {
        val recentFeedback = careLogRepository.getRecentWaterings(plant.id, limit = RECENT_WATERINGS_WINDOW)
            .map { it.wateringFeedback }
        val currentBase = currentAdaptiveBaseIntervalDays(plant, currentInterval)
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = feedback,
            observedIntervalDays = deseasonalizedObservedIntervalDays(actualIntervalDays, plant.pinIntervalToBase),
            currentBaseIntervalDays = currentBase,
            currentConfidence = plant.wateringConfidence,
            recentFeedback = recentFeedback
        )
        val now = System.currentTimeMillis()
        if (result.confidence != plant.wateringConfidence) {
            plantRepository.updatePlant(plant.copy(wateringConfidence = result.confidence, updatedAt = now))
        }
        wateringAdjustmentRepository.addAdjustment(
            WateringAdjustment(
                plantId = plant.id,
                triggeredAt = now,
                trigger = adjustmentTriggerFor(feedback, result.excludedFromBaseLearning),
                beforeIntervalDays = currentBase,
                afterIntervalDays = result.intervalDays
            )
        )
        return result.intervalDays
    }

    private fun adjustmentTriggerFor(
        feedback: WateringFeedback?,
        excludedFromBaseLearning: Boolean
    ): WateringAdjustmentTrigger = when {
        excludedFromBaseLearning -> WateringAdjustmentTrigger.WATER_NOT_ATTRIBUTED
        feedback == WateringFeedback.TOO_SOON -> WateringAdjustmentTrigger.WATER_TOO_SOON
        feedback == WateringFeedback.TOO_LATE -> WateringAdjustmentTrigger.WATER_TOO_LATE
        feedback == WateringFeedback.JUST_RIGHT -> WateringAdjustmentTrigger.WATER_JUST_RIGHT
        else -> WateringAdjustmentTrigger.WATER_NEUTRAL
    }

    private suspend fun isAdaptiveWateringEnabled(): Boolean =
        dataStore.data.first()[FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING)]
            ?: FeatureFlagRegistry.ADAPTIVE_WATERING.default

    /**
     * "Interaction with Part 1" (#569): `observedBase = observedGap / season(dateOfGap)`, so a
     * seasonal correction isn't baked into [Plant.wateringConfidence] as a permanent thirst change.
     * A no-op when SEASONAL_WATERING is off or [pinIntervalToBase] is set — [CareSchedule]'s due-date
     * math never applies the seasonal curve for a pinned plant, so its observed gaps are already
     * flat and must not be seasonally corrected.
     */
    @Suppress("ReturnCount")
    private suspend fun deseasonalizedObservedIntervalDays(actualIntervalDays: Int, pinIntervalToBase: Boolean): Int {
        if (pinIntervalToBase) return actualIntervalDays
        val amplitude = dataStore.seasonalAmplitudeOnce()
        if (amplitude == 0.0) return actualIntervalDays
        return SeasonalWatering.deseasonalizeToDays(
            actualIntervalDays,
            nowProvider().toLocalDate(),
            amplitude,
            SeasonalWatering.currentHemisphere()
        )
    }

    /**
     * The watering-model input for `currentBaseIntervalDays` (#572, amending technical ADR-0021):
     * season-neutral, reading [Plant.wateringBaseIntervalDays] instead of the raw (possibly seasonally
     * stale) [configuredIntervalDays] whenever `SEASONAL_WATERING` is on and the plant isn't pinned.
     * Prior to this fix every call site fed the model a value that only ever changed on a manual
     * edit, silently diverging from what [CareSchedule.computeStatus] actually used for the due date.
     */
    @Suppress("ReturnCount")
    private suspend fun currentAdaptiveBaseIntervalDays(plant: Plant, configuredIntervalDays: Int): Int {
        if (plant.pinIntervalToBase) return configuredIntervalDays
        val amplitude = dataStore.seasonalAmplitudeOnce()
        if (amplitude == 0.0) return configuredIntervalDays
        return (plant.wateringBaseIntervalDays ?: configuredIntervalDays.toDouble()).roundToInt()
    }

    private suspend fun clearWateringOverrideIfActive(plantId: Long) {
        plantRepository.getPlantById(plantId).first()?.let { p ->
            if (p.wateringDueDateOverride != null) {
                plantRepository.updatePlant(
                    p.copy(wateringDueDateOverride = null, updatedAt = System.currentTimeMillis())
                )
            }
        }
    }

    companion object {
        private const val RECENT_WATERINGS_WINDOW = 3

        /**
         * Floor and fallback for [suggestedStillMoistDeferralDays] (#586) — also the value #570's
         * `STILL_MOIST_DEFERRAL_DAYS` applied unconditionally, kept only as the "nothing to derive
         * from" case (adaptive watering off, no interval, no previous watering).
         */
        const val DEFAULT_STILL_MOIST_DEFERRAL_DAYS = 1
    }
}
