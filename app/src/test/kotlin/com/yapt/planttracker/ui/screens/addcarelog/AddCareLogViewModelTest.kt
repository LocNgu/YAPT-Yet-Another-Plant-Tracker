package com.yapt.planttracker.ui.screens.addcarelog

import app.cash.turbine.test
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AddCareLogViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val careLogRepo: CareLogRepository = mockk()
    private val plantRepo: PlantRepository = mockk()

    private val now = System.currentTimeMillis()

    private fun plant(id: Long = 1L, wateringIntervalDays: Int? = 7, useLiquidFertilizer: Boolean = false) = Plant(
        id = id,
        name = "Monstera",
        wateringIntervalDays = wateringIntervalDays,
        createdAt = 0L,
        updatedAt = 0L,
        useLiquidFertilizer = useLiquidFertilizer
    )

    private fun waterLog(loggedAt: Long = now) = CareLog(
        id = 0L,
        plantId = 1L,
        careType = CareType.WATER,
        loggedAt = loggedAt,
        wateringFeedback = WateringFeedback.JUST_RIGHT
    )

    @Test
    fun `save WATER log with JUST_RIGHT feedback emits Saved with null interval when gap matches stored`() = runTest {
        val sevenDaysAgo = now - 7L * 24 * 60 * 60 * 1000
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = now),
            waterLog(loggedAt = sevenDaysAgo)
        )
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
    fun `save WATER log with JUST_RIGHT feedback emits Saved with suggested interval when gap differs from stored`() = runTest {
        val sevenDaysAgo = now - 7L * 24 * 60 * 60 * 1000
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 14))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = now),
            waterLog(loggedAt = sevenDaysAgo)
        )
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.JUST_RIGHT

        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            assertEquals(7, event.suggestedWateringInterval)
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
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
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

        advanceUntilIdle()
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
        advanceUntilIdle()

        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            assertNull(event.suggestedWateringInterval)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `FERTILIZE with LIQUID type auto-creates paired WATER log`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(useLiquidFertilizer = true))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.FERTILIZE
        vm.selectedFertilizerType = FertilizerType.LIQUID

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 2) { careLogRepo.addLog(any()) }
        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.WATER && it.wateringFeedback == WateringFeedback.JUST_RIGHT }
            )
        }
    }

    @Test
    fun `FERTILIZE with SOLID type does not create paired WATER log`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        coEvery { careLogRepo.addLog(any()) } returns 1L
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.FERTILIZE
        vm.selectedFertilizerType = FertilizerType.SOLID

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `new mode init defaults selectedFertilizerType to LIQUID when plant useLiquidFertilizer is true`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(useLiquidFertilizer = true))
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)

        assertEquals(FertilizerType.LIQUID, vm.selectedFertilizerType)
    }

    @Test
    fun `new mode init defaults selectedFertilizerType to UNSPECIFIED when plant useLiquidFertilizer is false`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(useLiquidFertilizer = false))
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)

        assertEquals(FertilizerType.UNSPECIFIED, vm.selectedFertilizerType)
    }

    @Test
    fun `save new WATER log clears wateringDueDateOverride when it is set`() = runTest {
        val sevenDaysAgo = now - 7L * 24 * 60 * 60 * 1000
        val plantWithOverride = plant(wateringIntervalDays = 7)
            .copy(wateringDueDateOverride = now + 3L * 24 * 60 * 60 * 1000)
        every { plantRepo.getPlantById(1L) } returns flowOf(plantWithOverride)
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = now),
            waterLog(loggedAt = sevenDaysAgo)
        )
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.wateringDueDateOverride == null }) }
    }

    @Test
    fun `save PHOTO log with photoUri updates plant coverPhotoUri`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.PHOTO
        vm.photoUri = "content://photo.jpg"

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.coverPhotoUri == "content://photo.jpg" }) }
    }

    @Test
    fun `save WATER log with photo does not update coverPhotoUri`() = runTest {
        val sevenDaysAgo = now - 7L * 24 * 60 * 60 * 1000
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = now),
            waterLog(loggedAt = sevenDaysAgo)
        )
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.JUST_RIGHT
        vm.photoUri = "content://photo.jpg"

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `edit mode save PHOTO log with photoUri updates plant coverPhotoUri`() = runTest {
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.PHOTO,
            loggedAt = now,
            photoUri = "content://photo.jpg"
        )
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        coEvery { careLogRepo.addLog(any()) } returns 99L
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)
        advanceUntilIdle()

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.coverPhotoUri == "content://photo.jpg" }) }
    }
}
