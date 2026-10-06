package com.yapt.planttracker.ui.screens.addcarelog

import app.cash.turbine.test
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

// Edit-only screen (#532, part 3): every test opens a stored log by id. Creation lives in QuickLogUseCase.
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

    private fun waterLog() = CareLog(
        plantId = 1L,
        careType = CareType.WATER,
        loggedAt = now,
        wateringFeedback = WateringFeedback.JUST_RIGHT
    )

    @Before
    fun setup() {
        // Default: no same-day log exists yet; tests override to true to exercise the duplicate-rejection
        // paths (#509).
        coEvery { careLogRepo.hasLogOfTypeOnDay(any(), any(), any(), any()) } returns false
        coEvery { careLogRepo.getWaterLogTimestampsAscending(any()) } returns emptyList()
        coEvery { careLogRepo.getRecentWaterings(any(), limit = any()) } returns emptyList()
        // Default no predecessor, so the dormancy check has nothing to gate against; dormancy tests override.
        coEvery { careLogRepo.getLastWateringBefore(any(), any(), any()) } returns null
        every { plantRepo.getPlantById(any()) } returns flowOf(null)
    }

    // The dormancy check runs on every WATER save, edits included (#699/#761, product ADR-0044).

    @Test
    fun `editing an existing WATER log's date into a dormancy-spanning position suppresses its feedback`() = runTest {
        val octoberTwentyFifth = localDateUtcMillis(2026, 10, 25)
        val marchFirst = localDateUtcMillis(2027, 3, 1)
        val originalNonDormantDate = localDateUtcMillis(2026, 6, 1)
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.WATER,
            loggedAt = originalNonDormantDate,
            wateringFeedback = WateringFeedback.TOO_SOON
        )
        val dormantPlant = plant(wateringIntervalDays = 7).copy(
            wateringConfidence = 3,
            dormancyStartMonth = 11,
            dormancyEndMonth = 2
        )
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        every { plantRepo.getPlantById(1L) } returns flowOf(dormantPlant)
        coEvery { careLogRepo.addLog(any()) } returns 99L
        // The edit's own predecessor, excluding the row being edited itself (id 99L) -- P2's excludeId fix.
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst, 99L) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octoberTwentyFifth)

        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)
        advanceUntilIdle()
        // selectedFeedback is loaded from existingLog (TOO_SOON); the user only moves the date picker.
        vm.loggedAt = marchFirst

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            careLogRepo.addLog(match { it.id == 99L && it.wateringFeedback == null })
        }
    }

    @Test
    fun `re-saving an already-dormant WATER log that carries stale feedback strips it`() = runTest {
        val octoberTwentyFifth = localDateUtcMillis(2026, 10, 25)
        val marchFirst = localDateUtcMillis(2027, 3, 1)
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.WATER,
            loggedAt = marchFirst,
            wateringFeedback = WateringFeedback.TOO_SOON
        )
        val dormantPlant = plant(wateringIntervalDays = 7).copy(
            wateringConfidence = 3,
            dormancyStartMonth = 11,
            dormancyEndMonth = 2
        )
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        every { plantRepo.getPlantById(1L) } returns flowOf(dormantPlant)
        coEvery { careLogRepo.addLog(any()) } returns 99L
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst, 99L) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octoberTwentyFifth)

        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)
        advanceUntilIdle()
        // No changes at all -- a plain re-save, e.g. the user only edited the notes field.

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            careLogRepo.addLog(match { it.id == 99L && it.wateringFeedback == null })
        }
    }

    private fun editVm(
        log: CareLog,
        plant: Plant? = plant(),
        wateringAdjustmentRepository: WateringAdjustmentRepository? = null
    ): AddCareLogViewModel {
        coEvery { careLogRepo.getLogById(99L) } returns log.copy(id = 99L)
        coEvery { careLogRepo.addLog(any()) } returns 99L
        every { plantRepo.getPlantById(1L) } returns flowOf(plant)
        coEvery { plantRepo.updatePlant(any()) } just runs
        return AddCareLogViewModel(
            careLogRepo,
            plantRepo,
            plantId = 1L,
            careLogId = 99L,
            wateringAdjustmentRepository = wateringAdjustmentRepository
        )
    }

    private suspend fun save(vm: AddCareLogViewModel): AddCareLogViewModel.Event {
        var event: AddCareLogViewModel.Event? = null
        vm.events.test {
            vm.saveLog()
            event = awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        return event!!
    }

    @Test
    fun `loads the existing log's fields`() = runTest {
        val vm = editVm(
            CareLog(
                plantId = 1L,
                careType = CareType.FERTILIZE,
                loggedAt = now,
                notes = "Monthly feed",
                amount = "5 ml",
                fertilizerType = FertilizerType.SOLID
            )
        )
        advanceUntilIdle()

        assertTrue(vm.isLoaded)
        assertEquals(CareType.FERTILIZE, vm.careType)
        assertEquals("Monthly feed", vm.notes)
        assertEquals("5 ml", vm.amount)
        assertEquals(FertilizerType.SOLID, vm.selectedFertilizerType)
        assertEquals(now, vm.loggedAt)
    }

    @Test
    fun `a log that no longer exists never loads and cannot be saved`() = runTest {
        coEvery { careLogRepo.getLogById(99L) } returns null
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)

        vm.saveLog()
        advanceUntilIdle()

        assertFalse(vm.isLoaded)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `saving before the log has loaded writes nothing`() = runTest {
        val gate = CompletableDeferred<CareLog?>()
        coEvery { careLogRepo.getLogById(99L) } coAnswers { gate.await() }
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)

        vm.saveLog()
        advanceUntilIdle()

        assertFalse(vm.isLoaded)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }

    // Retired and system-written types must survive an edit unchanged (#532 part 3): the screen shows the
    // type as a read-only header, so nothing can retype a stored log.
    @Test
    fun `saving an edited NOTE, MIST, CUSTOM or CHECK log keeps its type and edits`() = runTest {
        listOf(CareType.NOTE, CareType.MIST, CareType.CUSTOM, CareType.CHECK, CareType.PRUNE).forEach { type ->
            val vm = editVm(
                CareLog(plantId = 1L, careType = type, loggedAt = now, notes = "Original", customReminderId = 7L)
            )
            advanceUntilIdle()
            vm.notes = "Edited"

            assertEquals(AddCareLogViewModel.Event.Saved(null, null), save(vm))

            coVerify {
                careLogRepo.addLog(
                    match { it.id == 99L && it.careType == type && it.notes == "Edited" && it.customReminderId == 7L }
                )
            }
        }
    }

    @Test
    fun `editing a WATER log keeps its feedback, amount and date and records no new adjustment`() = runTest {
        val adjustments: WateringAdjustmentRepository = mockk(relaxed = true)
        val vm = editVm(
            CareLog(
                plantId = 1L,
                careType = CareType.WATER,
                loggedAt = now,
                wateringFeedback = WateringFeedback.TOO_LATE,
                amount = "250 ml"
            ),
            plant = plant(wateringIntervalDays = 7),
            wateringAdjustmentRepository = adjustments
        )
        advanceUntilIdle()

        val event = save(vm) as AddCareLogViewModel.Event.Saved

        assertNull(event.suggestedWateringInterval)
        assertNull(event.suggestedWateringBaseInterval)
        coVerify {
            careLogRepo.addLog(
                match {
                    it.id == 99L && it.wateringFeedback == WateringFeedback.TOO_LATE && it.amount == "250 ml"
                }
            )
        }
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        coVerify(exactly = 0) { adjustments.addAdjustment(any()) }
    }

    @Test
    fun `editing a liquid FERTILIZE log writes only the edited row and never pairs a WATER or touches the plant`() = runTest {
        val vm = editVm(
            CareLog(
                plantId = 1L,
                careType = CareType.FERTILIZE,
                loggedAt = now,
                fertilizerType = FertilizerType.LIQUID
            ),
            plant = plant(useLiquidFertilizer = true).copy(wateringDueDateOverride = now)
        )
        advanceUntilIdle()

        save(vm)

        coVerify(exactly = 1) { careLogRepo.addLog(any()) }
        coVerify(exactly = 0) { careLogRepo.addLog(match { it.careType == CareType.WATER }) }
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `editing a FERTILIZE log keeps the chosen fertilizer type and clears it for other types`() = runTest {
        val fertilize = editVm(
            CareLog(plantId = 1L, careType = CareType.FERTILIZE, loggedAt = now, fertilizerType = FertilizerType.LIQUID)
        )
        advanceUntilIdle()
        fertilize.selectedFertilizerType = FertilizerType.SOLID
        save(fertilize)
        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.FERTILIZE && it.fertilizerType == FertilizerType.SOLID }
            )
        }

        val prune = editVm(CareLog(plantId = 1L, careType = CareType.PRUNE, loggedAt = now))
        advanceUntilIdle()
        save(prune)
        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.PRUNE && it.fertilizerType == FertilizerType.UNSPECIFIED }
            )
        }
    }

    @Test
    fun `editing a REPOT log's date does not re-trigger the reset or clear the plan`() = runTest {
        val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)
        coEvery { plantRepo.clearRepotPlan(any(), any()) } just runs
        val vm = editVm(
            CareLog(plantId = 1L, careType = CareType.REPOT, loggedAt = localDateUtcMillis(2026, 1, 5)),
            plant = plant(wateringIntervalDays = 7).copy(
                wateringConfidence = 3,
                repotPlanSeasonStartAt = localDateUtcMillis(2027, 3, 1),
                repotPlanMadeAt = localDateUtcMillis(2026, 9, 29)
            ),
            wateringAdjustmentRepository = wateringAdjustmentRepo
        )
        advanceUntilIdle()
        vm.loggedAt = localDateUtcMillis(2026, 11, 5)

        save(vm)

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        coVerify(exactly = 0) { plantRepo.clearRepotPlan(any(), any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
    }

    @Test
    fun `saving an edited PHOTO log with a photo updates the plant cover photo`() = runTest {
        val vm = editVm(
            CareLog(plantId = 1L, careType = CareType.PHOTO, loggedAt = now, photoUri = "content://photo.jpg")
        )
        advanceUntilIdle()

        save(vm)

        coVerify { plantRepo.updatePlant(match { it.coverPhotoUri == "content://photo.jpg" }) }
    }

    @Test
    fun `a PHOTO log whose photo was removed cannot be saved`() = runTest {
        val vm = editVm(
            CareLog(plantId = 1L, careType = CareType.PHOTO, loggedAt = now, photoUri = "content://photo.jpg")
        )
        advanceUntilIdle()
        vm.photoUri = null

        vm.saveLog()
        advanceUntilIdle()

        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `a PHOTO log can be saved again once a photo is added`() = runTest {
        val vm = editVm(CareLog(plantId = 1L, careType = CareType.PHOTO, loggedAt = now, photoUri = null))
        advanceUntilIdle()
        vm.saveLog()
        advanceUntilIdle()
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }

        vm.photoUri = "content://new.jpg"
        save(vm)

        coVerify { careLogRepo.addLog(match { it.photoUri == "content://new.jpg" }) }
    }

    @Test
    fun `editing a WATER log with a photo does not touch the cover photo`() = runTest {
        val vm = editVm(
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = now, photoUri = "content://photo.jpg"),
            plant = plant(wateringIntervalDays = 7)
        )
        advanceUntilIdle()

        save(vm)

        coVerify(exactly = 0) { plantRepo.updatePlant(match { it.coverPhotoUri == "content://photo.jpg" }) }
    }

    // Same-day duplicate rejection on edit (#509)

    @Test
    fun `re-saving the same WATER log on the same day excludes its own id and succeeds`() = runTest {
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), 99L) } returns false
        val vm = editVm(
            waterLog()
        )
        advanceUntilIdle()

        assertTrue(save(vm) is AddCareLogViewModel.Event.Saved)

        assertNull(vm.duplicateLogError)
        coVerify { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), 99L) }
    }

    @Test
    fun `moving a WATER log onto a day with another WATER log is rejected`() = runTest {
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), 99L) } returns true
        val vm = editVm(
            waterLog()
        )
        advanceUntilIdle()

        vm.events.test {
            vm.saveLog()
            expectNoEvents()
        }

        assertEquals(R.string.care_log_error_already_watered, vm.duplicateLogError)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `moving a FERTILIZE log onto a day with another FERTILIZE log is rejected`() = runTest {
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.FERTILIZE, any(), 99L) } returns true
        val vm = editVm(CareLog(plantId = 1L, careType = CareType.FERTILIZE, loggedAt = now))
        advanceUntilIdle()

        vm.events.test {
            vm.saveLog()
            expectNoEvents()
        }

        assertEquals(R.string.care_log_error_already_fertilized, vm.duplicateLogError)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `moving a WATER log to a day without another WATER log is accepted and clears the error`() = runTest {
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), 99L) } returnsMany listOf(true, false)
        val vm = editVm(CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = now))
        advanceUntilIdle()
        vm.events.test {
            vm.saveLog()
            expectNoEvents()
        }
        assertEquals(R.string.care_log_error_already_watered, vm.duplicateLogError)

        vm.loggedAt = now - 2L * 24 * 60 * 60 * 1000
        assertTrue(save(vm) is AddCareLogViewModel.Event.Saved)

        assertNull(vm.duplicateLogError)
    }

    @Test
    fun `a PRUNE edit is never duplicate-guarded`() = runTest {
        val vm = editVm(CareLog(plantId = 1L, careType = CareType.PRUNE, loggedAt = now))
        advanceUntilIdle()

        save(vm)

        coVerify(exactly = 0) { careLogRepo.hasLogOfTypeOnDay(any(), any(), any(), any()) }
    }

    @Test
    fun `clearDuplicateLogError resets the error to null`() = runTest {
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), 99L) } returns true
        val vm = editVm(CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = now))
        advanceUntilIdle()
        vm.events.test {
            vm.saveLog()
            expectNoEvents()
        }
        assertEquals(R.string.care_log_error_already_watered, vm.duplicateLogError)

        vm.clearDuplicateLogError()

        assertNull(vm.duplicateLogError)
    }
}

private fun localDateUtcMillis(year: Int, month: Int, day: Int): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month - 1, day, 12, 0, 0)
    return cal.timeInMillis
}
