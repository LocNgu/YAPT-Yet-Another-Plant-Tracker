package com.yapt.planttracker.ui.screens.plantdetail

import android.app.Application
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.GalleryPhoto
import com.yapt.planttracker.domain.model.GalleryPhotoSource
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.spyk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * #808 (technical ADR-0036): the Plant Detail ViewModel's own plant-row writes share
 * [PlantDetailViewModel.plantEditMutex] and read the plant fresh inside it (or writes one column
 * through a column-specific UPDATE), so none of them can undo a concurrent write to a column it doesn't
 * change. Same technique as [PlantDetailScheduleSettingsActionsFreshReadTest]: every repository write
 * is mocked with a real [delay], so two back-to-back calls genuinely overlap under
 * [MainDispatcherRule]'s `UnconfinedTestDispatcher` and the cached `plant` StateFlow is stale for the
 * length of the delay — the race window the lock has to close. Full-row writes replace the whole stored
 * row after the delay (the lost-update shape); column writes patch only their own column.
 */
class PlantDetailPlantEditLockTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val customReminderRepo: CustomReminderRepository = mockk()
    private val plantIssueRepo: PlantIssueRepository = mockk()
    private val database: PlantDatabase = mockk()
    private val adjustments = mutableListOf<WateringAdjustment>()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk {
        every { getRecentForPlant(any(), any()) } returns flowOf(emptyList())
        coEvery { addAdjustment(any()) } coAnswers {
            adjustments += firstArg<WateringAdjustment>()
            1L
        }
    }

    private lateinit var stored: MutableStateFlow<Plant?>
    private lateinit var quickLogUseCase: QuickLogUseCase

    private fun plant() = Plant(
        id = 1L,
        name = "Monstera",
        createdAt = 0L,
        updatedAt = 0L,
        wateringIntervalDays = 7,
        wateringBaseIntervalDays = 7.0
    )

    /**
     * [echoLagMs] > 0 makes the VM's cached `plant` StateFlow (the first `getPlantById` call) see every
     * stored change that many ms late, as Room's invalidation echo does, while the later fresh reads
     * inside the lock see [stored] directly.
     */
    private fun makeVm(
        initial: Plant,
        askBeforeChangingIntervals: Boolean = true,
        echoLagMs: Long = 0L
    ): PlantDetailViewModel {
        stored = MutableStateFlow(initial)
        val dataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(
                if (askBeforeChangingIntervals) {
                    emptyPreferences()
                } else {
                    preferencesOf(SettingsKeys.ASK_BEFORE_CHANGING_INTERVALS to false)
                }
            )
        }
        quickLogUseCase = spyk(
            QuickLogUseCase(
                mockk<Application>(relaxed = true),
                plantRepo,
                careLogRepo,
                plantPhotoRepo,
                dataStore,
                database,
                wateringAdjustmentRepo
            )
        )
        every { careLogRepo.getLogsForPlant(1L) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(1L) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(1L) } returns flowOf(emptyList())
        every { customReminderRepo.getRemindersForPlant(1L) } returns flowOf(emptyList())
        every { plantIssueRepo.getActiveIssuesForPlant(1L) } returns flowOf(emptyList())
        stubPlantReads(echoLagMs)
        stubPlantWrites()
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

    private fun stubPlantReads(echoLagMs: Long) {
        var calls = 0
        every { plantRepo.getPlantById(1L) } answers {
            val lagged = calls++ == 0 && echoLagMs > 0
            if (lagged) {
                stored.map {
                    delay(echoLagMs)
                    it
                }
            } else {
                stored
            }
        }
    }

    private fun stubPlantWrites() {
        coEvery { plantRepo.updatePlant(any()) } coAnswers {
            delay(WRITE_MS)
            stored.value = firstArg()
        }
        coEvery { plantRepo.updateWateringDueDateOverride(any(), any(), any()) } coAnswers {
            val override = secondArg<Long?>()
            val at = thirdArg<Long>()
            delay(WRITE_MS)
            stored.value = stored.value?.copy(wateringDueDateOverride = override, updatedAt = at)
        }
        coEvery { plantRepo.updateCoverPhotoUri(any(), any(), any()) } coAnswers {
            val uri = secondArg<String?>()
            val at = thirdArg<Long>()
            delay(WRITE_MS)
            stored.value = stored.value?.copy(coverPhotoUri = uri, updatedAt = at)
        }
    }

    private fun uri(value: String): Uri {
        val uri: Uri = mockk()
        every { uri.toString() } returns value
        return uri
    }

    // ---- Interval group: apply / silent apply / undo / dismiss ----

    @Test
    fun `applySuggestedInterval right after a pin write keeps the pin`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = makeVm(plant().copy(pinIntervalToBase = false), echoLagMs = ECHO_LAG_MS)

            vm.setPinIntervalToBase(true)
            vm.applySuggestedInterval(9)
            advanceUntilIdle()

            assertEquals(true, stored.value?.pinIntervalToBase)
            assertEquals(9, stored.value?.wateringIntervalDays)
        }

    @Test
    fun `dismissSuggestedInterval right after an interval write keeps the new interval and raises confidence`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = makeVm(plant().copy(wateringConfidence = 0), echoLagMs = ECHO_LAG_MS)

            vm.setWateringInterval(12)
            vm.dismissSuggestedInterval()
            advanceUntilIdle()

            assertEquals(12, stored.value?.wateringIntervalDays)
            assertEquals(CareSchedule.confidenceAfterDismissal(0), stored.value?.wateringConfidence)
            assertTrue(adjustments.any { it.trigger == WateringAdjustmentTrigger.DIALOG_DISMISSAL })
        }

    @Test
    fun `undoSilentIntervalApply right after a pin write keeps the pin and still restores the captured interval`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = makeVm(
                plant().copy(wateringIntervalDays = 9, wateringBaseIntervalDays = 9.0, pinIntervalToBase = false),
                echoLagMs = ECHO_LAG_MS
            )

            vm.setPinIntervalToBase(true)
            vm.undoSilentIntervalApply(7, 7.0)
            advanceUntilIdle()

            assertEquals(true, stored.value?.pinIntervalToBase)
            assertEquals(7, stored.value?.wateringIntervalDays)
            assertEquals(7.0, stored.value?.wateringBaseIntervalDays)
            assertTrue(adjustments.any { it.trigger == WateringAdjustmentTrigger.SILENT_APPLY_UNDONE })
        }

    @Test
    fun `silent apply right after a quickWater that cleared the override does not restore it`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = makeVm(
                plant().copy(wateringConfidence = 2, wateringDueDateOverride = OVERRIDE_AT),
                askBeforeChangingIntervals = false,
                echoLagMs = ECHO_LAG_MS
            )
            advanceUntilIdle()
            // quickWaterWithReason's own write: it clears the override and raises confidence. The cached
            // plant StateFlow only sees it ECHO_LAG_MS later, as with Room's invalidation echo.
            coEvery { quickLogUseCase.quickWaterWithReason(any(), any(), any()) } coAnswers {
                delay(WRITE_MS)
                stored.value = firstArg<Plant>().copy(wateringDueDateOverride = null, wateringConfidence = 3)
                QuickLogUseCase.QuickLogOutcome(
                    message = "Watered",
                    logged = true,
                    suggestion = QuickWaterSuggestion(1L, "Monstera", 9, 9, 9.0, 7)
                )
            }

            vm.quickWater(reason = null)
            advanceUntilIdle()

            assertNull(stored.value?.wateringDueDateOverride)
            assertEquals(3, stored.value?.wateringConfidence)
            assertEquals(9, stored.value?.wateringIntervalDays)
        }

    // ---- Reschedule revert ----

    @Test
    fun `revertReschedule right after an interval write keeps the interval and reports the prior override`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = makeVm(plant().copy(wateringDueDateOverride = OVERRIDE_AT))
            val events = mutableListOf<PlantDetailViewModel.Event>()
            backgroundScope.launch { vm.events.collect { events += it } }

            vm.setWateringInterval(12)
            vm.revertReschedule()
            advanceUntilIdle()

            assertEquals(12, stored.value?.wateringIntervalDays)
            assertNull(stored.value?.wateringDueDateOverride)
            assertEquals(listOf(PlantDetailViewModel.Event.RescheduleReverted(OVERRIDE_AT)), events)
        }

    @Test
    fun `an interval write right after revertReschedule does not put the override back`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = makeVm(plant().copy(wateringDueDateOverride = OVERRIDE_AT))

            vm.revertReschedule()
            vm.setWateringInterval(12)
            advanceUntilIdle()

            assertEquals(12, stored.value?.wateringIntervalDays)
            assertNull(stored.value?.wateringDueDateOverride)
        }

    @Test
    fun `undoRevertReschedule right after a pin write keeps the pin`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = makeVm(plant().copy(pinIntervalToBase = false, wateringDueDateOverride = null))

            vm.setPinIntervalToBase(true)
            vm.undoRevertReschedule(OVERRIDE_AT)
            advanceUntilIdle()

            assertEquals(true, stored.value?.pinIntervalToBase)
            assertEquals(OVERRIDE_AT, stored.value?.wateringDueDateOverride)
        }

    @Test
    fun `an interval write right after a reschedule keeps the new override`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = makeVm(plant())

            vm.confirmRescheduleCustomDate(OVERRIDE_AT)
            vm.setWateringInterval(12)
            advanceUntilIdle()

            assertEquals(12, stored.value?.wateringIntervalDays)
            assertEquals(OVERRIDE_AT, stored.value?.wateringDueDateOverride)
        }

    // ---- Cover photo ----

    @Test
    fun `savePhotoLog right after an interval write keeps the interval`() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery { careLogRepo.addLog(any()) } returns 1L
            val vm = makeVm(plant())

            vm.setWateringInterval(12)
            vm.savePhotoLog(uri("content://new.jpg"), 1_000L)
            advanceUntilIdle()

            assertEquals(12, stored.value?.wateringIntervalDays)
            assertEquals("content://new.jpg", stored.value?.coverPhotoUri)
        }

    @Test
    fun `an interval write right after savePhotoLog keeps the new cover`() =
        runTest(mainDispatcherRule.testDispatcher) {
            coEvery { careLogRepo.addLog(any()) } returns 1L
            val vm = makeVm(plant())

            vm.savePhotoLog(uri("content://new.jpg"), 1_000L)
            vm.setWateringInterval(12)
            advanceUntilIdle()

            assertEquals(12, stored.value?.wateringIntervalDays)
            assertEquals("content://new.jpg", stored.value?.coverPhotoUri)
        }

    @Test
    fun `an interval write right after saveReminderPhoto keeps the new cover`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = makeVm(plant())
            // Stands in for the real transactional use case, whose cover write is column-specific.
            coEvery { quickLogUseCase.saveReminderPhoto(1L, "content://reminder.jpg") } coAnswers {
                plantRepo.updateCoverPhotoUri(1L, "content://reminder.jpg", 5L)
                stored.value
            }

            vm.saveReminderPhoto(uri("content://reminder.jpg"))
            vm.setWateringInterval(12)
            advanceUntilIdle()

            assertEquals(12, stored.value?.wateringIntervalDays)
            assertEquals("content://reminder.jpg", stored.value?.coverPhotoUri)
        }

    @Test
    fun `deletePhoto of the cover right after an interval write keeps the interval and clears the cover`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val coverPhoto = PlantPhoto(id = 1L, plantId = 1L, uri = "content://cover.jpg", capturedAt = 1L)
            coEvery { plantPhotoRepo.deletePhoto(coverPhoto) } just runs
            coEvery { plantPhotoRepo.getPhotosForPlantOnce(1L) } returns emptyList()
            val vm = makeVm(plant().copy(coverPhotoUri = "content://cover.jpg"))
            val gallery = GalleryPhoto(
                uri = coverPhoto.uri,
                timestamp = coverPhoto.capturedAt,
                source = GalleryPhotoSource.FromPlant(coverPhoto)
            )

            vm.setWateringInterval(12)
            vm.deletePhoto(gallery)
            advanceUntilIdle()

            assertEquals(12, stored.value?.wateringIntervalDays)
            assertNull(stored.value?.coverPhotoUri)
        }

    // ---- Dormancy shares the lock ----

    @Test
    fun `a dormancy edit right after an interval write keeps both`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = makeVm(plant())

            vm.setWateringInterval(12)
            vm.setDormancyWindow(11, 2)
            advanceUntilIdle()

            assertEquals(12, stored.value?.wateringIntervalDays)
            assertEquals(11, stored.value?.dormancyStartMonth)
            assertEquals(2, stored.value?.dormancyEndMonth)
        }

    @Test
    fun `an interval write right after a dormancy edit keeps both`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val vm = makeVm(plant())

            vm.setDormancyWindow(11, 2)
            vm.setWateringInterval(12)
            advanceUntilIdle()

            assertEquals(12, stored.value?.wateringIntervalDays)
            assertEquals(11, stored.value?.dormancyStartMonth)
            assertEquals(2, stored.value?.dormancyEndMonth)
        }

    private companion object {
        const val WRITE_MS = 10L
        const val ECHO_LAG_MS = 500L
        const val OVERRIDE_AT = 1_800_000_000_000L
    }
}
