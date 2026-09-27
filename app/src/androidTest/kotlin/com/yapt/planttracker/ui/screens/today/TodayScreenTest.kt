package com.yapt.planttracker.ui.screens.today

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodayScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val queue = MutableStateFlow(TodayQueueSnapshot(0, emptyList()))
    private val grouped = MutableStateFlow(false)

    @Test
    fun noPlantsShowsFirstUseAction() {
        var addCalls = 0
        setContent(onNavigateToAdd = { addCalls++ })

        composeTestRule.onNodeWithText("Add your first plant to see its care plan here.").assertIsDisplayed()
        composeTestRule.onNodeWithText("Add plant").performClick()
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
                task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Today),
                task("photo:1", fern, TodayCareKind.PHOTO, TodayTaskBucket.Today)
            )
        )
        setContent()
        composeTestRule.onAllNodesWithText("Fern").assertCountEquals(2)

        grouped.value = true

        composeTestRule.onAllNodesWithText("Fern").assertCountEquals(1)
        composeTestRule.onNodeWithContentDescription("Select all care tasks for Fern").assertIsDisplayed()
        composeTestRule.onNodeWithText("Take photo").assertIsDisplayed()
    }

    private fun setContent(
        onNavigateToPlant: (Long) -> Unit = {},
        onNavigateToAdd: () -> Unit = {}
    ) {
        val repository = mockk<TodayCareRepository>()
        val featureFlags = mockk<FeatureFlags>()
        every { repository.observeQueue() } returns queue
        every { featureFlags.isEnabled(FeatureFlagRegistry.TODAY_GROUP_BY_PLANT) } returns grouped
        val viewModel = TodayViewModel(
            application = ApplicationProvider.getApplicationContext<Application>(),
            todayCareRepository = repository,
            featureFlags = featureFlags,
            quickLogUseCase = mockk<QuickLogUseCase>(relaxed = true),
            plantRepository = mockk<PlantRepository>(relaxed = true),
            careLogRepository = mockk<CareLogRepository>(relaxed = true),
            plantPhotoRepository = mockk<PlantPhotoRepository>(relaxed = true)
        )
        composeTestRule.setContent {
            TodayScreen(
                viewModel = viewModel,
                onNavigateToPlant = onNavigateToPlant,
                onNavigateToAdd = onNavigateToAdd
            )
        }
    }

    private fun plant() = Plant(id = 1L, name = "Fern", createdAt = 0L, updatedAt = 0L)

    private fun task(
        id: String,
        plant: Plant,
        kind: TodayCareKind,
        bucket: TodayTaskBucket
    ) = TodayCareTask(id = id, plant = plant, kind = kind, dueAt = 0L, bucket = bucket)
}
