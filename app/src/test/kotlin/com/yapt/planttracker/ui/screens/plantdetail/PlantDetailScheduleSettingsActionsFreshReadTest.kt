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
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * #804: `toggleFertilizingSeason`/`setLiquidFertilizer`/`setPinIntervalToBase` used to each write
 * straight from the cached `plant` StateFlow snapshot (or, for the season selector, a full
 * replacement set built by the UI from that same stale snapshot). A second tap landing before Room
 * echoed the first write back could silently discard it. Fixed by moving all three onto
 * [PlantDetailViewModel.intervalEditMutex], reading the plant fresh
 * (`plantRepository.getPlantById(plantId).first()`) inside the lock — same fix as the watering/
 * fertilizing interval writes (#531 review round 1, product ADR-0050).
 *
 * `plantRepo.updatePlant` is mocked with a real [delay] so two rapid calls genuinely overlap under
 * [MainDispatcherRule]'s `UnconfinedTestDispatcher` (which otherwise runs each `viewModelScope.launch`
 * to completion synchronously, since none of these writes have a natural suspension point of their
 * own) — this is what actually opens the race window the mutex has to close, rather than the two
 * calls trivially serializing on their own.
 */
class PlantDetailScheduleSettingsActionsFreshReadTest {

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
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk {
        every { getRecentForPlant(any(), any()) } returns flowOf(emptyList())
        coEvery { addAdjustment(any()) } returns 1L
    }
    private val database: PlantDatabase = mockk()

    private fun plant(
        fertilizingSeasons: Set<FertilizingSeason> = FertilizingSeason.entries.toSet(),
        useLiquidFertilizer: Boolean = false,
        pinIntervalToBase: Boolean = false
    ) = Plant(
        id = 1L,
        name = "Monstera",
        createdAt = 0L,
        updatedAt = 0L,
        fertilizingIntervalDays = 14,
        fertilizingSeasons = fertilizingSeasons,
        useLiquidFertilizer = useLiquidFertilizer,
        pinIntervalToBase = pinIntervalToBase
    )

    /** Real [delay] in the mocked write — see the class doc for why this matters. */
    private fun makeVm(stored: MutableStateFlow<Plant?>): PlantDetailViewModel {
        every { careLogRepo.getLogsForPlant(1L) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(1L) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(1L) } returns flowOf(emptyList())
        every { customReminderRepo.getRemindersForPlant(1L) } returns flowOf(emptyList())
        every { plantIssueRepo.getActiveIssuesForPlant(1L) } returns flowOf(emptyList())
        every { plantRepo.getPlantById(1L) } returns stored
        coEvery { plantRepo.updatePlant(any()) } coAnswers {
            delay(10)
            stored.value = firstArg()
        }
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
    fun `two back-to-back season toggles without waiting for Room both survive`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val stored = MutableStateFlow<Plant?>(plant(fertilizingSeasons = FertilizingSeason.entries.toSet()))
            val vm = makeVm(stored)

            vm.toggleFertilizingSeason(FertilizingSeason.WINTER)
            vm.toggleFertilizingSeason(FertilizingSeason.AUTUMN)
            advanceUntilIdle()

            assertEquals(
                setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER),
                stored.value?.fertilizingSeasons
            )
        }

    @Test
    fun `a toggle that would empty the freshly read set is rejected even with a stale 2-season cached snapshot`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val stored = MutableStateFlow<Plant?>(
                plant(fertilizingSeasons = setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER))
            )
            val vm = makeVm(stored)

            // Populate the cached `plant` StateFlow with the 2-season snapshot, then stop collecting
            // it — its `.value` now lags behind `stored`, same as a screen that hasn't recomposed
            // since a write landed from elsewhere.
            vm.plant.test {
                assertEquals(setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER), awaitItem()?.fertilizingSeasons)
                cancelAndIgnoreRemainingEvents()
            }

            // A write from elsewhere narrows the real row to one season, behind the cached snapshot's back.
            stored.value = stored.value?.copy(fertilizingSeasons = setOf(FertilizingSeason.SPRING))

            vm.toggleFertilizingSeason(FertilizingSeason.SPRING)
            advanceUntilIdle()

            assertEquals(setOf(FertilizingSeason.SPRING), stored.value?.fertilizingSeasons)
        }

    @Test
    fun `a season toggle immediately followed by a liquid-fertilizer toggle both survive`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val stored = MutableStateFlow<Plant?>(
                plant(fertilizingSeasons = FertilizingSeason.entries.toSet(), useLiquidFertilizer = false)
            )
            val vm = makeVm(stored)

            vm.toggleFertilizingSeason(FertilizingSeason.WINTER)
            vm.setLiquidFertilizer(true)
            advanceUntilIdle()

            val result = stored.value
            assertEquals(
                setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER, FertilizingSeason.AUTUMN),
                result?.fertilizingSeasons
            )
            assertEquals(true, result?.useLiquidFertilizer)
        }

    @Test
    fun `a season toggle immediately followed by a pin toggle both survive`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val stored = MutableStateFlow<Plant?>(
                plant(fertilizingSeasons = FertilizingSeason.entries.toSet(), pinIntervalToBase = false)
            )
            val vm = makeVm(stored)

            vm.toggleFertilizingSeason(FertilizingSeason.WINTER)
            vm.setPinIntervalToBase(true)
            advanceUntilIdle()

            val result = stored.value
            assertEquals(
                setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER, FertilizingSeason.AUTUMN),
                result?.fertilizingSeasons
            )
            assertEquals(true, result?.pinIntervalToBase)
        }
}
