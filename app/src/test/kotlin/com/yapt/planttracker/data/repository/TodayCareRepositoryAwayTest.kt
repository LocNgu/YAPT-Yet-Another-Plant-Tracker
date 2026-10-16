package com.yapt.planttracker.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Away mode (#897, product ADR-0065): the repository feeds the trips into the queue and reacts to changes. */
class TodayCareRepositoryAwayTest {

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 7, 20)
    private fun millis(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private val trip = AwayPeriod(id = 1L, firstDay = LocalDate.of(2026, 7, 1), lastDay = LocalDate.of(2026, 7, 15))

    private fun repository(trips: MutableStateFlow<List<AwayPeriod>>?): TodayCareRepository {
        val plant = Plant(id = 1L, name = "Fern", wateringIntervalDays = 7, createdAt = millis(today.minusDays(90)))
        val plantRepository = mockk<PlantRepository> { every { getAllPlants() } returns flowOf(listOf(plant)) }
        val careLogRepository = mockk<CareLogRepository> {
            every { getAllLogs() } returns flowOf(
                listOf(
                    CareLog(
                        plantId = 1L,
                        careType = CareType.WATER,
                        loggedAt = millis(LocalDate.of(2026, 6, 25))
                    )
                )
            )
        }
        val customReminderRepository = mockk<CustomReminderRepository> {
            every { getAllReminders() } returns flowOf(emptyList())
        }
        val plantIssueRepository = mockk<PlantIssueRepository> { every { getAllIssues() } returns flowOf(emptyList()) }
        val plantPhotoRepository = mockk<PlantPhotoRepository> { every { getAllPhotos() } returns flowOf(emptyList()) }
        val dataStore = mockk<DataStore<Preferences>> { every { data } returns flowOf(emptyPreferences()) }
        val awayRepository = trips?.let { flow -> mockk<AwayPeriodRepository> { every { observeAll() } returns flow } }
        return TodayCareRepository(
            plantRepository,
            careLogRepository,
            customReminderRepository,
            plantIssueRepository,
            plantPhotoRepository,
            dataStore,
            flowOf(today),
            awayRepository
        )
    }

    @Test
    fun `the queue marks the watering away-spanning and follows a change to the trips`() = runTest {
        val trips = MutableStateFlow(listOf(trip))

        repository(trips).observeQueue().test {
            assertTrue(requireNotNull(awaitItem().tasks.single().wateringAction).isAwaySpanning)

            trips.value = emptyList()

            assertFalse(requireNotNull(awaitItem().tasks.single().wateringAction).isAwaySpanning)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `without an away repository the queue is unchanged`() = runTest {
        repository(null).observeQueue().test {
            assertFalse(requireNotNull(awaitItem().tasks.single().wateringAction).isAwaySpanning)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
