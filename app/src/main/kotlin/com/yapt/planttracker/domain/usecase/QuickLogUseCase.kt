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
     * Logs [careType] for every plant in [plants] inside a single Room transaction, so a bulk
     * care action is applied atomically — a killed process can't leave some of the selected
     * plants logged and others not (#448). Watering uses [WateringFeedback.JUST_RIGHT]; other
     * care types route through [quickLog] so liquid-fertilizer plants still get a paired watering.
     * Per-plant interval-suggestion and photo-reminder side effects are intentionally not surfaced
     * here — bulk callers skip those dialogs.
     */
    suspend fun bulkLog(plants: List<Plant>, careType: CareType) {
        database.withTransaction {
            for (plant in plants) {
                if (careType == CareType.WATER) {
                    quickWaterWithFeedback(plant, WateringFeedback.JUST_RIGHT)
                } else {
                    quickLog(plant, careType)
                }
            }
        }
    }

    /** Logs [careType] for [plant] and returns the snackbar message to show. */
    suspend fun quickLog(plant: Plant, careType: CareType): String {
        val now = System.currentTimeMillis()
        val log = CareLog(
            plantId = plant.id,
            careType = careType,
            loggedAt = now,
            wateringFeedback = null,
            fertilizerType = if (careType == CareType.FERTILIZE && plant.useLiquidFertilizer) FertilizerType.LIQUID else FertilizerType.UNSPECIFIED
        )
        careLogRepository.addLog(log)
        if (careType == CareType.FERTILIZE && plant.useLiquidFertilizer) {
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
        return when (careType) {
            CareType.FERTILIZE -> if (plant.useLiquidFertilizer) {
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
    }

    /**
     * Logs a watering with the given [feedback] (called from the quick-water bottom sheet),
     * clears any active skip override, and returns a [QuickWaterSuggestion] if the adaptive
     * interval system produces one.
     */
    suspend fun quickWaterWithFeedback(plant: Plant, feedback: WateringFeedback): QuickWaterSuggestion? {
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
        return computeSuggestion(plant, feedback)
    }

    /** Logs a paired FERTILIZE + WATER entry for liquid-fertilizer plants, mirroring [quickWaterWithFeedback]. */
    suspend fun quickLiquidFertilizeWithFeedback(plant: Plant, feedback: WateringFeedback): QuickWaterSuggestion? {
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
        careLogRepository.addLog(
            CareLog(
                plantId = plant.id,
                careType = CareType.WATER,
                loggedAt = now,
                wateringFeedback = feedback
            )
        )
        clearWateringOverrideIfActive(plant.id)
        return computeSuggestion(plant, feedback)
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

    private suspend fun computeSuggestion(plant: Plant, feedback: WateringFeedback): QuickWaterSuggestion? {
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
