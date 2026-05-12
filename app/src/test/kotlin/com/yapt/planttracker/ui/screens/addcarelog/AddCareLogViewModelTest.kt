package com.yapt.planttracker.ui.screens.addcarelog

import app.cash.turbine.test
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AddCareLogViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val careLogRepo: CareLogRepository = mockk()
    private val plantRepo: PlantRepository = mockk()

    private val now = System.currentTimeMillis()

    private fun plant(id: Long = 1L, wateringIntervalDays: Int? = 7) = Plant(
        id = id,
        name = "Monstera",
        wateringIntervalDays = wateringIntervalDays,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun waterLog(loggedAt: Long = now) = CareLog(
        id = 0L,
        plantId = 1L,
        careType = CareType.WATER,
        loggedAt = loggedAt,
        wateringFeedback = WateringFeedback.JUST_RIGHT
    )

    @Test
    fun `save WATER log with JUST_RIGHT feedback emits Saved with null interval`() = runTest {
        coEvery { careLogRepo.addLog(any()) } returns 1L
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.JUST_RIGHT

        vm.events.test {
            vm.saveLog()
            val event = awaitItem()
            assertTrue(event is AddCareLogViewModel.Event.Saved)
            assertNull((event as AddCareLogViewModel.Event.Saved).suggestedWateringInterval)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save WATER log with TOO_SOON feedback emits Saved with non-null suggested interval`() = runTest {
        val threeDaysAgo = now - 3L * 24 * 60 * 60 * 1000
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = now),
            waterLog(loggedAt = threeDaysAgo)
        )
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.TOO_SOON

        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            assertTrue(event.suggestedWateringInterval != null)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save FERTILIZE log emits Saved with null interval regardless of feedback`() = runTest {
        coEvery { careLogRepo.addLog(any()) } returns 1L
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.FERTILIZE
        vm.selectedFeedback = WateringFeedback.TOO_SOON

        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            assertNull(event.suggestedWateringInterval)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `edit mode loads existing log fields and isEditMode is true`() = runTest {
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.FERTILIZE,
            loggedAt = now,
            notes = "Monthly feed",
            wateringFeedback = null
        )
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        coEvery { careLogRepo.addLog(any()) } returns 99L
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)

        assertTrue(vm.isEditMode)
        assertEquals(CareType.FERTILIZE, vm.selectedCareType)
        assertEquals("Monthly feed", vm.notes)
    }

    @Test
    fun `edit mode save emits Saved with null interval skipping suggest`() = runTest {
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.WATER,
            loggedAt = now,
            wateringFeedback = WateringFeedback.TOO_SOON
        )
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        coEvery { careLogRepo.addLog(any()) } returns 99L
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)

        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            assertNull(event.suggestedWateringInterval)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
