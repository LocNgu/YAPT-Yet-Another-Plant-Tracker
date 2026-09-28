package com.yapt.planttracker.ui.screens.today

import android.app.Application
import android.net.Uri
import app.cash.turbine.test
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.TodayCareRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayQueueSnapshot
import com.yapt.planttracker.domain.today.TodayTaskBucket
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val repository = mockk<TodayCareRepository>()
    private val quickLogUseCase = mockk<QuickLogUseCase>()
    private val plantRepository = mockk<PlantRepository>()
    private val application = mockk<Application>(relaxed = true)

    @Before
    fun setUp() {
        every { application.getString(R.string.today_action_failed) } returns "failed"
        every { application.getString(R.string.today_photo_saved, any()) } returns "photo saved"
    }

    @Test
    fun `queue distinguishes loading from ready empty state`() = runTest {
        val queue = MutableSharedFlow<TodayQueueSnapshot>()
        every { repository.observeQueue() } returns queue
        val viewModel = viewModel()

        viewModel.uiState.test {
            assertEquals(TodayUiState.Loading, awaitItem())
            queue.emit(TodayQueueSnapshot(1, emptyList()))
            val ready = awaitItem() as TodayUiState.Ready
            assertEquals(1, ready.snapshot.activePlantCount)
            assertTrue(ready.snapshot.tasks.isEmpty())
        }
    }

    @Test
    fun `aggregation failure shows error and retry shows loading then ready`() = runTest {
        var calls = 0
        val failing = MutableSharedFlow<TodayQueueSnapshot>(replay = 1)
        val recovered = MutableSharedFlow<TodayQueueSnapshot>(replay = 1)
        every { repository.observeQueue() } answers {
            calls++
            if (calls == 1) failing.map { throw IllegalStateException("boom") } else recovered
        }
        val viewModel = viewModel()

        viewModel.uiState.test {
            assertEquals(TodayUiState.Loading, awaitItem())
            failing.emit(TodayQueueSnapshot(0, emptyList()))
            assertEquals(TodayUiState.Error, awaitItem())
            viewModel.retry()
            assertEquals(TodayUiState.Loading, awaitItem())
            recovered.emit(TodayQueueSnapshot(0, emptyList()))
            assertTrue(awaitItem() is TodayUiState.Ready)
        }
        assertEquals(2, calls)
    }

    @Test
    fun `resubscribing after the stop timeout never flashes loading once ready was shown`() = runTest {
        every { repository.observeQueue() } returns flowOf(TodayQueueSnapshot(1, listOf(task())))
        val viewModel = viewModel()

        viewModel.uiState.first { it is TodayUiState.Ready }
        advanceTimeBy(STOP_TIMEOUT_PLUS_MS)
        runCurrent()

        viewModel.uiState.test {
            assertTrue(awaitItem() is TodayUiState.Ready)
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancelled custom reminder completion is not reported as a failure`() = runTest {
        val reminder = CustomReminder(id = 9L, plantId = 1L, name = "Neem", intervalDays = 7)
        val custom = task("custom:9", plant(), TodayCareKind.CUSTOM_REMINDER, reminder)
        every { repository.observeQueue() } returns flowOf(TodayQueueSnapshot(1, listOf(custom)))
        coEvery { quickLogUseCase.completeCustomReminder(any()) } throws CancellationException("cancelled")
        val viewModel = viewModel()

        viewModel.uiState.first { it is TodayUiState.Ready }
        viewModel.messageEvent.test {
            viewModel.completeCustomReminder(custom.id)
            expectNoEvents()
        }
    }

    @Test
    fun `row navigation is emitted as a one shot event`() = runTest {
        every { repository.observeQueue() } returns flowOf(TodayQueueSnapshot(1, listOf(task())))
        val viewModel = viewModel()

        viewModel.navigationEvent.test {
            viewModel.openPlant(7L)
            assertEquals(TodayNavigationEvent.PlantDetail(7L), awaitItem())
        }
    }

    @Test
    fun `water completion delegates to existing quick log flow`() = runTest {
        val task = task()
        every { repository.observeQueue() } returns flowOf(TodayQueueSnapshot(1, listOf(task)))
        coEvery { quickLogUseCase.quickWaterWithReason(task.plant, null, any()) } returns
            QuickLogUseCase.QuickLogOutcome("done", logged = true)
        val viewModel = viewModel()

        viewModel.uiState.first { it is TodayUiState.Ready }
        viewModel.messageEvent.test {
            viewModel.completeWater(task.id, null)
            assertEquals("done", awaitItem())
            coVerify { quickLogUseCase.quickWaterWithReason(task.plant, null, any()) }
        }
    }

    @Test
    fun `failed row action emits user feedback`() = runTest {
        val task = task()
        every { repository.observeQueue() } returns flowOf(TodayQueueSnapshot(1, listOf(task)))
        coEvery { quickLogUseCase.quickWaterWithReason(task.plant, null, any()) } throws
            IllegalStateException("boom")
        val viewModel = viewModel()

        viewModel.uiState.first { it is TodayUiState.Ready }
        viewModel.messageEvent.test {
            viewModel.completeWater(task.id, null)
            assertEquals("failed", awaitItem())
        }
    }

    @Test
    fun `row actions delegate fertilize repot custom and reschedule work`() = runTest {
        val plant = plant()
        val fertilize = task("fertilize:1", plant, TodayCareKind.FERTILIZE)
        val combined = task("combined:1", plant, TodayCareKind.WATER_AND_FERTILIZE)
        val repot = task("repot:1", plant, TodayCareKind.REPOT)
        val reminder = CustomReminder(id = 9L, plantId = plant.id, name = "Neem", intervalDays = 7)
        val custom = task("custom:9", plant, TodayCareKind.CUSTOM_REMINDER, reminder)
        val tasks = listOf(fertilize, combined, repot, custom)
        every { repository.observeQueue() } returns flowOf(TodayQueueSnapshot(1, tasks))
        coEvery { quickLogUseCase.quickLog(any(), any(), any()) } returns outcome()
        coEvery { quickLogUseCase.quickLiquidFertilizeWithReason(any(), any(), any()) } returns outcome()
        coEvery { quickLogUseCase.completeCustomReminder(any()) } returns outcome()
        coEvery { quickLogUseCase.recordReschedule(any(), any()) } returns Unit
        val viewModel = viewModel()

        viewModel.uiState.first { it is TodayUiState.Ready }
        viewModel.completeFertilizing(fertilize.id, null)
        viewModel.completeFertilizing(combined.id, null)
        viewModel.completeRepotting(repot.id, 123L)
        viewModel.completeCustomReminder(custom.id)
        viewModel.rescheduleWatering(fertilize.id, 456L)

        coVerify { quickLogUseCase.quickLog(plant, CareType.FERTILIZE, any()) }
        coVerify { quickLogUseCase.quickLiquidFertilizeWithReason(plant, null, any()) }
        coVerify { quickLogUseCase.quickLog(plant, CareType.REPOT, 123L) }
        coVerify { quickLogUseCase.completeCustomReminder(custom) }
        coVerify { quickLogUseCase.recordReschedule(plant, 456L) }
    }

    @Test
    fun `photo completion uses launch time plant identity after task disappears`() = runTest {
        val plant = plant()
        val photo = task("photo:1", plant, TodayCareKind.PHOTO)
        val queue = MutableStateFlow(TodayQueueSnapshot(1, listOf(photo)))
        val uri = mockk<Uri>(relaxed = true)
        val uriString = uri.toString()
        every { repository.observeQueue() } returns queue
        coEvery { quickLogUseCase.saveReminderPhoto(plant.id, uriString) } returns plant
        val viewModel = viewModel()

        viewModel.uiState.first { it is TodayUiState.Ready }
        queue.value = TodayQueueSnapshot(1, emptyList())
        viewModel.uiState.first { it is TodayUiState.Ready && it.snapshot.tasks.isEmpty() }
        viewModel.messageEvent.test {
            viewModel.savePhoto(plant.id, uri)
            assertEquals("photo saved", awaitItem())
        }

        coVerify(exactly = 1) { quickLogUseCase.saveReminderPhoto(plant.id, uriString) }
    }

    @Test
    fun `photo completion reports failure when launch time plant no longer exists`() = runTest {
        val uri = mockk<Uri>()
        every { repository.observeQueue() } returns flowOf(TodayQueueSnapshot(0, emptyList()))
        coEvery { quickLogUseCase.saveReminderPhoto(1L, any()) } returns null
        val viewModel = viewModel()

        viewModel.messageEvent.test {
            viewModel.savePhoto(1L, uri)
            assertEquals("failed", awaitItem())
        }
    }

    @Test
    fun `water suggestion and suggestion decisions are dispatched`() = runTest {
        val plant = plant()
        val water = task(plant = plant)
        val suggestion = QuickWaterSuggestion(plant.id, plant.name, 6, 6, 6.0, 5)
        every { repository.observeQueue() } returns flowOf(TodayQueueSnapshot(1, listOf(water)))
        every { plantRepository.getPlantById(plant.id) } returns flowOf(plant)
        coEvery { quickLogUseCase.quickWaterWithReason(any(), any(), any()) } returns
            outcome(suggestion = suggestion)
        coEvery { quickLogUseCase.applyWateringIntervalSuggestion(any(), any(), any(), any()) } returns
            QuickLogUseCase.IntervalApplyResult(5, null, 6)
        coEvery { quickLogUseCase.recordWateringSuggestionDismissal(any()) } returns plant
        val viewModel = viewModel()

        viewModel.uiState.first { it is TodayUiState.Ready }
        viewModel.wateringSuggestion.test {
            viewModel.completeWater(water.id, null)
            assertEquals(suggestion, awaitItem())
        }
        viewModel.applySuggestedInterval(suggestion, 6, 6.0)
        viewModel.dismissSuggestedInterval(plant.id)

        coVerify { quickLogUseCase.applyWateringIntervalSuggestion(plant, 6, 6, 6.0) }
        coVerify { quickLogUseCase.recordWateringSuggestionDismissal(plant) }
    }

    private fun viewModel() = TodayViewModel(
        application,
        repository,
        quickLogUseCase,
        plantRepository
    )

    private fun plant() = Plant(id = 1L, name = "Fern", createdAt = 0L, updatedAt = 0L)

    private fun task(
        id: String = "water:1",
        plant: Plant = plant(),
        kind: TodayCareKind = TodayCareKind.WATER,
        customReminder: CustomReminder? = null
    ) = TodayCareTask(
        id = id,
        plant = plant,
        kind = kind,
        dueAt = 0L,
        bucket = TodayTaskBucket.Today,
        customReminder = customReminder
    )

    private fun outcome(suggestion: QuickWaterSuggestion? = null) =
        QuickLogUseCase.QuickLogOutcome("done", logged = true, suggestion = suggestion)

    private companion object {
        const val STOP_TIMEOUT_PLUS_MS = 5_001L
    }
}
