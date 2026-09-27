package com.yapt.planttracker.ui.screens.today

import android.app.Application
import app.cash.turbine.test
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.TodayCareRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.Plant
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
    private val featureFlags = mockk<FeatureFlags>()
    private val quickLogUseCase = mockk<QuickLogUseCase>()
    private val plantRepository = mockk<PlantRepository>()
    private val careLogRepository = mockk<CareLogRepository>()
    private val plantPhotoRepository = mockk<PlantPhotoRepository>()
    private val application = mockk<Application>(relaxed = true)
    private val grouped = MutableStateFlow(false)

    @Before
    fun setUp() {
        every { featureFlags.isEnabled(FeatureFlagRegistry.TODAY_GROUP_BY_PLANT) } returns grouped
        every { application.getString(R.string.today_action_failed) } returns "failed"
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
    fun `aggregation failure shows error and retry starts a fresh collection`() = runTest {
        var calls = 0
        every { repository.observeQueue() } answers {
            calls++
            if (calls == 1) {
                flow { throw IllegalStateException("boom") }
            } else {
                flowOf(TodayQueueSnapshot(0, emptyList()))
            }
        }
        val viewModel = viewModel()

        assertEquals(TodayUiState.Error, viewModel.uiState.first { it is TodayUiState.Error })
        viewModel.retry()
        assertTrue(viewModel.uiState.first { it is TodayUiState.Ready } is TodayUiState.Ready)
        assertEquals(2, calls)
    }

    @Test
    fun `developer flag hot switches presentation without changing tasks`() = runTest {
        every { repository.observeQueue() } returns flowOf(TodayQueueSnapshot(1, listOf(task())))
        val viewModel = viewModel()
        val ungrouped = viewModel.uiState.first { it is TodayUiState.Ready } as TodayUiState.Ready

        viewModel.uiState.test {
            assertEquals(ungrouped, awaitItem())
            grouped.value = true
            val groupedState = awaitItem() as TodayUiState.Ready
            assertEquals(ungrouped.snapshot.tasks, groupedState.snapshot.tasks)
            assertTrue(groupedState.groupByPlant)
        }
    }

    @Test
    fun `group selection maps to bulk eligible canonical task ids only`() = runTest {
        val plant = plant()
        val water = task("water:1", plant, TodayCareKind.WATER)
        val photo = task("photo:1", plant, TodayCareKind.PHOTO)
        every { repository.observeQueue() } returns flowOf(TodayQueueSnapshot(1, listOf(water, photo)))
        val viewModel = viewModel()

        viewModel.uiState.first { it is TodayUiState.Ready }
        viewModel.togglePlantSelection(plant.id)
        assertEquals(setOf(water.id), viewModel.selectedTaskIds.value)
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

    private fun viewModel() = TodayViewModel(
        application,
        repository,
        featureFlags,
        quickLogUseCase,
        plantRepository,
        careLogRepository,
        plantPhotoRepository
    )

    private fun plant() = Plant(id = 1L, name = "Fern", createdAt = 0L, updatedAt = 0L)

    private fun task(
        id: String = "water:1",
        plant: Plant = plant(),
        kind: TodayCareKind = TodayCareKind.WATER
    ) = TodayCareTask(
        id = id,
        plant = plant,
        kind = kind,
        dueAt = 0L,
        bucket = TodayTaskBucket.Today
    )
}
