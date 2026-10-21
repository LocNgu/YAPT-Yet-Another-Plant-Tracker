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
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * #927 (product ADR-0068): the Plant Detail hero and Water-tab card show today's live interval, and
 * committing exactly that value is a full no-op. A fixed clock, hemisphere and day-change ticker make
 * the seasonal factor deterministic (15 January, northern: factor ~1.345 at the STANDARD amplitude).
 */
class PlantDetailViewModelLiveIntervalTest {

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

    private val zone = ZoneId.systemDefault()
    private val january = LocalDate.of(2026, 1, 15)
    private val july = LocalDate.of(2026, 7, 15)
    private val amplitude = SeasonalAmplitude.STANDARD.value
    private val today = MutableStateFlow(january)
    private var hemisphere = Hemisphere.NORTHERN

    private val nowMillis = january.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun plant(
        literal: Int? = 7,
        base: Double? = 8.0,
        pinned: Boolean = false,
        updatedAt: Long = 0L
    ) = Plant(
        id = 1L,
        name = "Monstera",
        wateringIntervalDays = literal,
        wateringBaseIntervalDays = base,
        pinIntervalToBase = pinned,
        createdAt = 0L,
        updatedAt = updatedAt
    )

    private fun makeVm(
        stored: MutableStateFlow<Plant?>,
        plantFlows: List<Flow<Plant?>>? = null
    ): PlantDetailViewModel {
        every { careLogRepo.getLogsForPlant(1L) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(1L) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(1L) } returns flowOf(emptyList())
        every { customReminderRepo.getRemindersForPlant(1L) } returns flowOf(emptyList())
        every { plantIssueRepo.getActiveIssuesForPlant(1L) } returns flowOf(emptyList())
        if (plantFlows == null) {
            every { plantRepo.getPlantById(1L) } returns stored
        } else {
            every { plantRepo.getPlantById(1L) } returnsMany plantFlows
        }
        coEvery { plantRepo.updatePlant(any()) } coAnswers { stored.value = firstArg() }
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
            wateringAdjustmentRepo,
            CoroutineScope(mainDispatcherRule.testDispatcher),
            null,
            today,
            { hemisphere },
            { nowMillis }
        )
    }

    @Test
    fun `live interval is today's value and never the stored literal`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = makeVm(MutableStateFlow(plant(literal = 7, base = 8.0)))

        vm.liveWateringInterval.test {
            val live = expectMostRecentItem()!!
            assertEquals(SeasonalWatering.effectiveInterval(8.0, january, amplitude, hemisphere), live.effectiveDays)
            assertNotEquals(7, live.effectiveDays)
            assertEquals(FertilizingSeason.WINTER, live.adjustment?.season)
            assertEquals(8, live.adjustment?.baseDays)
        }
    }

    @Test
    fun `a day-change emission updates the live value and the season`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = makeVm(MutableStateFlow(plant(literal = 7, base = 20.0)))

        vm.liveWateringInterval.test {
            val winter = expectMostRecentItem()!!
            today.value = july
            val summer = expectMostRecentItem()!!

            assertEquals(SeasonalWatering.effectiveInterval(20.0, july, amplitude, hemisphere), summer.effectiveDays)
            assertNotEquals(winter.effectiveDays, summer.effectiveDays)
            assertEquals(FertilizingSeason.SUMMER, summer.adjustment?.season)
        }
    }

    @Test
    fun `live interval is null while no watering interval is set`() = runTest(mainDispatcherRule.testDispatcher) {
        val vm = makeVm(MutableStateFlow(plant(literal = null)))

        vm.liveWateringInterval.test {
            assertNull(expectMostRecentItem())
        }
    }

    @Test
    fun `committing the live value is a full no-op`() = runTest(mainDispatcherRule.testDispatcher) {
        val stored = MutableStateFlow<Plant?>(plant(literal = 7, base = 8.0, updatedAt = 123L))
        val vm = makeVm(stored)
        val live = SeasonalWatering.effectiveInterval(8.0, january, amplitude, hemisphere)

        vm.setWateringInterval(live)
        advanceUntilIdle()

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
        assertEquals(123L, stored.value?.updatedAt)
        assertEquals(8.0, stored.value?.wateringBaseIntervalDays)
    }

    @Test
    fun `a tap burst that ends on the live value writes nothing`() = runTest(mainDispatcherRule.testDispatcher) {
        val stored = MutableStateFlow<Plant?>(plant(literal = 7, base = 8.0))
        val vm = makeVm(stored)
        val live = SeasonalWatering.effectiveInterval(8.0, january, amplitude, hemisphere)

        vm.setWateringInterval(live + 1, viaButtonTap = true)
        vm.setWateringInterval(live, viaButtonTap = true)
        advanceUntilIdle()

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
    }

    // `vm.plant` collects the first flow returned by getPlantById (stale); the write's fresh read in
    // the lock calls it again and gets the second (fresh), so the two disagree on purpose.
    private fun makeVmWithStaleCache(stale: Plant, fresh: Plant): PlantDetailViewModel =
        makeVm(MutableStateFlow(fresh), plantFlows = listOf(flowOf(stale), flowOf(fresh)))

    @Test
    fun `the no-op comparison follows the fresh plant when the cached one is stale`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val stale = plant(literal = 7, base = 8.0)
            val fresh = plant(literal = 7, base = 20.0)
            val vm = makeVmWithStaleCache(stale, fresh)
            val liveForFresh = SeasonalWatering.effectiveInterval(20.0, january, amplitude, hemisphere)

            vm.plant.test {
                assertEquals(stale, expectMostRecentItem())
                vm.setWateringInterval(liveForFresh)
                advanceUntilIdle()
            }

            coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        }

    @Test
    fun `committing the stale cached live value still writes when the fresh plant differs`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val stale = plant(literal = 7, base = 8.0)
            val fresh = plant(literal = 7, base = 20.0)
            val vm = makeVmWithStaleCache(stale, fresh)
            val liveForStale = SeasonalWatering.effectiveInterval(8.0, january, amplitude, hemisphere)

            vm.plant.test {
                assertEquals(stale, expectMostRecentItem())
                vm.setWateringInterval(liveForStale)
                advanceUntilIdle()
            }

            coVerify(exactly = 1) { plantRepo.updatePlant(match { it.wateringIntervalDays == liveForStale }) }
        }

    @Test
    fun `committing the stored literal while live differs writes and audits`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val stored = MutableStateFlow<Plant?>(plant(literal = 7, base = 8.0))
            val vm = makeVm(stored)

            vm.setWateringInterval(7)
            advanceUntilIdle()

            val expectedBase = SeasonalWatering.deseasonalize(7.0, january, amplitude, hemisphere)
            assertEquals(7, stored.value?.wateringIntervalDays)
            assertEquals(expectedBase, stored.value!!.wateringBaseIntervalDays!!, 1e-9)
            coVerify(exactly = 1) {
                wateringAdjustmentRepo.addAdjustment(
                    match {
                        it.trigger == WateringAdjustmentTrigger.MANUAL_EDIT &&
                            it.beforeIntervalDays == 8 &&
                            it.afterIntervalDays == expectedBase.roundToInt()
                    }
                )
            }
        }

    @Test
    fun `a real change stamps updatedAt from the injected clock`() = runTest(mainDispatcherRule.testDispatcher) {
        val stored = MutableStateFlow<Plant?>(plant(literal = 7, base = 8.0))
        val vm = makeVm(stored)

        vm.setWateringInterval(7)
        advanceUntilIdle()

        assertEquals(nowMillis, stored.value?.updatedAt)
    }

    @Test
    fun `a write whose rounded base is unchanged still writes but logs no audit row`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val storedBase = 19.6
            val live = SeasonalWatering.effectiveInterval(storedBase, january, amplitude, hemisphere)
            val days = live + 1
            val newBase = SeasonalWatering.deseasonalize(days.toDouble(), january, amplitude, hemisphere)
            assertEquals(storedBase.roundToInt(), newBase.roundToInt())
            val stored = MutableStateFlow<Plant?>(plant(literal = 7, base = storedBase))
            val vm = makeVm(stored)

            vm.setWateringInterval(days)
            advanceUntilIdle()

            coVerify(exactly = 1) { plantRepo.updatePlant(match { it.wateringIntervalDays == days }) }
            coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
        }

    @Test
    fun `a coalesced burst with a real change writes once and audits once`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val stored = MutableStateFlow<Plant?>(plant(literal = 7, base = 8.0))
            val vm = makeVm(stored)

            vm.setWateringInterval(12, viaButtonTap = true)
            vm.setWateringInterval(13, viaButtonTap = true)
            vm.setWateringInterval(14, viaButtonTap = true)
            advanceUntilIdle()

            coVerify(exactly = 1) { plantRepo.updatePlant(any()) }
            coVerify(exactly = 1) { wateringAdjustmentRepo.addAdjustment(any()) }
            assertEquals(14, stored.value?.wateringIntervalDays)
        }

    @Test
    fun `a live value over 60 is a no-op when committed unmoved and commits 60 on a decrease`() =
        runTest(mainDispatcherRule.testDispatcher) {
            val stored = MutableStateFlow<Plant?>(plant(literal = 50, base = 50.0))
            val vm = makeVm(stored)
            val live = SeasonalWatering.effectiveInterval(50.0, january, amplitude, hemisphere)
            assertNotNull(live.takeIf { it > 60 })

            vm.setWateringInterval(live)
            advanceUntilIdle()
            coVerify(exactly = 0) { plantRepo.updatePlant(any()) }

            vm.setWateringInterval(60)
            advanceUntilIdle()
            coVerify(exactly = 1) { plantRepo.updatePlant(match { it.wateringIntervalDays == 60 }) }
        }

    @Test
    fun `a pinned plant committing its literal is a no-op`() = runTest(mainDispatcherRule.testDispatcher) {
        val stored = MutableStateFlow<Plant?>(plant(literal = 7, base = 12.0, pinned = true))
        val vm = makeVm(stored)

        vm.setWateringInterval(7)
        advanceUntilIdle()

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
    }

    @Test
    fun `switching on a plant with no interval writes the shown value`() = runTest(mainDispatcherRule.testDispatcher) {
        val stored = MutableStateFlow<Plant?>(plant(literal = null, base = null))
        val vm = makeVm(stored)

        vm.setWateringInterval(7)
        advanceUntilIdle()

        assertEquals(7, stored.value?.wateringIntervalDays)
    }

    @Test
    fun `switching off still writes null`() = runTest(mainDispatcherRule.testDispatcher) {
        val stored = MutableStateFlow<Plant?>(plant(literal = 7, base = 8.0))
        val vm = makeVm(stored)

        vm.setWateringInterval(null)
        advanceUntilIdle()

        assertNull(stored.value?.wateringIntervalDays)
        coVerify(exactly = 1) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `the southern hemisphere flips the season used for the guard and the live value`() =
        runTest(mainDispatcherRule.testDispatcher) {
            hemisphere = Hemisphere.SOUTHERN
            val vm = makeVm(MutableStateFlow(plant(literal = 7, base = 20.0)))

            vm.liveWateringInterval.test {
                assertEquals(FertilizingSeason.SUMMER, expectMostRecentItem()?.adjustment?.season)
            }
        }
}
