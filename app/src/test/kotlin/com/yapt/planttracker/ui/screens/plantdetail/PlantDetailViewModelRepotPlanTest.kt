package com.yapt.planttracker.ui.screens.plantdetail

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.RepotPlanSeason
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Set / edit / clear for the one-off planned repot (#809, product ADR-0057) from Plant Detail's Repot
 * tab. The writes are column-specific (`PlantRepository.setRepotPlan`/`clearRepotPlan`) — never a
 * full-row `updatePlant()` — and a plan needs no repotting interval.
 */
class PlantDetailViewModelRepotPlanTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val dataStore: DataStore<Preferences> = mockk {
        every { data } returns flowOf(emptyPreferences())
    }
    private val quickLogUseCase: QuickLogUseCase = mockk()
    private val customReminderRepo: CustomReminderRepository = mockk()
    private val plantIssueRepo: PlantIssueRepository = mockk()
    private val database: PlantDatabase = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk {
        every { getRecentForPlant(any(), any()) } returns flowOf(emptyList())
    }

    private val springStart = 1_804_032_000_000L
    private val summerStart = 1_812_000_000_000L
    private val spring = RepotPlanSeason(FertilizingSeason.SPRING, 2027, springStart)
    private val summer = RepotPlanSeason(FertilizingSeason.SUMMER, 2027, summerStart)

    private fun makeVm(
        plant: Plant = Plant(id = 1L, name = "Monstera", createdAt = 0L, updatedAt = 0L),
        logs: List<CareLog> = emptyList()
    ): PlantDetailViewModel {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(1L) } returns flowOf(logs)
        every { careLogRepo.getPhotoLogsForPlant(1L) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(1L) } returns flowOf(emptyList())
        every { customReminderRepo.getRemindersForPlant(1L) } returns flowOf(emptyList())
        every { plantIssueRepo.getActiveIssuesForPlant(1L) } returns flowOf(emptyList())
        return PlantDetailViewModel(
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            1L,
            dataStore,
            quickLogUseCase,
            customReminderRepo,
            plantIssueRepo,
            database,
            wateringAdjustmentRepo
        )
    }

    @Test
    fun `setRepotPlan writes the season start and the made-at instant through the column-specific update`() =
        runTest {
            coEvery { plantRepo.setRepotPlan(any(), any(), any(), any()) } just runs
            val vm = makeVm()

            vm.setRepotPlan(spring, now = 1_790_000_000_000L)

            coVerify(exactly = 1) {
                plantRepo.setRepotPlan(1L, springStart, 1_790_000_000_000L, 1_790_000_000_000L)
            }
            coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        }

    @Test
    fun `editing a plan replaces it and restarts the made-at instant`() = runTest {
        coEvery { plantRepo.setRepotPlan(any(), any(), any(), any()) } just runs
        val vm = makeVm()

        vm.setRepotPlan(spring, now = 1_790_000_000_000L)
        vm.setRepotPlan(summer, now = 1_791_000_000_000L)

        coVerifyOrder {
            plantRepo.setRepotPlan(1L, springStart, 1_790_000_000_000L, 1_790_000_000_000L)
            plantRepo.setRepotPlan(1L, summerStart, 1_791_000_000_000L, 1_791_000_000_000L)
        }
    }

    @Test
    fun `clearRepotPlan clears both plan columns through the column-specific update`() = runTest {
        coEvery { plantRepo.clearRepotPlan(any(), any()) } just runs
        val vm = makeVm()

        vm.clearRepotPlan(now = 1_792_000_000_000L)

        coVerify(exactly = 1) { plantRepo.clearRepotPlan(1L, 1_792_000_000_000L) }
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `plan writes serialize behind the interval-edit mutex so they run in tap order`() = runTest {
        val finished = mutableListOf<Long>()
        coEvery { plantRepo.setRepotPlan(any(), any(), any(), any()) } coAnswers {
            if (arg<Long>(1) == springStart) delay(50)
            finished += arg<Long>(1)
        }
        val vm = makeVm()

        vm.setRepotPlan(spring, now = 1L)
        vm.setRepotPlan(summer, now = 2L)
        advanceUntilIdle()

        assertEquals(listOf(springStart, summerStart), finished)
    }

    @Test
    fun `careStatus takes lastRepottedAt from the newest REPOT log`() = runTest {
        val day = 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        val plant = Plant(
            id = 1L,
            name = "Monstera",
            repottingIntervalDays = 30,
            createdAt = now - 400 * day,
            updatedAt = 0L
        )
        val logs = listOf(
            CareLog(id = 2L, plantId = 1L, careType = CareType.REPOT, loggedAt = now - day),
            CareLog(id = 1L, plantId = 1L, careType = CareType.REPOT, loggedAt = now - 300 * day)
        )
        val vm = makeVm(plant, logs)

        vm.careStatus.test {
            val status = awaitItem() ?: awaitItem()
            assertNotNull(status)
            assertEquals(now - day, status?.lastRepottedAt)
            assertFalse(status?.isRepottingOverdue == true)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `careStatus reports an overdue repot when the newest REPOT log is older than the interval`() = runTest {
        val day = 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        val plant = Plant(
            id = 1L,
            name = "Monstera",
            repottingIntervalDays = 30,
            createdAt = now - 400 * day,
            updatedAt = 0L
        )
        val logs = listOf(CareLog(id = 1L, plantId = 1L, careType = CareType.REPOT, loggedAt = now - 100 * day))
        val vm = makeVm(plant, logs)

        vm.careStatus.test {
            val status = awaitItem() ?: awaitItem()
            assertTrue(status?.isRepottingOverdue == true)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
