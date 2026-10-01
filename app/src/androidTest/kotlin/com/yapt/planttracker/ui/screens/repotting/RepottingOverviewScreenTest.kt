package com.yapt.planttracker.ui.screens.repotting

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.data.preferences.RepottingOverviewPreferences
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.repotting.RepottingOverviewThreshold
import com.yapt.planttracker.domain.schedule.SeasonalRepotting
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab
import com.yapt.planttracker.ui.screens.plantdetail.REPOT_PLAN_DIALOG_TEST_TAG
import com.yapt.planttracker.ui.util.labelRes
import com.yapt.planttracker.ui.util.repotPlanLabelRes
import com.yapt.planttracker.util.DateUtils
import com.yapt.planttracker.util.toStartOfDayMillis
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class RepottingOverviewScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val today = LocalDate.now()
    private val plants = MutableStateFlow<List<Plant>>(emptyList())
    private val lastRepot = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private val threshold = MutableStateFlow(RepottingOverviewThreshold.TWO_YEARS)
    private val plantRepository = mockk<PlantRepository>()
    private val careLogRepository = mockk<CareLogRepository>()
    private val preferences = mockk<RepottingOverviewPreferences>()
    private val quickLogUseCase = mockk<QuickLogUseCase>()

    private fun str(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun plant(
        id: Long,
        name: String,
        createdAt: LocalDate = today.minusYears(5),
        room: String? = null,
        planStart: Long? = null
    ) = Plant(
        id = id,
        name = name,
        room = room,
        createdAt = createdAt.toStartOfDayMillis(),
        updatedAt = 0L,
        repotPlanSeasonStartAt = planStart,
        repotPlanMadeAt = planStart?.let { 1L }
    )

    private fun setContent(
        onNavigateBack: () -> Unit = {},
        onNavigateToPlant: (Long, PlantDetailTab) -> Unit = { _, _ -> }
    ) {
        every { plantRepository.getAllPlants() } returns plants
        every { plantRepository.getPlantById(any()) } answers {
            val id = firstArg<Long>()
            plants.map { list -> list.firstOrNull { it.id == id } }
        }
        coEvery { plantRepository.setRepotPlan(any(), any(), any(), any()) } answers {
            val id = firstArg<Long>()
            val start = secondArg<Long>()
            plants.value = plants.value.map {
                if (it.id == id) it.copy(repotPlanSeasonStartAt = start, repotPlanMadeAt = 1L) else it
            }
        }
        coEvery { plantRepository.clearRepotPlan(any(), any()) } answers {
            val id = firstArg<Long>()
            plants.value = plants.value.map {
                if (it.id == id) it.copy(repotPlanSeasonStartAt = null, repotPlanMadeAt = null) else it
            }
        }
        every { careLogRepository.observeLastCareAtByPlant(CareType.REPOT) } returns lastRepot
        every { preferences.threshold } returns threshold
        coEvery { preferences.setThreshold(any()) } answers { threshold.value = firstArg() }
        val viewModel = RepottingOverviewViewModel(
            plantRepository = plantRepository,
            careLogRepository = careLogRepository,
            preferences = preferences,
            quickLogUseCase = quickLogUseCase,
            dayChange = MutableStateFlow(today)
        )
        composeTestRule.setContent {
            RepottingOverviewScreen(
                viewModel = viewModel,
                onNavigateBack = onNavigateBack,
                onNavigateToPlant = onNavigateToPlant
            )
        }
    }

    private fun rowTag(id: Long) = repottingRowTag(id)

    private fun menuItem(label: String) = hasText(label).and(hasAnyAncestor(isPopup()))

    private fun openMenu(id: Long) {
        composeTestRule.onNodeWithTag(rowTag(id)).performSemanticsAction(SemanticsActions.OnLongClick)
    }

    private fun chooseMenuItem(id: Long, label: String) {
        openMenu(id)
        composeTestRule.onNode(menuItem(label)).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun upcoming() = SeasonalRepotting.upcomingSeasons(today, SeasonalWatering.currentHemisphere())

    private fun plannedLabel(index: Int) = upcoming()[index].let { str(it.season.repotPlanLabelRes(), it.year) }

    // ---- Chips ----

    @Test
    fun chipRowOffersNeverAndTheYearChipsWithTwoYearsSelectedByDefault() {
        plants.value = listOf(plant(1, "Fern"))
        setContent()

        composeTestRule.onNodeWithText("Never").assertIsDisplayed()
        composeTestRule.onNodeWithText("1+ yr").assertIsDisplayed()
        composeTestRule.onNodeWithText("2+ yr").assertIsSelected()
        composeTestRule.onNodeWithText("3+ yr").assertIsDisplayed()
    }

    @Test
    fun selectingAChipSavesItAndTheHeaderNamesIt() {
        plants.value = listOf(plant(1, "Fern"))
        setContent()
        composeTestRule.onNodeWithText("Not repotted in 2+ years").assertIsDisplayed()

        composeTestRule.onNodeWithText("Never").performClick()

        coVerify(timeout = 5000) { preferences.setThreshold(RepottingOverviewThreshold.NEVER) }
        composeTestRule.onNodeWithText("Never").assertIsSelected()
        composeTestRule.onNodeWithText("Never repotted").assertIsDisplayed()
    }

    @Test
    fun aSavedChipIsSelectedWhenThePageOpens() {
        threshold.value = RepottingOverviewThreshold.THREE_YEARS
        plants.value = listOf(plant(1, "Fern"))
        setContent()

        composeTestRule.onNodeWithText("3+ yr").assertIsSelected()
        composeTestRule.onNodeWithText("Not repotted in 3+ years").assertIsDisplayed()
    }

    // ---- Rows ----

    @Test
    fun thresholdRowsShowPhotoNameRoomAndTheLastRepottedOrNeverRepottedLine() {
        val repotted = today.minusYears(3)
        plants.value = listOf(
            plant(1, "Fern", room = "Kitchen"),
            plant(2, "Aloe", createdAt = today.minusYears(4))
        )
        lastRepot.value = mapOf(1L to repotted.toStartOfDayMillis())
        setContent()

        composeTestRule.onNodeWithText("Fern").assertIsDisplayed()
        composeTestRule.onNodeWithText("Kitchen").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            str(R.string.repotting_overview_last_repotted, DateUtils.formatMonthYear(repotted.toStartOfDayMillis()))
        ).assertIsDisplayed()
        composeTestRule.onNodeWithText("Aloe").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            str(
                R.string.repotting_overview_never_repotted,
                DateUtils.formatMonthYear(today.minusYears(4).toStartOfDayMillis())
            )
        ).assertIsDisplayed()
    }

    @Test
    fun aPlantAddedRecentlyIsNotUnderAYearChipButIsUnderNever() {
        plants.value = listOf(plant(1, "Seedling", createdAt = today.minusDays(10)))
        setContent()

        composeTestRule.onNodeWithText("Seedling").assertDoesNotExist()
        composeTestRule.onNodeWithText("Never").performClick()

        composeTestRule.onNodeWithText("Seedling").assertIsDisplayed()
    }

    @Test
    fun plannedGroupSitsAboveTheListLabelledWithSeasonAndYearAndEndedPlansAreOverdue() {
        val upcoming = upcoming()
        val endedStart = LocalDate.now().minusYears(1).withMonth(6).withDayOfMonth(1).toStartOfDayMillis()
        plants.value = listOf(
            plant(1, "Fern", planStart = upcoming[1].startAtMillis),
            plant(2, "Stale", planStart = endedStart),
            plant(3, "Aloe")
        )
        setContent()

        composeTestRule.onNodeWithText("Planned").assertIsDisplayed()
        composeTestRule.onNodeWithText(plannedLabel(1)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Overdue", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Aloe").assertIsDisplayed()
        assertTrue(topOf("Planned") < topOf("Fern"))
        assertTrue(topOf("Fern") < topOf("Not repotted in 2+ years"))
        assertTrue(topOf("Not repotted in 2+ years") < topOf("Aloe"))
    }

    @Test
    fun plannedPlantsStayUnderEveryChipAndLeaveTheThresholdList() {
        plants.value = listOf(plant(1, "Fern", planStart = upcoming()[0].startAtMillis), plant(2, "Aloe"))
        setContent()

        composeTestRule.onNodeWithText("Never").performClick()

        composeTestRule.onNodeWithText("Planned").assertIsDisplayed()
        composeTestRule.onNodeWithText(plannedLabel(0)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Aloe").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(rowTag(1)).assertCountEquals(1)
    }

    // ---- Empty states ----

    @Test
    fun withNoActivePlantsThePageAsksToAddOne() {
        setContent()

        composeTestRule.onNodeWithText(str(R.string.repotting_overview_no_plants)).assertIsDisplayed()
        composeTestRule.onNodeWithText("2+ yr").assertDoesNotExist()
    }

    @Test
    fun whenNothingMatchesAndNothingIsPlannedItSaysSo() {
        plants.value = listOf(plant(1, "Fern", createdAt = today.minusDays(3)))
        setContent()

        composeTestRule.onNodeWithText(str(R.string.repotting_overview_nothing_here)).assertIsDisplayed()
        composeTestRule.onNodeWithText("2+ yr").assertIsSelected()
    }

    @Test
    fun whenNothingMatchesButPlansExistThePlannedGroupAndAShortNoteShow() {
        plants.value = listOf(
            plant(1, "Fern", planStart = upcoming()[0].startAtMillis),
            plant(2, "Sprout", createdAt = today.minusDays(3))
        )
        setContent()

        composeTestRule.onNodeWithText("Planned").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not repotted in 2+ years").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.repotting_overview_no_other_matches)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.repotting_overview_nothing_here)).assertDoesNotExist()
        composeTestRule.onNodeWithText("Sprout").assertDoesNotExist()
    }

    // ---- Navigation ----

    @Test
    fun backArrowNavigatesBack() {
        var backs = 0
        setContent(onNavigateBack = { backs++ })

        composeTestRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, backs)
    }

    @Test
    fun tappingARowOpensPlantDetailOnTheRepotTab() {
        plants.value = listOf(plant(7, "Fern"))
        var opened: Pair<Long, PlantDetailTab>? = null
        setContent(onNavigateToPlant = { id, tab -> opened = id to tab })

        composeTestRule.onNodeWithTag(rowTag(7)).performClick()
        composeTestRule.waitForIdle()

        assertEquals(7L to PlantDetailTab.REPOT, opened)
    }

    // ---- Long-press menu ----

    @Test
    fun longPressOpensTheMenuInsteadOfOpeningThePlant() {
        plants.value = listOf(plant(1, "Fern"))
        var opened: Long? = null
        setContent(onNavigateToPlant = { id, _ -> opened = id })

        composeTestRule.onNodeWithTag(rowTag(1)).performTouchInput { longClick() }

        composeTestRule.onNode(menuItem("Repot…")).assertIsDisplayed()
        composeTestRule.onNode(menuItem("Plan repot…")).assertIsDisplayed()
        composeTestRule.onNode(menuItem("Change plan…")).assertDoesNotExist()
        composeTestRule.onNode(menuItem("Clear plan")).assertDoesNotExist()
        assertNull(opened)
    }

    @Test
    fun aPlannedRowMenuOffersRepotChangePlanAndClearPlan() {
        plants.value = listOf(plant(1, "Fern", planStart = upcoming()[0].startAtMillis))
        setContent()

        openMenu(1)

        composeTestRule.onNode(menuItem("Repot…")).assertIsDisplayed()
        composeTestRule.onNode(menuItem("Change plan…")).assertIsDisplayed()
        composeTestRule.onNode(menuItem("Clear plan")).assertIsDisplayed()
        composeTestRule.onNode(menuItem("Plan repot…")).assertDoesNotExist()
    }

    @Test
    fun rowsExposeALongClickLabelAndTheMenuActionsAsCustomActions() {
        plants.value = listOf(plant(1, "Fern"), plant(2, "Yucca", planStart = upcoming()[0].startAtMillis))
        setContent()

        val plain = composeTestRule.onNodeWithTag(rowTag(1)).fetchSemanticsNode().config
        val planned = composeTestRule.onNodeWithTag(rowTag(2)).fetchSemanticsNode().config

        assertEquals("Show actions", plain[SemanticsActions.OnLongClick].label)
        assertEquals(listOf("Repot…", "Plan repot…"), plain[SemanticsActions.CustomActions].map { it.label })
        assertEquals(
            listOf("Repot…", "Change plan…", "Clear plan"),
            planned[SemanticsActions.CustomActions].map { it.label }
        )
    }

    // ---- Actions ----

    @Test
    fun repotOpensTheExistingDatePickerAndLogsARepotThroughQuickLog() {
        val fern = plant(1, "Fern")
        plants.value = listOf(fern)
        coEvery { quickLogUseCase.quickLog(fern, CareType.REPOT, any()) } coAnswers {
            lastRepot.value = mapOf(1L to System.currentTimeMillis())
            QuickLogUseCase.QuickLogOutcome(message = "Repotted Fern", logged = true)
        }
        setContent()

        chooseMenuItem(1, "Repot…")
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(REPOTTING_OVERVIEW_REPOT_DATE_PICKER_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        coVerify(exactly = 0) { quickLogUseCase.quickLog(any(), any(), any()) }
        composeTestRule.onNodeWithText(str(R.string.ok)).performClick()

        coVerify(timeout = 5000) { quickLogUseCase.quickLog(fern, CareType.REPOT, any()) }
        waitUntilShown("Repotted Fern")
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(rowTag(1)).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
    }

    @Test
    fun dismissingTheRepotDatePickerLogsNothing() {
        plants.value = listOf(plant(1, "Fern"))
        setContent()

        chooseMenuItem(1, "Repot…")
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(REPOTTING_OVERVIEW_REPOT_DATE_PICKER_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText(str(R.string.cancel)).performClick()
        composeTestRule.waitForIdle()

        coVerify(exactly = 0) { quickLogUseCase.quickLog(any(), any(), any()) }
    }

    @Test
    fun planRepotOpensTheSeasonDialogAndMovesThePlantIntoThePlannedGroup() {
        plants.value = listOf(plant(1, "Fern"))
        setContent()
        val chosen = upcoming()[1]

        chooseMenuItem(1, "Plan repot…")
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(REPOT_PLAN_DIALOG_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        coVerify(exactly = 0) { plantRepository.setRepotPlan(any(), any(), any(), any()) }
        composeTestRule.onNodeWithText("${str(chosen.season.labelRes())} ${chosen.year}").performClick()

        coVerify(timeout = 5000) { plantRepository.setRepotPlan(1L, chosen.startAtMillis, any(), any()) }
        waitUntilShown(str(R.string.repotting_overview_plan_saved, "Fern"))
        waitUntilShown("Planned")
        composeTestRule.onNodeWithText(plannedLabel(1)).assertIsDisplayed()
    }

    @Test
    fun changePlanReplacesTheSeason() {
        plants.value = listOf(plant(1, "Fern", planStart = upcoming()[0].startAtMillis))
        setContent()
        val replacement = upcoming()[2]

        chooseMenuItem(1, "Change plan…")
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(REPOT_PLAN_DIALOG_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText("${str(replacement.season.labelRes())} ${replacement.year}").performClick()

        coVerify(timeout = 5000) { plantRepository.setRepotPlan(1L, replacement.startAtMillis, any(), any()) }
        waitUntilShown(plannedLabel(2))
    }

    @Test
    fun clearPlanRemovesItAndTheRowReturnsToTheThresholdList() {
        plants.value = listOf(plant(1, "Fern", planStart = upcoming()[0].startAtMillis))
        setContent()
        composeTestRule.onNodeWithText("Planned").assertIsDisplayed()

        chooseMenuItem(1, "Clear plan")

        coVerify(timeout = 5000) { plantRepository.clearRepotPlan(1L, any()) }
        waitUntilShown(str(R.string.repotting_overview_plan_cleared, "Fern"))
        composeTestRule.onNodeWithText("Planned").assertDoesNotExist()
        composeTestRule.onNodeWithText("Fern").assertIsDisplayed()
    }

    @Test
    fun customActionsDispatchTheSameHandlersAsTheMenu() {
        plants.value = listOf(plant(1, "Fern", planStart = upcoming()[0].startAtMillis))
        setContent()

        val actions = composeTestRule.onNodeWithTag(rowTag(1)).fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]
        composeTestRule.runOnUiThread { assertTrue(actions.first { it.label == "Clear plan" }.action()) }

        coVerify(timeout = 5000) { plantRepository.clearRepotPlan(1L, any()) }
        composeTestRule.runOnUiThread { assertTrue(actions.first { it.label == "Repot…" }.action()) }
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(REPOTTING_OVERVIEW_REPOT_DATE_PICKER_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    private fun waitUntilShown(text: String) {
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    private fun topOf(text: String): Float =
        composeTestRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y
}
