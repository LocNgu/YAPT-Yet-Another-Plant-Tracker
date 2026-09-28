package com.yapt.planttracker.ui.screens.today

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.TodayCareRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayQueueSnapshot
import com.yapt.planttracker.domain.today.TodayTaskBucket
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.toStartOfDayMillis
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TodayScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val queue = MutableStateFlow(TodayQueueSnapshot(0, emptyList()))
    private val grouped = MutableStateFlow(false)
    private val repository = mockk<TodayCareRepository>()
    private val featureFlags = mockk<FeatureFlags>()
    private val quickLogUseCase = mockk<QuickLogUseCase>(relaxed = true)

    @Test
    fun noPlantsShowsFirstUseAction() {
        var addCalls = 0
        setContent(onNavigateToAdd = { addCalls++ })

        composeTestRule.onNodeWithText("Add your first plant to see its care plan here.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("today_add_plant")
            .performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.waitForIdle()

        assertEquals(1, addCalls)
    }

    @Test
    fun emptyQueueWithPlantsShowsCaughtUpState() {
        queue.value = TodayQueueSnapshot(activePlantCount = 1, tasks = emptyList())
        setContent()

        composeTestRule.onNodeWithText("You’re all caught up for the next three days.").assertIsDisplayed()
    }

    @Test
    fun taskLayoutShowsBucketsAndNavigatesFromRow() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(
            activePlantCount = 1,
            tasks = listOf(
                task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Overdue),
                task("repot:1", fern, TodayCareKind.REPOT, TodayTaskBucket.Today)
            )
        )
        var openedPlantId: Long? = null
        setContent(onNavigateToPlant = { openedPlantId = it })

        composeTestRule.onNodeWithText("Overdue").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Today").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("Fern").assertCountEquals(2)
        composeTestRule.onAllNodesWithText("Fern")[0].performClick()
        composeTestRule.waitForIdle()

        assertEquals(fern.id, openedPlantId)
    }

    @Test
    fun longPressSelectsEligibleTaskAndShowsBulkAction() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(
            activePlantCount = 1,
            tasks = listOf(task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Today))
        )
        setContent()

        composeTestRule.onNodeWithText("Fern").performTouchInput { longClick() }

        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Complete selected").assertIsDisplayed()
    }

    @Test
    fun groupedFlagHotSwitchesToPlantSelectionWithoutChangingTaskActions() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(
            activePlantCount = 1,
            tasks = listOf(
                task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Overdue),
                task("photo:1", fern, TodayCareKind.PHOTO, TodayTaskBucket.Today)
            )
        )
        setContent()
        composeTestRule.onAllNodesWithText("Fern").assertCountEquals(2)

        grouped.value = true

        composeTestRule.onAllNodesWithText("Fern").assertCountEquals(1)
        composeTestRule.onNodeWithText("Overdue").assertIsDisplayed()
        composeTestRule.onAllNodes(hasText("days ago", substring = true)).assertCountEquals(2)
        composeTestRule.onNodeWithContentDescription("Select all care tasks for Fern").assertIsDisplayed()
        composeTestRule.onNodeWithText("Take photo").assertIsDisplayed()
    }

    @Test
    fun errorStateRetryStartsFreshQueueCollection() {
        var calls = 0
        every { repository.observeQueue() } answers {
            calls++
            if (calls == 1) flow { throw IllegalStateException("boom") } else queue
        }
        queue.value = TodayQueueSnapshot(1, emptyList())
        setContent(stubRepository = false)

        composeTestRule.onNodeWithText("Today’s care queue couldn’t be loaded.").assertIsDisplayed()
        composeTestRule.onNode(hasText("Retry").and(hasClickAction()))
            .performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.onNodeWithText("You’re all caught up for the next three days.").assertIsDisplayed()
        assertEquals(2, calls)
    }

    @Test
    fun liveQueueUpdateReplacesVisibleTaskSemantics() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(
            1,
            listOf(task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Today))
        )
        setContent()
        composeTestRule.onNodeWithContentDescription("Select Water task").assertIsDisplayed()

        queue.value = TodayQueueSnapshot(
            1,
            listOf(task("repot:1", fern, TodayCareKind.REPOT, TodayTaskBucket.Today))
        )

        composeTestRule.onNodeWithContentDescription("Select Water task").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Select Repot task").assertIsDisplayed()
    }

    @Test
    fun taskControlsExposeAccessibleSelectionAndRescheduleActions() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(
            1,
            listOf(task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Today))
        )
        setContent()

        composeTestRule.onNodeWithContentDescription("Select Water task").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Reschedule watering").assertIsDisplayed()
    }

    @Test
    fun repotAndCustomActionsDispatchTheirExistingFlows() {
        val fern = plant()
        val reminder = CustomReminder(id = 4L, plantId = fern.id, name = "Neem", intervalDays = 7)
        val repot = task("repot:1", fern, TodayCareKind.REPOT, TodayTaskBucket.Today)
        val custom = TodayCareTask(
            id = "custom:4",
            plant = fern,
            kind = TodayCareKind.CUSTOM_REMINDER,
            dueAt = System.currentTimeMillis(),
            bucket = TodayTaskBucket.Today,
            customReminder = reminder
        )
        queue.value = TodayQueueSnapshot(1, listOf(repot, custom))
        coEvery { quickLogUseCase.completeCustomReminder(custom) } returns
            QuickLogUseCase.QuickLogOutcome("done", logged = true)
        setContent()

        composeTestRule.onNodeWithText("Done").performClick()
        coVerify { quickLogUseCase.completeCustomReminder(custom) }

        composeTestRule.onNodeWithTag("today_task_action_${repot.id}").performClick()
        composeTestRule.onNodeWithTag("today_repot_date_picker").assertIsDisplayed()
    }

    @Test
    fun selectedBulkCompletionShowsResultFeedback() {
        val fern = plant()
        val water = task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Today)
        queue.value = TodayQueueSnapshot(1, listOf(water))
        coEvery { quickLogUseCase.completeTodayTasks(listOf(water)) } returns
            QuickLogUseCase.BulkCompletionResult(completedCount = 1, skippedCount = 0, totalCount = 1)
        setContent()

        composeTestRule.onNodeWithContentDescription("Select Water task").performClick()
        composeTestRule.onNodeWithText("Complete selected").performClick()

        composeTestRule.onNodeWithText("Completed 1 of 1 tasks · 0 skipped").assertIsDisplayed()
        coVerify(exactly = 1) { quickLogUseCase.completeTodayTasks(listOf(water)) }
    }

    @Test
    fun waterActionDispatchesAndShowsUseCaseFeedback() {
        val fern = plant()
        val water = task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Today)
        queue.value = TodayQueueSnapshot(1, listOf(water))
        coEvery { quickLogUseCase.quickWaterWithReason(fern, null, any()) } returns
            QuickLogUseCase.QuickLogOutcome("Watered Fern", logged = true)
        setContent()

        composeTestRule.onNodeWithTag("today_task_action_${water.id}").performClick()

        composeTestRule.onNodeWithText("Watered Fern").assertIsDisplayed()
        coVerify(exactly = 1) { quickLogUseCase.quickWaterWithReason(fern, null, any()) }
    }

    @Test
    fun photoActionDispatchesTheLaunchTimePlantIdentity() {
        val fern = plant()
        val photo = task("photo:1", fern, TodayCareKind.PHOTO, TodayTaskBucket.Today)
        queue.value = TodayQueueSnapshot(1, listOf(photo))
        var launchedPlantId: Long? = null
        setContent(onLaunchPhotoCapture = { launchedPlantId = it })

        composeTestRule.onNodeWithTag("today_task_action_${photo.id}").performClick()

        assertEquals(fern.id, launchedPlantId)
    }

    @Test
    fun upcomingTaskUsesResourceBackedRelativeDateLabels() {
        val fern = plant()
        val today = LocalDate.now()
        queue.value = TodayQueueSnapshot(
            1,
            listOf(
                task(
                    "water:1",
                    fern,
                    TodayCareKind.WATER,
                    TodayTaskBucket.Upcoming(today.plusDays(1).toEpochDay()),
                    today.plusDays(1).toStartOfDayMillis()
                ),
                task(
                    "repot:1",
                    fern,
                    TodayCareKind.REPOT,
                    TodayTaskBucket.Upcoming(today.plusDays(3).toEpochDay()),
                    today.plusDays(3).toStartOfDayMillis()
                )
            )
        )
        setContent()

        composeTestRule.onAllNodesWithText("Tomorrow").assertCountEquals(1)
        composeTestRule.onNodeWithText("In 3 days").assertIsDisplayed()
    }

    private fun setContent(
        onNavigateToPlant: (Long) -> Unit = {},
        onNavigateToAdd: () -> Unit = {},
        onLaunchPhotoCapture: ((Long) -> Unit)? = null,
        stubRepository: Boolean = true
    ): TodayViewModel {
        if (stubRepository) every { repository.observeQueue() } returns queue
        every { featureFlags.isEnabled(FeatureFlagRegistry.TODAY_GROUP_BY_PLANT) } returns grouped
        val viewModel = TodayViewModel(
            application = ApplicationProvider.getApplicationContext<Application>(),
            todayCareRepository = repository,
            featureFlags = featureFlags,
            quickLogUseCase = quickLogUseCase,
            plantRepository = mockk<PlantRepository>(relaxed = true)
        )
        composeTestRule.setContent {
            TodayScreen(
                viewModel = viewModel,
                onNavigateToPlant = onNavigateToPlant,
                onNavigateToAdd = onNavigateToAdd,
                onLaunchPhotoCapture = onLaunchPhotoCapture
            )
        }
        return viewModel
    }

    private fun plant() = Plant(id = 1L, name = "Fern", createdAt = 0L, updatedAt = 0L)

    private fun task(
        id: String,
        plant: Plant,
        kind: TodayCareKind,
        bucket: TodayTaskBucket,
        dueAt: Long = 0L
    ) = TodayCareTask(
        id = id,
        plant = plant,
        kind = kind,
        dueAt = dueAt,
        bucket = bucket
    )
}
