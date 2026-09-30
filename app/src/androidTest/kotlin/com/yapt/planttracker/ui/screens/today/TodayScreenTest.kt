package com.yapt.planttracker.ui.screens.today

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.TodayCareRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayQueueSnapshot
import com.yapt.planttracker.domain.today.TodayTaskBucket
import com.yapt.planttracker.domain.today.WateringTaskAction
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab
import com.yapt.planttracker.util.toStartOfDayMillis
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    }

    @Test
    fun combinedTileShowsAFertilizeBadgeOnThePhotoInsteadOfATextLabel() {
        val fern = plant()
        val aloe = plant(2L, "Aloe")
        queue.value = TodayQueueSnapshot(
            2,
            listOf(
                task("water_fertilize:1", fern, TodayCareKind.WATER_AND_FERTILIZE),
                task("water:2", aloe, TodayCareKind.WATER)
            )
        )
        setContent()

        // The badge is decorative, so it lives only in the unmerged tree (like the photo container).
        composeTestRule.onNodeWithTag(careTileFertilizeBadgeTag("water_fertilize:1"), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(careTileFertilizeBadgeTag("water:2"), useUnmergedTree = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithText("Water & fertilize").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Fern, watering and fertilizing").assertIsDisplayed()
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
    fun secondLineAppearsForRemindersAndTreatments() {
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
    fun plannedRepotTileNamesItsSeasonAndAnIntervalRepotDoesNot() {
        val fern = plant()
        val aloe = plant(id = 2L, name = "Aloe")
        queue.value = TodayQueueSnapshot(
            2,
            listOf(
                task("repot:1", fern, TodayCareKind.REPOT, repotPlanSeason = FertilizingSeason.SPRING),
                task("repot:2", aloe, TodayCareKind.REPOT)
            )
        )
        setContent()

        composeTestRule.onNodeWithContentDescription("Fern, repotting, Planned for spring").assertExists()
        composeTestRule.onNodeWithText("Planned for spring").assertExists()
        composeTestRule.onNodeWithContentDescription("Aloe, repotting").assertExists()
    }

    @Test
    fun plannedRepotLineIsNotCutOffOnTheNarrowestTileAtLargerFontScale() {
        // 468dp is the narrowest window that fits three columns, so every tile sits at its 140dp
        // minimum width — the tightest a tile ever gets. At 1.3x font each season's line must render
        // in full (it may wrap within its two lines, but never ellipsize).
        val tasks = FertilizingSeason.entries.mapIndexed { index, season ->
            val id = index + 1L
            task("repot:$id", plant(id, "Monstera deliciosa $id"), TodayCareKind.REPOT, repotPlanSeason = season)
        }
        queue.value = TodayQueueSnapshot(tasks.size, tasks)
        setContent(widthDp = 468.dp, fontScale = 1.3f)

        for (label in listOf("Planned for spring", "Planned for summer", "Planned for autumn", "Planned for winter")) {
            assertShownInFull(label)
        }
    }

    @Test
    fun tapOpensPlantAndDoesNotOpenTheMenu() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("water:1", fern, TodayCareKind.WATER)))
        var openedPlantId: Long? = null
        setContent(onNavigateToPlant = { id, _ -> openedPlantId = id })

        composeTestRule.onNodeWithTag(careTileTag("water:1")).performClick()
        composeTestRule.waitForIdle()

        assertEquals(fern.id, openedPlantId)
        composeTestRule.onNode(menuItem("Reschedule")).assertDoesNotExist()
    }

    @Test
    fun tapOnPhotoTileOpensPhotoTab() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("photo:1", fern, TodayCareKind.PHOTO)))
        var openedTab: PlantDetailTab? = null
        setContent(onNavigateToPlant = { _, tab -> openedTab = tab })

        composeTestRule.onNodeWithTag(careTileTag("photo:1")).performClick()
        composeTestRule.waitForIdle()

        assertEquals(PlantDetailTab.PHOTO, openedTab)
    }

    @Test
    fun tapOnIssueTreatmentTileOpensIssuesTab() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("issue:1", fern, TodayCareKind.ISSUE_TREATMENT)))
        var openedTab: PlantDetailTab? = null
        setContent(onNavigateToPlant = { _, tab -> openedTab = tab })

        composeTestRule.onNodeWithTag(careTileTag("issue:1")).performClick()
        composeTestRule.waitForIdle()

        assertEquals(PlantDetailTab.ISSUES, openedTab)
    }

    @Test
    fun longPressOpensTheMenuInsteadOfOpeningThePlant() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("water:1", fern, TodayCareKind.WATER)))
        var openedPlantId: Long? = null
        setContent(onNavigateToPlant = { id, _ -> openedPlantId = id })

        composeTestRule.onNodeWithTag(careTileTag("water:1")).performTouchInput { longClick() }

        composeTestRule.onNode(menuItem("Water")).assertIsDisplayed()
        composeTestRule.onNode(menuItem("Reschedule")).assertIsDisplayed()
        assertNull(openedPlantId)
    }

    @Test
    fun tileExposesALongClickLabelAndTheMenuActionsAsCustomActionsForScreenReaders() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("water:1", fern, TodayCareKind.WATER)))
        setContent()

        val config = composeTestRule.onNodeWithTag(careTileTag("water:1")).fetchSemanticsNode().config

        assertEquals("Show quick actions", config[SemanticsActions.OnLongClick].label)
        assertEquals(listOf("Water", "Reschedule"), config[SemanticsActions.CustomActions].map { it.label })
    }

    @Test
    fun customActionsDispatchTheSameHandlersAsTheMenu() {
        val fern = plant()
        val water = task("water:1", fern, TodayCareKind.WATER)
        queue.value = TodayQueueSnapshot(1, listOf(water))
        coEvery { quickLogUseCase.quickWaterWithReason(fern, null, any()) } returns
            QuickLogUseCase.QuickLogOutcome("Watered Fern", logged = true)
        setContent()

        val actions = composeTestRule.onNodeWithTag(careTileTag("water:1")).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        composeTestRule.runOnUiThread { assertTrue(actions.first { it.label == "Water" }.action()) }

        composeTestRule.onNodeWithText("Watered Fern").assertIsDisplayed()
        coVerify(exactly = 1) { quickLogUseCase.quickWaterWithReason(fern, null, any()) }
        composeTestRule.runOnUiThread { assertTrue(actions.first { it.label == "Reschedule" }.action()) }
        composeTestRule.onNodeWithText("Reschedule watering").assertIsDisplayed()
    }

    @Test
    fun waterMenuListsOnlyWaterAndReschedule() = assertMenuListsOnly(
        task("water:1", plant(), TodayCareKind.WATER),
        "Water",
        "Reschedule"
    )

    @Test
    fun combinedMenuListsOnlyWaterAndFertilizeAndReschedule() = assertMenuListsOnly(
        task("water_fertilize:1", plant(), TodayCareKind.WATER_AND_FERTILIZE),
        "Water & fertilize",
        "Reschedule"
    )

    @Test
    fun fertilizeMenuListsOnlyFertilize() = assertMenuListsOnly(
        task("fertilize:1", plant(), TodayCareKind.FERTILIZE),
        "Fertilize"
    )

    @Test
    fun repotMenuListsOnlyRepot() = assertMenuListsOnly(task("repot:1", plant(), TodayCareKind.REPOT), "Repot…")

    @Test
    fun reminderMenuListsOnlyMarkDone() = assertMenuListsOnly(
        task("custom:4", plant(), TodayCareKind.CUSTOM_REMINDER, customReminder = reminder()),
        "Mark done"
    )

    @Test
    fun treatmentMenuListsOnlyMarkDone() = assertMenuListsOnly(
        task("custom:4", plant(), TodayCareKind.ISSUE_TREATMENT, customReminder = reminder(), issueName = "Mites"),
        "Mark done"
    )

    @Test
    fun photoMenuListsOnlyTakePhoto() = assertMenuListsOnly(
        task("photo:1", plant(), TodayCareKind.PHOTO),
        "Take photo"
    )

    @Test
    fun liveQueueUpdateReplacesTheVisibleTile() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("water:1", fern, TodayCareKind.WATER)))
        setContent()
        composeTestRule.onNodeWithContentDescription("Fern, watering").assertIsDisplayed()

        queue.value = TodayQueueSnapshot(1, listOf(task("repot:1", fern, TodayCareKind.REPOT)))

        composeTestRule.onNodeWithContentDescription("Fern, watering").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Fern, repotting").assertIsDisplayed()
    }

    @Test
    fun everyTileDescriptionNamesThePlantAndTheTask() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(
            1,
            listOf(
                task("water:1", fern, TodayCareKind.WATER),
                task("water_fertilize:1", fern, TodayCareKind.WATER_AND_FERTILIZE),
                task("fertilize:1", fern, TodayCareKind.FERTILIZE),
                task("repot:1", fern, TodayCareKind.REPOT),
                task("photo:1", fern, TodayCareKind.PHOTO),
                task("custom:4", fern, TodayCareKind.CUSTOM_REMINDER, customReminder = reminder()),
                task(
                    "custom:5",
                    fern,
                    TodayCareKind.ISSUE_TREATMENT,
                    customReminder = reminder(5L, "Neem spray"),
                    issueName = "Spider mites"
                )
            )
        )
        setContent(tall = true)

        listOf(
            "Fern, watering",
            "Fern, watering and fertilizing",
            "Fern, fertilizing",
            "Fern, repotting",
            "Fern, progress photo",
            "Fern, Mist leaves",
            "Fern, Treat Spider mites"
        ).forEach { composeTestRule.onNodeWithContentDescription(it).assertExists() }
    }

    @Test
    fun waterMenuActionDispatchesAndShowsUseCaseFeedback() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("water:1", fern, TodayCareKind.WATER)))
        coEvery { quickLogUseCase.quickWaterWithReason(fern, null, any()) } returns
            QuickLogUseCase.QuickLogOutcome("Watered Fern", logged = true)
        setContent()

        chooseMenuItem("water:1", "Water")

        composeTestRule.onNodeWithText("Watered Fern").assertIsDisplayed()
        coVerify(exactly = 1) { quickLogUseCase.quickWaterWithReason(fern, null, any()) }
        composeTestRule.onNode(menuItem("Reschedule")).assertDoesNotExist()
    }

    @Test
    fun combinedMenuActionDispatchesTheLiquidFertilizeFlow() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("water_fertilize:1", fern, TodayCareKind.WATER_AND_FERTILIZE)))
        coEvery { quickLogUseCase.quickLiquidFertilizeWithReason(fern, null, any()) } returns
            QuickLogUseCase.QuickLogOutcome("Watered and fertilized Fern", logged = true)
        setContent()

        chooseMenuItem("water_fertilize:1", "Water & fertilize")

        composeTestRule.onNodeWithText("Watered and fertilized Fern").assertIsDisplayed()
        coVerify(exactly = 1) { quickLogUseCase.quickLiquidFertilizeWithReason(fern, null, any()) }
    }

    @Test
    fun fertilizeMenuActionDispatchesTheQuickLogFlow() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("fertilize:1", fern, TodayCareKind.FERTILIZE)))
        coEvery { quickLogUseCase.quickLog(fern, CareType.FERTILIZE, any()) } returns
            QuickLogUseCase.QuickLogOutcome("Fertilized Fern", logged = true)
        setContent()

        chooseMenuItem("fertilize:1", "Fertilize")

        composeTestRule.onNodeWithText("Fertilized Fern").assertIsDisplayed()
        coVerify(exactly = 1) { quickLogUseCase.quickLog(fern, CareType.FERTILIZE, any()) }
    }

    @Test
    fun rescheduleMenuActionOpensTheExistingDialog() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("water:1", fern, TodayCareKind.WATER)))
        setContent()

        chooseMenuItem("water:1", "Reschedule")

        composeTestRule.onNodeWithText("Reschedule watering").assertIsDisplayed()
    }

    @Test
    fun markDoneMenuActionDispatchesTheExistingCustomReminderFlow() {
        val fern = plant()
        val custom = task("custom:4", fern, TodayCareKind.CUSTOM_REMINDER, customReminder = reminder(4L, "Neem"))
        queue.value = TodayQueueSnapshot(1, listOf(custom))
        coEvery { quickLogUseCase.completeCustomReminder(custom) } returns
            QuickLogUseCase.QuickLogOutcome("Neem completed", logged = true)
        setContent()

        chooseMenuItem("custom:4", "Mark done")

        composeTestRule.onNodeWithText("Neem completed").assertIsDisplayed()
        coVerify(exactly = 1) { quickLogUseCase.completeCustomReminder(custom) }
    }

    @Test
    fun treatmentMarkDoneUsesTheSameCustomReminderFlow() {
        val fern = plant()
        val treatment = task(
            "custom:5",
            fern,
            TodayCareKind.ISSUE_TREATMENT,
            customReminder = reminder(5L, "Neem spray"),
            issueName = "Spider mites"
        )
        queue.value = TodayQueueSnapshot(1, listOf(treatment))
        coEvery { quickLogUseCase.completeCustomReminder(treatment) } returns
            QuickLogUseCase.QuickLogOutcome("Neem spray completed", logged = true)
        setContent()

        chooseMenuItem("custom:5", "Mark done")

        coVerify(exactly = 1) { quickLogUseCase.completeCustomReminder(treatment) }
    }

    @Test
    fun repotMenuActionOpensTheExistingDatePicker() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("repot:1", fern, TodayCareKind.REPOT)))
        setContent()

        chooseMenuItem("repot:1", "Repot…")

        composeTestRule.onNodeWithTag("today_repot_date_picker").assertIsDisplayed()
    }

    @Test
    fun photoMenuActionDispatchesTheLaunchTimePlantIdentity() {
        val fern = plant()
        queue.value = TodayQueueSnapshot(1, listOf(task("photo:1", fern, TodayCareKind.PHOTO)))
        var launchedPlantId: Long? = null
        setContent(onLaunchPhotoCapture = { launchedPlantId = it })

        chooseMenuItem("photo:1", "Take photo")

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

        chooseMenuItem("water:1", "Water")
        composeTestRule.onNodeWithText("Water Fern?").assertIsDisplayed()

        restoration.emulateSavedInstanceStateRestore()
        composeTestRule.onNodeWithText("Water Fern?").assertIsDisplayed()

        queue.value = TodayQueueSnapshot(1, emptyList())
        composeTestRule.onNodeWithText("Water Fern?").assertDoesNotExist()

        queue.value = TodayQueueSnapshot(1, listOf(water))
        composeTestRule.onNodeWithContentDescription("Fern, watering").assertIsDisplayed()
        composeTestRule.onNodeWithText("Water Fern?").assertDoesNotExist()
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

    private fun reminder(id: Long = 4L, name: String = "Mist leaves") =
        CustomReminder(id = id, plantId = 1L, name = name, intervalDays = 3)

    private fun menuItem(label: String) = hasText(label).and(hasAnyAncestor(isPopup()))

    private fun openMenu(taskId: String) {
        composeTestRule.onNodeWithTag(careTileTag(taskId)).performSemanticsAction(SemanticsActions.OnLongClick)
    }

    private fun chooseMenuItem(taskId: String, label: String) {
        openMenu(taskId)
        composeTestRule.onNode(menuItem(label)).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun assertMenuListsOnly(task: TodayCareTask, vararg labels: String) {
        queue.value = TodayQueueSnapshot(1, listOf(task))
        setContent()

        openMenu(task.id)

        for (label in ALL_MENU_LABELS) {
            if (label in labels) {
                composeTestRule.onNode(menuItem(label)).assertIsDisplayed()
            } else {
                composeTestRule.onNode(menuItem(label)).assertDoesNotExist()
            }
        }
    }

    private fun topOf(text: String): Float =
        composeTestRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y

    // Reads the Text's own layout from the unmerged tree (the tile merges its children's semantics, so
    // the merged node would report whichever text line it saw first). "Shown in full" means every
    // character is laid out and the last line isn't ellipsized — not `hasVisualOverflow`, whose
    // width half reports true for a line that fits whenever the whole-pixel layout width rounds just
    // below the text's fractional measured width.
    private fun assertShownInFull(text: String) {
        val layouts = mutableListOf<TextLayoutResult>()
        composeTestRule.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val lastLine = layout.lineCount - 1
        assertFalse("\"$text\" is ellipsized", layout.isLineEllipsized(lastLine))
        assertEquals("\"$text\" is cut short", text.length, layout.getLineEnd(lastLine))
    }

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
        onNavigateToPlant: (Long, PlantDetailTab?) -> Unit = { _, _ -> },
        onNavigateToAdd: () -> Unit = {},
        onLaunchPhotoCapture: ((Long) -> Unit)? = null,
        stubRepository: Boolean = true,
        restoration: StateRestorationTester? = null,
        widthDp: Dp? = null,
        tall: Boolean = false,
        fontScale: Float? = null
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
            val density = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(density.density, fontScale ?: density.fontScale)
            ) {
                Box(frame) {
                    TodayScreen(
                        viewModel = viewModel,
                        onNavigateToPlant = onNavigateToPlant,
                        onNavigateToAdd = onNavigateToAdd,
                        onLaunchPhotoCapture = onLaunchPhotoCapture
                    )
                }
            }
        }
        if (restoration != null) restoration.setContent(content) else composeTestRule.setContent(content)
        return viewModel
    }

    private companion object {
        val TALL_VIEWPORT = 2400.dp
        val ALL_MENU_LABELS = listOf(
            "Water",
            "Water & fertilize",
            "Fertilize",
            "Reschedule",
            "Repot…",
            "Mark done",
            "Take photo"
        )
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
        issueName: String? = null,
        repotPlanSeason: FertilizingSeason? = null
    ) = TodayCareTask(
        id = id,
        plant = plant,
        kind = kind,
        dueAt = dueAt,
        bucket = bucket,
        wateringAction = wateringAction,
        customReminder = customReminder,
        issueName = issueName,
        repotPlanSeason = repotPlanSeason
    )
}
