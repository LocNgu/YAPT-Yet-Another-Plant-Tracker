package com.yapt.planttracker.ui.screens.today

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.TodayCareRepository
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayQueueSnapshot
import com.yapt.planttracker.domain.today.TodayTaskBucket
import com.yapt.planttracker.domain.today.WateringTaskAction
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.toStartOfDayMillis
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class TodayScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val queue = MutableStateFlow(TodayQueueSnapshot(0, emptyList()))
    private val repository = mockk<TodayCareRepository>()
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
    fun careTypeSectionsListWateringFirstAndHideEmptySections() {
        val fern = plant()
        val aloe = plant(2L, "Aloe")
        queue.value = TodayQueueSnapshot(
            activePlantCount = 2,
            tasks = listOf(
                task("photo:1", fern, TodayCareKind.PHOTO),
                task("repot:1", fern, TodayCareKind.REPOT),
                task("water:2", aloe, TodayCareKind.WATER, TodayTaskBucket.Overdue)
            )
        )
        setContent(tall = true)

        composeTestRule.onNodeWithText("Watering · 1").assertExists()
        composeTestRule.onNodeWithText("Repotting · 1").assertExists()
        composeTestRule.onNodeWithText("Photos · 1").assertExists()
        composeTestRule.onNodeWithText("Fertilizing", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Issue treatments", substring = true).assertDoesNotExist()
        assertTrue(topOf("Watering · 1") < topOf("Repotting · 1"))
        assertTrue(topOf("Repotting · 1") < topOf("Photos · 1"))
    }

    @Test
    fun wateringSplitsIntoOverdueTodayAndNextThreeDaysAndHidesEmptySubGroups() {
        val fern = plant()
        val aloe = plant(2L, "Aloe")
        val cactus = plant(3L, "Cactus")
        queue.value = TodayQueueSnapshot(
            3,
            listOf(
                task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Overdue),
                task("water:2", aloe, TodayCareKind.WATER),
                task("water:3", cactus, TodayCareKind.WATER, TodayTaskBucket.Upcoming(LocalDate.now().toEpochDay() + 2))
            )
        )
        setContent(tall = true)

        composeTestRule.onNodeWithText("Watering · 3").assertExists()
        composeTestRule.onNodeWithText("Overdue · 1").assertExists()
        composeTestRule.onNodeWithText("Today · 1").assertExists()
        composeTestRule.onNodeWithText("Next 3 days · 1").assertExists()
        assertTrue(topOf("Overdue · 1") < topOf("Today · 1"))
        assertTrue(topOf("Today · 1") < topOf("Next 3 days · 1"))

        queue.value = TodayQueueSnapshot(1, listOf(task("water:2", aloe, TodayCareKind.WATER)))

        composeTestRule.onNodeWithText("Today · 1").assertExists()
        composeTestRule.onNodeWithText("Overdue · 1").assertDoesNotExist()
        composeTestRule.onNodeWithText("Next 3 days · 1").assertDoesNotExist()
    }

    @Test
    fun headerCountsAreDistinctPlantsAndOtherSectionsStayFlat() {
        val fern = plant()
        val aloe = plant(2L, "Aloe")
        val mist = CustomReminder(id = 4L, plantId = fern.id, name = "Mist leaves", intervalDays = 3)
        val prune = CustomReminder(id = 5L, plantId = fern.id, name = "Prune", intervalDays = 30)
        val feed = CustomReminder(id = 6L, plantId = aloe.id, name = "Top dress", intervalDays = 30)
        queue.value = TodayQueueSnapshot(
            2,
            listOf(
                task("custom:4", fern, TodayCareKind.CUSTOM_REMINDER, customReminder = mist),
                task("custom:5", fern, TodayCareKind.CUSTOM_REMINDER, customReminder = prune),
                task("custom:6", aloe, TodayCareKind.CUSTOM_REMINDER, customReminder = feed)
            )
        )
        setContent()

        composeTestRule.onNodeWithText("Custom reminders · 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Today · 2").assertDoesNotExist()
        composeTestRule.onNodeWithText("Today · 3").assertDoesNotExist()
    }

    @Test
    fun combinedWaterAndFertilizeTaskSitsUnderWatering() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(
            1,
            listOf(task("water_fertilize:1", fern, TodayCareKind.WATER_AND_FERTILIZE))
        )
        setContent()

        composeTestRule.onNodeWithText("Watering · 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fertilizing", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Fern, watering and fertilizing").assertIsDisplayed()
        composeTestRule.onNodeWithText("Water & fertilize").assertIsDisplayed()
    }

    @Test
    fun sectionAndSubGroupHeadersCollapseAndExposeTheirStateToScreenReaders() {
        val fern = plant()
        val aloe = plant(2L, "Aloe")
        queue.value = TodayQueueSnapshot(
            2,
            listOf(
                task("water:1", fern, TodayCareKind.WATER),
                task("fertilize:2", aloe, TodayCareKind.FERTILIZE)
            )
        )
        setContent(tall = true)
        composeTestRule.onNodeWithText("Watering · 1").assert(hasStateDescription("Expanded"))
        composeTestRule.onNodeWithContentDescription("Fern, watering").assertExists()

        composeTestRule.onNodeWithText("Today · 1").performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.onNodeWithText("Today · 1").assert(hasStateDescription("Collapsed"))
        composeTestRule.onNodeWithContentDescription("Fern, watering").assertDoesNotExist()
        composeTestRule.onNodeWithText("Watering · 1").assert(hasStateDescription("Expanded"))

        composeTestRule.onNodeWithText("Today · 1").performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithText("Watering · 1").performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.onNodeWithText("Watering · 1").assert(hasStateDescription("Collapsed"))
        composeTestRule.onNodeWithText("Today · 1").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Fern, watering").assertDoesNotExist()
        composeTestRule.onNodeWithText("Fertilizing · 1").assert(hasStateDescription("Expanded"))

        composeTestRule.onNodeWithText("Watering · 1").performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.onNodeWithText("Watering · 1").assert(hasStateDescription("Expanded"))
        composeTestRule.onNodeWithContentDescription("Fern, watering").assertExists()
    }

    @Test
    fun collapsedGroupsStayCollapsedAcrossRecreation() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("fertilize:1", fern, TodayCareKind.FERTILIZE)))
        val restoration = StateRestorationTester(composeTestRule)
        setContent(restoration = restoration)

        composeTestRule.onNodeWithText("Fertilizing · 1").performSemanticsAction(SemanticsActions.OnClick)
        composeTestRule.onNodeWithContentDescription("Fern, fertilizing").assertDoesNotExist()

        restoration.emulateSavedInstanceStateRestore()

        composeTestRule.onNodeWithText("Fertilizing · 1").assert(hasStateDescription("Collapsed"))
        composeTestRule.onNodeWithContentDescription("Fern, fertilizing").assertDoesNotExist()
    }

    @Test
    fun tilesAreSquareTwoPerRowOnANarrowScreenAndMoreOnWiderOnes() {
        val tasks = (1L..4L).map { id ->
            task("water:$id", plant(id, "Plant $id"), TodayCareKind.WATER)
        }
        queue.value = TodayQueueSnapshot(4, tasks)

        setContent(widthDp = 320.dp)
        assertEquals(2, tilesInFirstRow(tasks))
        assertTrue(photoIsSquare("water:1"))
    }

    @Test
    fun widerScreensFitMoreTilesPerRowUpToFour() {
        val tasks = (1L..6L).map { id ->
            task("water:$id", plant(id, "Plant $id"), TodayCareKind.WATER)
        }
        queue.value = TodayQueueSnapshot(6, tasks)

        setContent(widthDp = 900.dp)

        assertEquals(4, tilesInFirstRow(tasks))
    }

    @Test
    fun tilesShowThePlantNameWithoutATaskTypeLabelOrSecondLineForPlainTasks() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("water:1", fern, TodayCareKind.WATER)))
        setContent()

        composeTestRule.onNodeWithText("Fern").assertIsDisplayed()
        composeTestRule.onNodeWithText("Water").assertDoesNotExist()
        composeTestRule.onNodeWithText("Water & fertilize").assertDoesNotExist()
    }

    @Test
    fun secondLineAppearsForRemindersTreatmentsAndCombinedTilesOnly() {
        val fern = plant()
        val reminder = CustomReminder(id = 4L, plantId = fern.id, name = "Mist leaves", intervalDays = 3)
        val treatment = CustomReminder(id = 5L, plantId = fern.id, name = "Neem spray", intervalDays = 7)
        queue.value = TodayQueueSnapshot(
            1,
            listOf(
                task("custom:4", fern, TodayCareKind.CUSTOM_REMINDER, customReminder = reminder),
                task(
                    "custom:5",
                    fern,
                    TodayCareKind.ISSUE_TREATMENT,
                    customReminder = treatment,
                    issueName = "Spider mites"
                )
            )
        )
        setContent()

        composeTestRule.onNodeWithText("Mist leaves").assertExists()
        composeTestRule.onNodeWithText("Treat Spider mites").assertExists()
    }

    @Test
    fun tapOpensPlant() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("water:1", fern, TodayCareKind.WATER)))
        var openedPlantId: Long? = null
        setContent(onNavigateToPlant = { openedPlantId = it })

        composeTestRule.onNodeWithText("Fern").tapRowEdge()
        composeTestRule.waitForIdle()

        assertEquals(fern.id, openedPlantId)
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

        composeTestRule.onNodeWithText("The Care queue couldn’t be loaded.").assertIsDisplayed()
        composeTestRule.onNode(hasText("Retry").and(hasClickAction()))
            .performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.onNodeWithText("You’re all caught up for the next three days.").assertIsDisplayed()
        assertEquals(2, calls)
    }

    @Test
    fun liveQueueUpdateReplacesVisibleTaskControls() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("water:1", fern, TodayCareKind.WATER)))
        setContent()
        composeTestRule.onNodeWithContentDescription("Water Fern").assertIsDisplayed()

        queue.value = TodayQueueSnapshot(1, listOf(task("repot:1", fern, TodayCareKind.REPOT)))

        composeTestRule.onNodeWithContentDescription("Water Fern").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Repot Fern").assertIsDisplayed()
    }

    @Test
    fun waterControlsIdentifyThePlantForScreenReaders() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("water:1", fern, TodayCareKind.WATER)))
        setContent()

        composeTestRule.onNodeWithContentDescription("Water Fern").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Reschedule watering for Fern").assertIsDisplayed()
    }

    @Test
    fun fertilizeControlsIdentifyThePlantForScreenReaders() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("fertilize:1", fern, TodayCareKind.FERTILIZE)))
        setContent()

        composeTestRule.onNodeWithContentDescription("Fertilize Fern").assertIsDisplayed()
    }

    @Test
    fun reminderControlsIdentifyThePlantAndTaskForScreenReaders() {
        val fern = plant()
        val reminder = CustomReminder(id = 4L, plantId = fern.id, name = "Mist leaves", intervalDays = 3)
        queue.value = TodayQueueSnapshot(
            1,
            listOf(task("custom:4", fern, TodayCareKind.CUSTOM_REMINDER, customReminder = reminder))
        )
        setContent()

        composeTestRule.onNodeWithContentDescription("Done: Mist leaves for Fern").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Fern, Mist leaves").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mist leaves").assertIsDisplayed()
    }

    @Test
    fun treatmentControlsIdentifyThePlantAndTaskForScreenReaders() {
        val fern = plant()
        val treatment = CustomReminder(id = 5L, plantId = fern.id, name = "Neem spray", intervalDays = 7)
        queue.value = TodayQueueSnapshot(
            1,
            listOf(
                task(
                    "custom:5",
                    fern,
                    TodayCareKind.ISSUE_TREATMENT,
                    customReminder = treatment,
                    issueName = "Spider mites"
                )
            )
        )
        setContent()

        composeTestRule.onNodeWithContentDescription("Done: Treat Spider mites for Fern").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Fern, Treat Spider mites").assertIsDisplayed()
        composeTestRule.onNodeWithText("Treat Spider mites").assertIsDisplayed()
    }

    @Test
    fun overdueTilesAreAnnouncedAsOverdueInEverySection() {
        val fern = plant()
        val aloe = plant(2L, "Aloe")
        queue.value = TodayQueueSnapshot(
            2,
            listOf(
                task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Overdue),
                task("water:2", aloe, TodayCareKind.WATER),
                task("fertilize:1", fern, TodayCareKind.FERTILIZE, TodayTaskBucket.Overdue)
            )
        )
        setContent(tall = true)

        composeTestRule.onNodeWithContentDescription("Overdue, Fern, watering").assertExists()
        composeTestRule.onNodeWithContentDescription("Aloe, watering").assertExists()
        composeTestRule.onNodeWithContentDescription("Overdue, Aloe, watering").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Overdue, Fern, fertilizing").assertExists()
    }

    @Test
    fun customActionDispatchesTheExistingFlow() {
        val fern = plant()
        val reminder = CustomReminder(id = 4L, plantId = fern.id, name = "Neem", intervalDays = 7)
        val custom = task("custom:4", fern, TodayCareKind.CUSTOM_REMINDER, customReminder = reminder)
        queue.value = TodayQueueSnapshot(1, listOf(custom))
        coEvery { quickLogUseCase.completeCustomReminder(custom) } returns
            QuickLogUseCase.QuickLogOutcome("done", logged = true)
        setContent()

        composeTestRule.onNodeWithContentDescription("Done: Neem for Fern").performClick()

        coVerify { quickLogUseCase.completeCustomReminder(custom) }
    }

    @Test
    fun repotActionOpensTheExistingDatePicker() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("repot:1", fern, TodayCareKind.REPOT)))
        setContent()

        composeTestRule.onNodeWithContentDescription("Repot Fern").performClick()

        composeTestRule.onNodeWithTag("today_repot_date_picker").assertIsDisplayed()
    }

    @Test
    fun waterActionDispatchesAndShowsUseCaseFeedback() {
        val fern = plant()
        val water = task("water:1", fern, TodayCareKind.WATER)
        queue.value = TodayQueueSnapshot(1, listOf(water))
        coEvery { quickLogUseCase.quickWaterWithReason(fern, null, any()) } returns
            QuickLogUseCase.QuickLogOutcome("Watered Fern", logged = true)
        setContent()

        composeTestRule.onNodeWithContentDescription("Water Fern").performClick()

        composeTestRule.onNodeWithText("Watered Fern").assertIsDisplayed()
        coVerify(exactly = 1) { quickLogUseCase.quickWaterWithReason(fern, null, any()) }
    }

    @Test
    fun photoActionDispatchesTheLaunchTimePlantIdentity() {
        val fern = plant()
        val photo = task("photo:1", fern, TodayCareKind.PHOTO)
        queue.value = TodayQueueSnapshot(1, listOf(photo))
        var launchedPlantId: Long? = null
        setContent(onLaunchPhotoCapture = { launchedPlantId = it })

        composeTestRule.onNodeWithContentDescription("Take a progress photo of Fern").performClick()

        assertEquals(fern.id, launchedPlantId)
    }

    @Test
    fun reasonSheetSurvivesRecreationAndDoesNotReappearOnceItsTaskIsGone() {
        val fern = plant()
        val offSchedule = WateringTaskAction(
            isOnSchedule = false,
            isGapLong = false,
            isDormancySpanning = false,
            computedDueAt = null,
            effectiveDueAt = null
        )
        val water = task("water:1", fern, TodayCareKind.WATER, wateringAction = offSchedule)
        queue.value = TodayQueueSnapshot(1, listOf(water))
        val restoration = StateRestorationTester(composeTestRule)
        setContent(restoration = restoration)

        composeTestRule.onNodeWithContentDescription("Water Fern").performClick()
        composeTestRule.onNodeWithText("Water Fern?").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.onNodeWithText("Water Fern?").assertIsDisplayed()

        queue.value = TodayQueueSnapshot(1, emptyList())
        composeTestRule.onNodeWithText("Water Fern?").assertDoesNotExist()

        queue.value = TodayQueueSnapshot(1, listOf(water))
        composeTestRule.onNodeWithContentDescription("Water Fern").assertIsDisplayed()
        composeTestRule.onNodeWithText("Water Fern?").assertDoesNotExist()
    }

    @Test
    fun tilesShowNoDueDateText() {
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
                ),
                task(
                    "fertilize:1",
                    fern,
                    TodayCareKind.FERTILIZE,
                    TodayTaskBucket.Overdue,
                    today.minusDays(1).toStartOfDayMillis()
                )
            )
        )
        setContent(tall = true)

        composeTestRule.onNodeWithText("Tomorrow").assertDoesNotExist()
        composeTestRule.onNodeWithText("In 3 days").assertDoesNotExist()
        composeTestRule.onNodeWithText("Yesterday").assertDoesNotExist()
    }

    private fun topOf(text: String): Float =
        composeTestRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    private fun hasStateDescription(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun tilesInFirstRow(tasks: List<TodayCareTask>): Int {
        val tops = tasks.map { composeTestRule.onNodeWithTag(careTileTag(it.id)).fetchSemanticsNode().positionInRoot.y }
        return tops.count { it == tops.first() }
    }

    private fun photoIsSquare(taskId: String): Boolean {
        val size = composeTestRule.onNodeWithTag(careTilePhotoTag(taskId), useUnmergedTree = true)
            .fetchSemanticsNode().size
        return size.width > 0 && size.width == size.height
    }

    private fun setContent(
        onNavigateToPlant: (Long) -> Unit = {},
        onNavigateToAdd: () -> Unit = {},
        onLaunchPhotoCapture: ((Long) -> Unit)? = null,
        stubRepository: Boolean = true,
        restoration: StateRestorationTester? = null,
        widthDp: Dp? = null,
        tall: Boolean = false
    ): TodayViewModel {
        if (stubRepository) every { repository.observeQueue() } returns queue
        val viewModel = TodayViewModel(
            application = ApplicationProvider.getApplicationContext<Application>(),
            todayCareRepository = repository,
            quickLogUseCase = quickLogUseCase,
            plantRepository = mockk<PlantRepository>(relaxed = true)
        )
        val content: @Composable () -> Unit = {
            // A viewport taller than the window composes every group, so tests that only check
            // existence/order need not scroll. requiredHeight centres the overflow, so nothing in a
            // tall test may assertIsDisplayed or touch-click; order/state checks use positions and
            // semantics actions instead.
            var frame: Modifier = Modifier
            if (widthDp != null) frame = frame.requiredWidth(widthDp)
            if (tall) frame = frame.requiredHeight(TALL_VIEWPORT)
            Box(frame) {
                TodayScreen(
                    viewModel = viewModel,
                    onNavigateToPlant = onNavigateToPlant,
                    onNavigateToAdd = onNavigateToAdd,
                    onLaunchPhotoCapture = onLaunchPhotoCapture
                )
            }
        }
        if (restoration != null) restoration.setContent(content) else composeTestRule.setContent(content)
        return viewModel
    }

    private companion object {
        val TALL_VIEWPORT = 2400.dp
    }

    private fun plant(id: Long = 1L, name: String = "Fern") =
        Plant(id = id, name = name, createdAt = 0L, updatedAt = 0L)

    @Suppress("LongParameterList")
    private fun task(
        id: String,
        plant: Plant,
        kind: TodayCareKind,
        bucket: TodayTaskBucket = TodayTaskBucket.Today,
        dueAt: Long = System.currentTimeMillis(),
        wateringAction: WateringTaskAction? = null,
        customReminder: CustomReminder? = null,
        issueName: String? = null
    ) = TodayCareTask(
        id = id,
        plant = plant,
        kind = kind,
        dueAt = dueAt,
        bucket = bucket,
        wateringAction = wateringAction,
        customReminder = customReminder,
        issueName = issueName
    )
}
