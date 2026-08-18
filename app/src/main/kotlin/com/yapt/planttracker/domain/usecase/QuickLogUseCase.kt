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
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.PhotoReminderRequest
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.reminder.PhotoReminderPolicy
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.ui.util.labelRes
import kotlinx.coroutines.flow.first

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
class QuickLogUseCase(
    private val application: Application,
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    private val dataStore: DataStore<Preferences>,
    private val database: PlantDatabase
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
     * plants logged and others not (#448). Watering uses [WateringFeedback.JUST_RIGHT]; other
     * care types route through [quickLog] so liquid-fertilizer plants still get a paired watering.
     * Plants that already have today's log for [careType] are skipped (#509) rather than aborting
     * the whole batch. Per-plant interval-suggestion and photo-reminder side effects are
     * intentionally not surfaced here — bulk callers skip those dialogs.
     */
    suspend fun bulkLog(plants: List<Plant>, careType: CareType): BulkLogResult {
        var loggedCount = 0
        database.withTransaction {
            for (plant in plants) {
                val outcome = if (careType == CareType.WATER) {
                    quickWaterWithFeedback(plant, WateringFeedback.JUST_RIGHT)
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
            careLogRepository.addLog(
                CareLog(
                    plantId = plant.id,
                    careType = CareType.WATER,
                    loggedAt = now,
                    wateringFeedback = WateringFeedback.JUST_RIGHT
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
     * Logs a watering with the given [feedback] (called from the quick-water bottom sheet),
     * clears any active skip override, and returns a [QuickLogOutcome] with a
     * [QuickWaterSuggestion] if the adaptive interval system produces one. Returns
     * [QuickLogOutcome.logged] = false without inserting anything if [plant] already has a WATER
     * log today (#509).
     */
    suspend fun quickWaterWithFeedback(plant: Plant, feedback: WateringFeedback?): QuickLogOutcome {
        if (hasLoggedToday(plant.id, CareType.WATER)) {
            return QuickLogOutcome(message = alreadyLoggedMessage(plant, CareType.WATER), logged = false)
        }
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
     * [quickWaterWithFeedback]. Returns [QuickLogOutcome.logged] = false without inserting
     * anything if [plant] already has a FERTILIZE log today. If [plant] was already watered today,
     * the paired WATER insert is suppressed (checked before the FERTILIZE insert so it can't race
     * against a WATER row inserted earlier in this same call) but the FERTILIZE log still proceeds
     * (#509).
     */
    suspend fun quickLiquidFertilizeWithFeedback(plant: Plant, feedback: WateringFeedback?): QuickLogOutcome {
        if (hasLoggedToday(plant.id, CareType.FERTILIZE)) {
            return QuickLogOutcome(message = alreadyLoggedMessage(plant, CareType.FERTILIZE), logged = false)
        }
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

    private fun isDuplicateGuarded(careType: CareType) = careType == CareType.WATER || careType == CareType.FERTILIZE

    private suspend fun hasLoggedToday(plantId: Long, careType: CareType): Boolean =
        careLogRepository.hasLogOfTypeOnDay(plantId, careType, System.currentTimeMillis())

    private fun alreadyLoggedMessage(plant: Plant, careType: CareType): String = when (careType) {
        CareType.WATER -> application.getString(R.string.quick_log_already_watered, plant.name)
        CareType.FERTILIZE -> application.getString(R.string.quick_log_already_fertilized, plant.name)
        else -> error("No duplicate guard defined for $careType")
    }

    private suspend fun computeSuggestion(plant: Plant, feedback: WateringFeedback?): QuickWaterSuggestion? {
        if (feedback == null) return null
        val lastTwo = careLogRepository.getLastTwoWaterings(plant.id)
        if (lastTwo.size < 2) return null
        val current = plant.wateringIntervalDays ?: return null
        val actual = CareSchedule.daysBetween(lastTwo[1].loggedAt, lastTwo[0].loggedAt)
        if (actual <= 0) return null
        val suggestion = CareSchedule.computeSuggestedInterval(feedback, actual, current)
        return if (suggestion != current) QuickWaterSuggestion(plant.id, plant.name, suggestion) else null
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
}
