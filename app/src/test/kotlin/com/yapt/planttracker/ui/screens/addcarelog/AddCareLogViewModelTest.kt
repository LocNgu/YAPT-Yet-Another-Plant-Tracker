package com.yapt.planttracker.ui.screens.addcarelog

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import app.cash.turbine.test
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.SeasonalWatering
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.Calendar
import java.util.TimeZone

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

    @Before
    fun setup() {
        // Default: no same-day log of any type exists yet; individual tests override to true to
        // exercise the duplicate-rejection paths (#509).
        coEvery { careLogRepo.hasLogOfTypeOnDay(any(), any(), any(), any()) } returns false
        // #571: below the 3-gap bootstrap threshold by default, so existing adaptive-model tests keep
        // exercising the plain per-observation path — tests exercising the bootstrap itself override this.
        coEvery { careLogRepo.getWaterLogTimestampsAscending(any()) } returns emptyList()
        // Now unconditional (no more ADAPTIVE_WATERING flag gate) — every WATER-log save with a
        // configured interval and 2+ prior waterings reaches this; individual tests override with a
        // real correction-streak window where that matters.
        coEvery { careLogRepo.getRecentWaterings(any(), limit = any()) } returns emptyList()
    }

    @Test
    fun `save WATER log with JUST_RIGHT feedback emits Saved with null interval when gap matches stored`() = runTest {
        val sevenDaysAgo = now - 7L * 24 * 60 * 60 * 1000
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = now),
            waterLog(loggedAt = sevenDaysAgo)
        )
        coEvery { plantRepo.updatePlant(any()) } just runs
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
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.JUST_RIGHT

        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            // Confidence-weighted adaptive model (#655): base=14, target=observed(7)*JUST_RIGHT(1.00)=7,
            // first-ever observation uses the confidence-0 gain (0.60) -> 14 + 0.60*(7-14) = 9.8 -> 10.
            assertEquals(10, event.suggestedWateringInterval)
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
        coEvery { plantRepo.updatePlant(any()) } just runs
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
        // #586: the paired watering carries no reason — the user fertilized and the watering came
        // along with it (ADR-0008), so they were never asked why they watered.
        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.WATER && it.wateringFeedback == null })
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

    // #571: a new REPOT log resets wateringConfidence and starts the freeze window.
    @Test
    fun `save new REPOT log resets confidence and starts the freeze window`() = runTest {
        val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7).copy(wateringConfidence = 3))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(
            careLogRepo,
            plantRepo,
            plantId = 1L,
            wateringAdjustmentRepository = wateringAdjustmentRepo
        )
        vm.selectedCareType = CareType.REPOT

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) {
            plantRepo.updatePlant(
                match { it.wateringConfidence == 0 && it.wateringResetAt != null && it.wateringFreezeUntil != null }
            )
        }
        coVerify { wateringAdjustmentRepo.addAdjustment(any()) }
    }

    // #571 AC3 regression: editing a past REPOT log's date/type must never re-trigger the reset —
    // it's written once at original log-creation time, not derived from querying REPOT history live.
    @Test
    fun `editing an existing REPOT log's date does not re-trigger the reset`() = runTest {
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.REPOT,
            loggedAt = now - 10L * 24 * 60 * 60 * 1000
        )
        val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        coEvery { careLogRepo.addLog(any()) } returns 99L
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7).copy(wateringConfidence = 3))
        val vm = AddCareLogViewModel(
            careLogRepo,
            plantRepo,
            plantId = 1L,
            careLogId = 99L,
            wateringAdjustmentRepository = wateringAdjustmentRepo
        )
        advanceUntilIdle()
        vm.loggedAt = now

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
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
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.JUST_RIGHT
        vm.photoUri = "content://photo.jpg"

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // A WATER log may still trigger a confidence-only updatePlant() (unconditional adaptive
        // model, #655) — this only asserts that call never carries the WATER log's photo through
        // to coverPhotoUri, which is PHOTO-log-only behavior.
        coVerify(exactly = 0) { plantRepo.updatePlant(match { it.coverPhotoUri == "content://photo.jpg" }) }
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

    @Test
    fun `edit mode save preserves customReminderId when editing a CUSTOM log`() = runTest {
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.CUSTOM,
            loggedAt = now,
            notes = "Original notes",
            customReminderId = 42L
        )
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        coEvery { careLogRepo.addLog(any()) } returns 99L
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)
        advanceUntilIdle()
        vm.notes = "Edited notes"

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            careLogRepo.addLog(
                match { it.customReminderId == 42L && it.notes == "Edited notes" }
            )
        }
    }

    // Same-day duplicate rejection (#509)

    @Test
    fun `save WATER log already logged today shows inline error and does not save`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7))
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), null) } returns true
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER

        vm.events.test {
            vm.saveLog()
            expectNoEvents()
        }

        assertEquals(R.string.care_log_error_already_watered, vm.duplicateLogError)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `save FERTILIZE log already logged today shows inline error and does not save`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.FERTILIZE, any(), null) } returns true
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.FERTILIZE

        vm.events.test {
            vm.saveLog()
            expectNoEvents()
        }

        assertEquals(R.string.care_log_error_already_fertilized, vm.duplicateLogError)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `save WATER log on a different day than an existing same-day log is accepted`() = runTest {
        val sevenDaysAgo = now - 7L * 24 * 60 * 60 * 1000
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = now),
            waterLog(loggedAt = sevenDaysAgo)
        )
        coEvery { plantRepo.updatePlant(any()) } just runs
        // hasLogOfTypeOnDay defaults to false for the queried day in setup() — simulates a day
        // with no existing WATER log even though other days have one.
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER

        vm.events.test {
            vm.saveLog()
            val event = awaitItem()
            assertTrue(event is AddCareLogViewModel.Event.Saved)
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(vm.duplicateLogError)
    }

    @Test
    fun `edit mode re-saving the same WATER log on the same day excludes its own id and succeeds`() = runTest {
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.WATER,
            loggedAt = now,
            wateringFeedback = WateringFeedback.JUST_RIGHT
        )
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        coEvery { careLogRepo.addLog(any()) } returns 99L
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), 99L) } returns false
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)
        advanceUntilIdle()

        vm.events.test {
            vm.saveLog()
            val event = awaitItem()
            assertTrue(event is AddCareLogViewModel.Event.Saved)
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(vm.duplicateLogError)
        coVerify { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), 99L) }
    }

    @Test
    fun `edit mode moving a WATER log onto a day with another WATER log is rejected`() = runTest {
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.WATER,
            loggedAt = now,
            wateringFeedback = WateringFeedback.JUST_RIGHT
        )
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), 99L) } returns true
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)
        advanceUntilIdle()

        vm.events.test {
            vm.saveLog()
            expectNoEvents()
        }

        assertEquals(R.string.care_log_error_already_watered, vm.duplicateLogError)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `FERTILIZE with LIQUID type already watered today still saves FERTILIZE but suppresses paired WATER`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.FERTILIZE, any(), null) } returns false
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), null) } returns true
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.FERTILIZE
        vm.selectedFertilizerType = FertilizerType.LIQUID

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { careLogRepo.addLog(any()) }
        coVerify(exactly = 0) { careLogRepo.addLog(match { it.careType == CareType.WATER }) }
    }

    @Test
    fun `clearDuplicateLogError resets the error to null`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7))
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), null) } returns true
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER
        vm.events.test {
            vm.saveLog()
            expectNoEvents()
        }
        assertEquals(R.string.care_log_error_already_watered, vm.duplicateLogError)

        vm.clearDuplicateLogError()

        assertNull(vm.duplicateLogError)
    }

    // Seasonal de-seasonalization of the observed gap (#569, product ADR-0026, #578 follow-up)

    @Test
    fun `save WATER log de-seasonalizes the observed gap for a non-pinned plant when SEASONAL_WATERING is on`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val peakDay = localDateUtcMillis(2023, 1, 5)
        val twentyDaysBeforePeak = peakDay - 20L * 24 * 60 * 60 * 1000
        val seasonalDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(
                preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
            )
        }
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 10))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = peakDay),
            waterLog(loggedAt = twentyDaysBeforePeak)
        )
        coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, dataStore = seasonalDataStore)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.JUST_RIGHT
        vm.loggedAt = peakDay

        var suggestedInterval: Int? = null
        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            suggestedInterval = event.suggestedWateringInterval
            cancelAndIgnoreRemainingEvents()
        }

        // Peak day (Jan 5, northern): season(peakDay) = 1 + 0.35 = 1.35, so the observed 20-day gap
        // de-seasonalizes to round(20 / 1.35) = 15 before feeding the adaptive model.
        val deseasonalizedObserved = SeasonalWatering.deseasonalizeToDays(
            20,
            LocalDate.of(2023, 1, 5),
            SeasonalAmplitude.STANDARD.value,
            Hemisphere.NORTHERN
        )
        val expected = CareSchedule.computeAdaptiveInterval(
            feedback = WateringFeedback.JUST_RIGHT,
            observedIntervalDays = deseasonalizedObserved,
            currentBaseIntervalDays = 10,
            currentConfidence = null,
            recentFeedback = emptyList()
        )
        assertEquals(expected.intervalDays.takeIf { it != 10 }, suggestedInterval)
    }

    @Test
    fun `save WATER log skips de-seasonalization for a pinned plant even when SEASONAL_WATERING is on`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val peakDay = localDateUtcMillis(2023, 1, 5)
        val twentyDaysBeforePeak = peakDay - 20L * 24 * 60 * 60 * 1000
        val seasonalDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(
                preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
            )
        }
        val pinnedPlant = plant(wateringIntervalDays = 10).copy(pinIntervalToBase = true)
        every { plantRepo.getPlantById(1L) } returns flowOf(pinnedPlant)
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = peakDay),
            waterLog(loggedAt = twentyDaysBeforePeak)
        )
        coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, dataStore = seasonalDataStore)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.JUST_RIGHT
        vm.loggedAt = peakDay

        var suggestedInterval: Int? = null
        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            suggestedInterval = event.suggestedWateringInterval
            cancelAndIgnoreRemainingEvents()
        }

        // Pinned: the raw 20-day gap is used unchanged, not the season(peakDay)-corrected 15.
        val expected = CareSchedule.computeAdaptiveInterval(
            feedback = WateringFeedback.JUST_RIGHT,
            observedIntervalDays = 20,
            currentBaseIntervalDays = 10,
            currentConfidence = null,
            recentFeedback = emptyList()
        )
        assertEquals(expected.intervalDays.takeIf { it != 10 }, suggestedInterval)
    }

    // #620 round 2: computeSuggestedInterval() must gate on the effective-space value, not the raw
    // base-space suggestion, or a pure unit-mismatch artifact reaches Event.Saved ungated.
    @Test
    fun `save WATER log suppresses a suggestion whose effective-space value equals current under a non-1_0 seasonal multiplier`() =
        runTest {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val peakDay = localDateUtcMillis(2023, 1, 5)
            val fiveDaysBeforePeak = peakDay - 5L * 24 * 60 * 60 * 1000
            val seasonalDataStore: DataStore<Preferences> = mockk {
                every { data } returns flowOf(
                    preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
                )
            }
            // current = 7 (already seasonally-adjusted, e.g. from a prior effective-space edit); the
            // observed 5-day gap de-seasonalizes to round(5 / 1.35) = 4 before the adaptive model sees
            // it, and the model (confidence-0, JUST_RIGHT, target = 4) lands the raw base-space
            // suggestion back at round(7 + 0.60*(4-7)) = 5. But at the peak day, season() = 1.35, so
            // round(5 * 1.35) = 7 == current: the entire "5 vs 7" jump is a unit-mismatch artifact, not a
            // real model change, and must be suppressed exactly like the ADR-0006 dialog gate is.
            every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7))
            coEvery { careLogRepo.addLog(any()) } returns 1L
            coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
                waterLog(loggedAt = peakDay),
                waterLog(loggedAt = fiveDaysBeforePeak)
            )
            coEvery { plantRepo.updatePlant(any()) } just runs
            val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, dataStore = seasonalDataStore)
            vm.selectedCareType = CareType.WATER
            vm.selectedFeedback = WateringFeedback.JUST_RIGHT
            vm.loggedAt = peakDay

            vm.events.test {
                vm.saveLog()
                val event = awaitItem() as AddCareLogViewModel.Event.Saved
                assertNull(event.suggestedWateringInterval)
                cancelAndIgnoreRemainingEvents()
            }
        }

    // Sanity check for the same fix: a raw suggestion whose effective-space value genuinely differs
    // from current must still surface, seasonal multiplier or not.
    @Test
    fun `save WATER log still surfaces a suggestion whose effective-space value genuinely differs from current`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val peakDay = localDateUtcMillis(2023, 1, 5)
        val oneDayBeforePeak = peakDay - 1L * 24 * 60 * 60 * 1000
        val seasonalDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(
                preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true)
            )
        }
        // The observed 1-day gap de-seasonalizes to round(1 / 1.35) = 1; the model's confidence-0 gain
        // (0.60) pulls the base from 7 toward target=1 down to 3.4, clamped at the ±40% floor
        // (7*0.6=4.2) and rounded to 4 — the raw base-space suggestion. round(4 * 1.35) = 5 != 7 ==
        // current — a genuine effective-space change, so the suggestion must still surface.
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = peakDay),
            waterLog(loggedAt = oneDayBeforePeak)
        )
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, dataStore = seasonalDataStore)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.JUST_RIGHT
        vm.loggedAt = peakDay

        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            assertEquals(4, event.suggestedWateringInterval)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

private fun localDateUtcMillis(year: Int, month: Int, day: Int): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month - 1, day, 12, 0, 0)
    return cal.timeInMillis
}
