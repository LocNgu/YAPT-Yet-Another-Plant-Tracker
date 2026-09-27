package com.yapt.planttracker.ui.screens.calendar

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.PhotoReminderRequest
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.domain.model.WateringReason
import com.yapt.planttracker.domain.reminder.PhotoReminderPolicy
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class CalendarViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val application: Application = mockk {
        every { getString(R.string.quick_log_watered, any()) } answers { "Watered ${(args[1] as Array<*>)[0]}" }
        every {
            getString(R.string.quick_log_watered_and_fertilized, any())
        } answers { "Watered and fertilized ${(args[1] as Array<*>)[0]}" }
    }
    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val dataStore: DataStore<Preferences> = mockk {
        every { data } returns flowOf(emptyPreferences())
    }
    private val quickLogUseCase: QuickLogUseCase = mockk()

    // The real dayChangeTicker() default is a genuine while-true delay() loop; most tests below
    // don't exercise day-change behavior at all (see the dedicated #550 tests further down, which
    // inject their own controllable flow), so they use this single-emission stand-in instead —
    // never the real ticker, which would leave a coroutine parked on Dispatchers.Main's own test
    // scheduler for the life of the test (see DayChangeTickerTest/technical ADR-0035).
    private val dayChangeFlow = flowOf(LocalDate.now())

    private lateinit var vm: CalendarViewModel

    private fun plant(id: Long, name: String) = Plant(id = id, name = name, createdAt = 0L, updatedAt = 0L)

    @Before
    fun setup() {
        PhotoReminderPolicy.shownThisSession.clear()
        every { careLogRepo.logCount } returns flowOf(0)
        coEvery { careLogRepo.getLastLogOfType(any(), any()) } returns null
        coEvery { careLogRepo.getCareLogCount(any()) } returns 0
        // Default: no photo reminder unless a test explicitly stubs otherwise.
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(any()) } returns null
    }

    @After
    fun tearDown() {
        PhotoReminderPolicy.shownThisSession.clear()
    }

    // quickLog/quickWater/quickLiquidFertilize delegate the actual
    // care-log persistence, override clearing, and adaptive-interval computation to
    // QuickLogUseCase (see QuickLogUseCaseTest). These tests verify VM-level orchestration only:
    // the right use-case method is invoked with the resolved plant, and its result is mapped onto
    // the correct StateFlow/SharedFlow.

    @Test
    fun `quickLog water routes through quickWater and emits its snackbar message`() = runTest {
        val monstera = plant(1L, "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickWaterWithReason(monstera, null, any()) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Watered Monstera", logged = true)
        vm = CalendarViewModel(
            application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase, dayChangeFlow
        )

        vm.quickLogEvent.test {
            vm.plantsWithStatus.test {
                awaitItem()
                vm.quickLog(1L, CareType.WATER)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals("Watered Monstera", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { quickLogUseCase.quickWaterWithReason(monstera, null, any()) }
    }

    @Test
    fun `quickWater emits the QuickWaterSuggestion returned by the use case`() = runTest {
        val monstera = Plant(id = 1L, name = "Monstera", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT, any()) } returns
            QuickLogUseCase.QuickLogOutcome(
                message = "Watered Monstera",
                logged = true,
                suggestion = QuickWaterSuggestion(
                    plantId = 1L,
                    plantName = "Monstera",
                    suggestedInterval = 4,
                    suggestedIntervalEffective = 5,
                    suggestedBaseInterval = 4.0,
                    currentIntervalEffective = 7
                )
            )
        vm = CalendarViewModel(
            application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase, dayChangeFlow
        )

        vm.quickWaterSuggestion.test {
            vm.plantsWithStatus.test {
                awaitItem()
                vm.quickWater(1L, WateringReason.PLANT_NEEDED_IT)
                cancelAndIgnoreRemainingEvents()
            }
            val suggestion = awaitItem()
            assertEquals(1L, suggestion.plantId)
            assertEquals(4, suggestion.suggestedInterval)
            // #620: the dialog binds display/gating to suggestedIntervalEffective, not the raw
            // base-space suggestedInterval — verify CalendarViewModel forwards it unchanged, since it
            // does no conversion of its own (QuickLogUseCase.computeSuggestion() is the sole source).
            assertEquals(5, suggestion.suggestedIntervalEffective)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `quickLog emits a PhotoReminderRequest when the use case returns one`() = runTest {
        val monstera = plant(1L, "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE, any()) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Fertilized Monstera", logged = true)
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(1L) } returns
            PhotoReminderRequest(plantId = 1L, plantName = "Monstera", daysSince = 45L)
        vm = CalendarViewModel(
            application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase, dayChangeFlow
        )

        vm.plantsWithStatus.test {
            awaitItem()
            vm.quickLog(1L, CareType.FERTILIZE)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        val request = vm.photoReminderRequest.value
        assertNotNull(request)
        assertEquals(1L, request!!.plantId)
    }

    @Test
    fun `quickLog does not emit photo reminder when plant already reminded this session`() = runTest {
        // Simulates the plant having already been reminded on Plants tab or Plant Detail this session.
        PhotoReminderPolicy.shownThisSession.add(1L)
        val monstera = plant(1L, "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE, any()) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Fertilized Monstera", logged = true)
        // Default @Before stub already returns null for maybeBuildPhotoReminderRequest; this test
        // documents that the gating (session suppression) lives in QuickLogUseCase, not the VM.
        vm = CalendarViewModel(
            application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase, dayChangeFlow
        )

        vm.plantsWithStatus.test {
            awaitItem()
            vm.quickLog(1L, CareType.FERTILIZE)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(vm.photoReminderRequest.value)
    }

    @Test
    fun `quickLog does not emit photo reminder when the use case returns null`() = runTest {
        val monstera = plant(1L, "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickLog(monstera, CareType.FERTILIZE, any()) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Fertilized Monstera", logged = true)
        vm = CalendarViewModel(
            application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase, dayChangeFlow
        )

        vm.plantsWithStatus.test {
            awaitItem()
            vm.quickLog(1L, CareType.FERTILIZE)
            advanceUntilIdle()
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(vm.photoReminderRequest.value)
    }

    @Test
    fun `quickLiquidFertilize emits watered-and-fertilized message, no interval suggestion`() = runTest {
        val monstera = Plant(
            id = 1L,
            name = "Monstera",
            useLiquidFertilizer = true,
            wateringIntervalDays = 7,
            createdAt = 0L,
            updatedAt = 0L
        )
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.quickLiquidFertilizeWithReason(monstera, null, any()) } returns
            QuickLogUseCase.QuickLogOutcome(
                message = "Watered and fertilized Monstera",
                logged = true,
                waterPaired = true
            )
        vm = CalendarViewModel(
            application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase, dayChangeFlow
        )

        vm.quickWaterSuggestion.test {
            vm.quickLogEvent.test {
                vm.plantsWithStatus.test {
                    awaitItem()
                    vm.quickLiquidFertilize(1L, null)
                    cancelAndIgnoreRemainingEvents()
                }
                assertEquals("Watered and fertilized Monstera", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { quickLogUseCase.quickLiquidFertilizeWithReason(monstera, null, any()) }
    }

    @Test
    fun `quickLiquidFertilize emits the suggestion returned by the use case`() = runTest {
        val monstera = Plant(
            id = 1L,
            name = "Monstera",
            useLiquidFertilizer = true,
            wateringIntervalDays = 7,
            createdAt = 0L,
            updatedAt = 0L
        )
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery {
            quickLogUseCase.quickLiquidFertilizeWithReason(monstera, WateringReason.PLANT_NEEDED_IT, any())
        } returns
            QuickLogUseCase.QuickLogOutcome(
                message = "Watered and fertilized Monstera",
                logged = true,
                waterPaired = true,
                suggestion = QuickWaterSuggestion(
                    plantId = 1L,
                    plantName = "Monstera",
                    suggestedInterval = 4,
                    suggestedIntervalEffective = 4,
                    suggestedBaseInterval = 4.0,
                    currentIntervalEffective = 7
                )
            )
        vm = CalendarViewModel(
            application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase, dayChangeFlow
        )

        vm.quickWaterSuggestion.test {
            vm.plantsWithStatus.test {
                awaitItem()
                vm.quickLiquidFertilize(1L, WateringReason.PLANT_NEEDED_IT)
                cancelAndIgnoreRemainingEvents()
            }
            val suggestion = awaitItem()
            assertEquals(1L, suggestion.plantId)
            assertEquals(4, suggestion.suggestedInterval)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selectDay updates selectedDay and selectDay null clears it`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        vm = CalendarViewModel(
            application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase, dayChangeFlow
        )

        val day = java.time.LocalDate.of(2026, 7, 15)
        vm.selectDay(day)
        assertEquals(day, vm.selectedDay.value)

        vm.selectDay(null)
        assertNull(vm.selectedDay.value)
    }

    @Test
    fun `setVisibleMonth updates plantsByDay window`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        vm = CalendarViewModel(
            application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase, dayChangeFlow
        )

        val month = java.time.YearMonth.of(2026, 9)
        vm.setVisibleMonth(month)
        advanceUntilIdle()

        assertEquals(month, vm.visibleMonth.value)
    }

    // dismissSuggestedInterval/applySuggestedInterval are thin delegations to QuickLogUseCase's shared
    // functions so the product ADR-0006 dialog has the same confidence effect (and, for dismissal, the same
    // WateringAdjustment row, #674) regardless of which of the three screens it was shown from.
    // Write-path math-correctness coverage lives in QuickLogUseCaseIntervalApplyTest (#631) and
    // QuickLogUseCaseDismissalTest (#674), directly against QuickLogUseCase.

    @Test
    fun `applySuggestedInterval delegates to QuickLogUseCase with the resolved plant`() = runTest {
        val monstera = plant(1L, "Monstera").copy(wateringConfidence = 2)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.applyWateringIntervalSuggestion(monstera, 10, 10, null) } returns
            QuickLogUseCase.IntervalApplyResult(
                previousEffectiveIntervalDays = 7,
                previousBaseIntervalDays = null,
                newEffectiveIntervalDays = 10
            )
        vm = CalendarViewModel(
            application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase, dayChangeFlow
        )

        vm.applySuggestedInterval(1L, suggestedIntervalDays = 10, newInterval = 10, suggestedBaseInterval = null)
        advanceUntilIdle()

        coVerify { quickLogUseCase.applyWateringIntervalSuggestion(monstera, 10, 10, null) }
    }

    @Test
    fun `dismissSuggestedInterval delegates to QuickLogUseCase with the resolved plant`() = runTest {
        val monstera = plant(1L, "Monstera").copy(wateringConfidence = 1)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        coEvery { quickLogUseCase.recordWateringSuggestionDismissal(monstera) } returns
            monstera.copy(wateringConfidence = 2)
        vm = CalendarViewModel(
            application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase, dayChangeFlow
        )

        vm.dismissSuggestedInterval(1L)
        advanceUntilIdle()

        coVerify { quickLogUseCase.recordWateringSuggestionDismissal(monstera) }
    }

    // Day-change ticker (#550) — plantsWithStatus/plantsByDay recompute at midnight with no other
    // state change. The real dayChangeTicker() isn't exercised here (see DayChangeTickerTest for
    // that); these tests inject a controllable replay-1 MutableSharedFlow standing in for it, so a
    // "tick" is just a manual emit rather than a real delay.

    @Test
    fun `plantsWithStatus recomputes watering due status on a day-change tick (#550)`() = runTest {
        val p1 = Plant(id = 1L, name = "P1", wateringIntervalDays = 1, createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(p1))
        val oneDayMs = TimeUnit.DAYS.toMillis(1)
        val now = System.currentTimeMillis()
        // First evaluation: just watered, not overdue. Second, after the tick, simulates enough
        // calendar time having passed for the 1-day interval to become overdue with no new log.
        coEvery { careLogRepo.getLastLogOfType(1L, CareType.WATER) } returnsMany listOf(
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = now),
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = now - oneDayMs * 3)
        )
        val dayChangeFlow = MutableSharedFlow<LocalDate>(replay = 1)
        dayChangeFlow.tryEmit(LocalDate.of(2026, 1, 1))
        vm = CalendarViewModel(
            application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase, dayChangeFlow
        )

        vm.plantsWithStatus.test {
            val before = awaitItem()
            assertFalse(before[0].isOverdue)

            dayChangeFlow.emit(LocalDate.of(2026, 1, 2))
            advanceUntilIdle()

            val after = awaitItem()
            assertTrue(after[0].isOverdue)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `plantsByDay recomputes the dormant-today bucket on a day-change tick (#550)`() = runTest {
        // Dormant year-round so isDormant is true regardless of which real-world month the test
        // happens to run in -- only the injected `today` value should move which day key the
        // dormant-only contribution lands under.
        val dormant = Plant(
            id = 1L,
            name = "Dormant Fern",
            dormancyStartMonth = 1,
            dormancyEndMonth = 12,
            createdAt = 0L,
            updatedAt = 0L
        )
        every { plantRepo.getAllPlants() } returns flowOf(listOf(dormant))
        val day1 = LocalDate.of(2030, 6, 14)
        val day2 = LocalDate.of(2030, 6, 15)
        val dayChangeFlow = MutableSharedFlow<LocalDate>(replay = 1)
        dayChangeFlow.tryEmit(day1)
        vm = CalendarViewModel(
            application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, quickLogUseCase, dayChangeFlow
        )
        vm.setVisibleMonth(YearMonth.of(2030, 6))
        advanceUntilIdle()

        vm.plantsByDay.test {
            val before = awaitItem()
            assertTrue(before[day1]?.dormantPlants?.any { it.status.plant.id == 1L } == true)
            assertNull(before[day2])

            dayChangeFlow.emit(day2)
            advanceUntilIdle()

            val after = awaitItem()
            assertTrue(after[day2]?.dormantPlants?.any { it.status.plant.id == 1L } == true)
            assertNull(after[day1])
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `plantsWithStatus and plantsByDay share one ticker collection, never two independent ones (#550 review)`() =
        runTest {
            // A cold flow that emits a DIFFERENT date on each independent collection -- if
            // plantsWithStatus and plantsByDay each collected the ticker separately (the bug this
            // regression test guards against), they'd disagree on "today". With a single shared
            // source, the ticker is collected at most once no matter how many derived flows read it.
            var collectionCount = 0
            val distinctDatePerCollection = flow {
                collectionCount++
                emit(if (collectionCount == 1) LocalDate.of(2026, 3, 1) else LocalDate.of(2026, 3, 2))
            }
            val p1 = plant(1L, "P1")
            every { plantRepo.getAllPlants() } returns flowOf(listOf(p1))
            vm = CalendarViewModel(
                application, plantRepo, careLogRepo, plantPhotoRepo, dataStore,
                quickLogUseCase, distinctDatePerCollection
            )
            vm.setVisibleMonth(YearMonth.of(2026, 3))

            vm.plantsWithStatus.test {
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }
            vm.plantsByDay.test {
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            assertEquals(1, collectionCount)
        }
}
