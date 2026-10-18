package com.yapt.planttracker.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeFlow
import com.yapt.planttracker.domain.today.TodayQueueAggregator
import com.yapt.planttracker.domain.today.TodayQueueInput
import com.yapt.planttracker.domain.today.TodayQueueSnapshot
import com.yapt.planttracker.util.dayChangeTicker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate

@Suppress("LongParameterList")
class TodayCareRepository(
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val customReminderRepository: CustomReminderRepository,
    private val plantIssueRepository: PlantIssueRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    private val dataStore: DataStore<Preferences>,
    private val dayChangeFlow: Flow<LocalDate> = dayChangeTicker(),
    private val awayPeriodRepository: AwayPeriodRepository? = null
) {

    private data class CareData(
        val plants: List<com.yapt.planttracker.domain.model.Plant>,
        val logs: List<com.yapt.planttracker.domain.model.CareLog>,
        val reminders: List<com.yapt.planttracker.domain.model.CustomReminder>,
        val issues: List<com.yapt.planttracker.domain.model.PlantIssue>,
        val photos: List<com.yapt.planttracker.domain.model.PlantPhoto>
    )

    private data class QueueSettings(
        val day: java.time.LocalDate,
        val seasonalAmplitude: Double,
        val photoReminderEnabled: Boolean,
        val awayPeriods: List<AwayPeriod>,
        val horizonEnd: LocalDate?
    )

    fun observeQueue(): Flow<TodayQueueSnapshot> = combineQueue(flowOf(null))

    fun observeQueue(horizonEnd: Flow<LocalDate>): Flow<TodayQueueSnapshot> = combineQueue(horizonEnd)

    private fun combineQueue(horizonEnd: Flow<LocalDate?>): Flow<TodayQueueSnapshot> {
        val careData = combine(
            plantRepository.getAllPlants(),
            careLogRepository.getAllLogs(),
            customReminderRepository.getAllReminders(),
            plantIssueRepository.getAllIssues(),
            plantPhotoRepository.getAllPhotos()
        ) { plants, logs, reminders, issues, photos ->
            CareData(plants, logs, reminders, issues, photos)
        }
        val photoReminderEnabled = dataStore.data.map { prefs ->
            prefs[SettingsKeys.PHOTO_REMINDER_ENABLED] ?: false
        }
        val awayPeriods = awayPeriodRepository?.observeAll() ?: flowOf(emptyList())
        val settings = combine(
            dayChangeFlow,
            dataStore.seasonalAmplitudeFlow(),
            photoReminderEnabled,
            awayPeriods,
            horizonEnd
        ) { day, seasonalAmplitude, photosEnabled, periods, horizon ->
            QueueSettings(day, seasonalAmplitude, photosEnabled, periods, horizon)
        }
        return combine(careData, settings) { data, queueSettings ->
            TodayQueueAggregator.build(
                TodayQueueInput(
                    plants = data.plants,
                    careLogs = data.logs,
                    customReminders = data.reminders,
                    issues = data.issues,
                    photos = data.photos,
                    today = queueSettings.day,
                    seasonalAmplitude = queueSettings.seasonalAmplitude,
                    photoReminderEnabled = queueSettings.photoReminderEnabled,
                    awayPeriods = queueSettings.awayPeriods,
                    horizonEnd = queueSettings.horizonEnd ?: TodayQueueInput.defaultHorizonEnd(queueSettings.day)
                )
            )
        }
    }
}
