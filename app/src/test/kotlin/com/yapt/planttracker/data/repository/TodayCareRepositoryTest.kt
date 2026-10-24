package com.yapt.planttracker.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.today.TodayTaskBucket
import com.yapt.planttracker.domain.today.TodayWateredPlant
import com.yapt.planttracker.util.dayChangeTicker
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalCoroutinesApi::class)
class TodayCareRepositoryTest {

    @Test
    fun `queue recomputes when canonical day ticker observes clock advancement`() = runTest {
        val zone = ZoneId.of("UTC")
        val firstDay = LocalDate.of(2026, 9, 27)
        var now = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()
        val plant = Plant(
            id = 1L,
            name = "Fern",
            wateringIntervalDays = 1,
            pinIntervalToBase = true,
            createdAt = now,
            updatedAt = now
        )
        val plantRepository = mockk<PlantRepository> {
            every { getAllPlants() } returns flowOf(listOf(plant))
        }
        val careLogRepository = mockk<CareLogRepository> {
            every { getAllLogs() } returns flowOf(
                listOf(
                    CareLog(
                        plantId = plant.id,
                        careType = CareType.WATER,
                        loggedAt = firstDay.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    )
                )
            )
        }
        val customReminderRepository = mockk<CustomReminderRepository> {
            every { getAllReminders() } returns flowOf(emptyList())
        }
        val plantIssueRepository = mockk<PlantIssueRepository> {
            every { getAllIssues() } returns flowOf(emptyList())
        }
        val plantPhotoRepository = mockk<PlantPhotoRepository> {
            every { getAllPhotos() } returns flowOf(emptyList())
        }
        val dataStore = mockk<DataStore<Preferences>> {
            every { data } returns flowOf(emptyPreferences())
        }
        val repository = TodayCareRepository(
            plantRepository,
            careLogRepository,
            customReminderRepository,
            plantIssueRepository,
            plantPhotoRepository,
            dataStore,
            dayChangeTicker(zone, nowProvider = { now }, maxPollIntervalMs = POLL_MS)
        )

        repository.observeQueue().test {
            assertEquals(TodayTaskBucket.Today, awaitItem().tasks.single().bucket)

            now = firstDay.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            advanceTimeBy(POLL_MS)
            runCurrent()

            assertEquals(TodayTaskBucket.Overdue, awaitItem().tasks.single().bucket)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `watered today list empties when an injected day-change ticker rolls to the next day`() = runTest {
        val firstDay = LocalDate.of(2026, 9, 27)
        val wateredAt = firstDay.atTime(LocalTime.NOON).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val fern = Plant(id = 1L, name = "Fern", createdAt = wateredAt, updatedAt = wateredAt)
        val plantRepository = mockk<PlantRepository> {
            every { getAllPlants() } returns flowOf(listOf(fern))
        }
        val careLogRepository = mockk<CareLogRepository> {
            every { getAllLogs() } returns flowOf(
                listOf(CareLog(plantId = fern.id, careType = CareType.WATER, loggedAt = wateredAt))
            )
        }
        val customReminderRepository = mockk<CustomReminderRepository> {
            every { getAllReminders() } returns flowOf(emptyList())
        }
        val plantIssueRepository = mockk<PlantIssueRepository> {
            every { getAllIssues() } returns flowOf(emptyList())
        }
        val plantPhotoRepository = mockk<PlantPhotoRepository> {
            every { getAllPhotos() } returns flowOf(emptyList())
        }
        val dataStore = mockk<DataStore<Preferences>> {
            every { data } returns flowOf(emptyPreferences())
        }
        val ticker = MutableSharedFlow<LocalDate>(replay = 1)
        ticker.tryEmit(firstDay)
        val repository = TodayCareRepository(
            plantRepository,
            careLogRepository,
            customReminderRepository,
            plantIssueRepository,
            plantPhotoRepository,
            dataStore,
            ticker
        )

        repository.observeQueue().test {
            assertEquals(listOf(fern.id), awaitItem().wateredToday.map { it.plant.id })

            ticker.emit(firstDay.plusDays(1))

            assertEquals(emptyList<TodayWateredPlant>(), awaitItem().wateredToday)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val POLL_MS = 100L
    }
}
