package com.yapt.planttracker.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.today.TodayTaskBucket
import com.yapt.planttracker.util.dayChangeTicker
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** `observeQueue(horizonEnd)` (#910): widening goes through the same combine, with no extra Room reads or tickers. */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayCareRepositoryHorizonTest {

    private val zone = ZoneId.systemDefault()
    private val firstDay = LocalDate.of(2026, 9, 27)
    private var now = firstDay.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun millis(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun plant(id: Long, intervalDays: Int) = Plant(
        id = id,
        name = "Plant $id",
        wateringIntervalDays = intervalDays,
        pinIntervalToBase = true,
        createdAt = now,
        updatedAt = now
    )

    private class Fixture(
        val repository: TodayCareRepository,
        val plantRepository: PlantRepository,
        val careLogRepository: CareLogRepository,
        val customReminderRepository: CustomReminderRepository,
        val plantIssueRepository: PlantIssueRepository,
        val plantPhotoRepository: PlantPhotoRepository
    )

    private fun fixture(dayChange: Flow<LocalDate>): Fixture {
        val soon = plant(id = 1L, intervalDays = 10)
        val later = plant(id = 2L, intervalDays = 10)
        val plantRepository = mockk<PlantRepository> {
            every { getAllPlants() } returns flowOf(listOf(soon, later))
        }
        val careLogRepository = mockk<CareLogRepository> {
            every { getAllLogs() } returns flowOf(
                listOf(
                    CareLog(plantId = soon.id, careType = CareType.WATER, loggedAt = millis(firstDay.minusDays(8))),
                    CareLog(plantId = later.id, careType = CareType.WATER, loggedAt = millis(firstDay))
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
        return Fixture(
            TodayCareRepository(
                plantRepository,
                careLogRepository,
                customReminderRepository,
                plantIssueRepository,
                plantPhotoRepository,
                dataStore,
                dayChange
            ),
            plantRepository,
            careLogRepository,
            customReminderRepository,
            plantIssueRepository,
            plantPhotoRepository
        )
    }

    @Test
    fun `no-arg queue keeps the default horizon while the overload emits a widened snapshot`() = runTest {
        val fixture = fixture(flowOf(firstDay))

        fixture.repository.observeQueue().test {
            assertEquals(listOf("water:1"), awaitItem().tasks.map { it.id })
            awaitComplete()
        }

        fixture.repository.observeQueue(flowOf(firstDay.plusDays(10))).test {
            val tasks = awaitItem().tasks
            assertEquals(listOf("water:1", "water:2"), tasks.map { it.id })
            assertEquals(TodayTaskBucket.Upcoming(firstDay.plusDays(2).toEpochDay()), tasks[0].bucket)
            assertEquals(TodayTaskBucket.Upcoming(firstDay.plusDays(10).toEpochDay()), tasks[1].bucket)
            awaitComplete()
        }
    }

    @Test
    fun `widening adds no repository reads`() = runTest {
        val fixture = fixture(flowOf(firstDay))

        fixture.repository.observeQueue(flowOf(firstDay.plusDays(42))).test {
            awaitItem()
            awaitComplete()
        }

        verify(exactly = 1) { fixture.plantRepository.getAllPlants() }
        verify(exactly = 1) { fixture.careLogRepository.getAllLogs() }
        verify(exactly = 1) { fixture.customReminderRepository.getAllReminders() }
        verify(exactly = 1) { fixture.plantIssueRepository.getAllIssues() }
        verify(exactly = 1) { fixture.plantPhotoRepository.getAllPhotos() }
    }

    @Test
    fun `widened queue still re-emits at midnight through the injected ticker`() = runTest {
        val fixture = fixture(dayChangeTicker(zone, nowProvider = { now }, maxPollIntervalMs = POLL_MS))
        val horizon = firstDay.plusDays(10)

        fixture.repository.observeQueue(flowOf(horizon)).test {
            val first = awaitItem().tasks
            assertEquals(TodayTaskBucket.Upcoming(firstDay.plusDays(2).toEpochDay()), first[0].bucket)
            assertEquals(TodayTaskBucket.Upcoming(horizon.toEpochDay()), first[1].bucket)

            now = millis(firstDay.plusDays(2))
            advanceTimeBy(POLL_MS)
            runCurrent()

            val second = awaitItem().tasks
            assertEquals(TodayTaskBucket.Today, second[0].bucket)
            assertEquals(TodayTaskBucket.Upcoming(horizon.toEpochDay()), second[1].bucket)
            assertEquals(2, second.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private companion object {
        const val POLL_MS = 100L
    }
}
