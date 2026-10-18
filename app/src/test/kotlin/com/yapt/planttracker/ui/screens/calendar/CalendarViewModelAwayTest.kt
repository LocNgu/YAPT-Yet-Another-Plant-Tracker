package com.yapt.planttracker.ui.screens.calendar

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.yapt.planttracker.data.repository.AwayPeriodRepository
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Away mode (#897, product ADR-0065): Calendar's status flow takes the trips as its fifth `combine()` input. */
@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelAwayTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 7, 20)
    private fun millis(date: LocalDate) = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private val trip = AwayPeriod(id = 1L, firstDay = LocalDate.of(2026, 7, 1), lastDay = LocalDate.of(2026, 7, 15))

    @Test
    fun `a trip crossing the gap marks the status away-spanning and follows later changes`() = runTest {
        val plant = Plant(id = 1L, name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val plantRepo: PlantRepository = mockk { every { getAllPlants() } returns flowOf(listOf(plant)) }
        val careLogRepo: CareLogRepository = mockk {
            every { logCount } returns flowOf(0)
            coEvery { getLastLogOfType(1L, CareType.WATER) } returns
                CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = millis(LocalDate.of(2026, 6, 25)))
            coEvery { getLastLogOfType(1L, CareType.FERTILIZE) } returns null
            coEvery { getCareLogCount(any()) } returns 1
        }
        val dataStore: DataStore<Preferences> = mockk { every { data } returns flowOf(emptyPreferences()) }
        val trips = MutableStateFlow(listOf(trip))
        val awayRepo: AwayPeriodRepository = mockk { every { observeAll() } returns trips }
        val vm = CalendarViewModel(
            mockk<Application>(relaxed = true),
            plantRepo,
            careLogRepo,
            dataStore,
            mockk<QuickLogUseCase>(),
            dayChangeFlow = flowOf(today),
            nowProvider = { millis(today) },
            awayPeriodRepository = awayRepo
        )

        vm.plantsWithStatus.test {
            var status = awaitItem()
            if (status.isEmpty()) status = awaitItem()
            assertTrue(status.single().isWateringGapAwaySpanning)

            trips.value = emptyList()

            assertFalse(awaitItem().single().isWateringGapAwaySpanning)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
