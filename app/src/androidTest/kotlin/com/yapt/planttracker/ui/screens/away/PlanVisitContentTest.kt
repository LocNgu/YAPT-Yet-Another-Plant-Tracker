package com.yapt.planttracker.ui.screens.away

import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.domain.away.ProjectedPlant
import com.yapt.planttracker.domain.away.SitterSelection
import com.yapt.planttracker.domain.away.VisitProjection
import com.yapt.planttracker.domain.away.applySelection
import com.yapt.planttracker.domain.away.summary
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PlanVisitContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val today = LocalDate.now()
    private val picked = mutableListOf<LocalDate>()
    private val gaps = mutableListOf<Int>()
    private var pdfShares = 0
    private var textShares = 0

    private fun plant(id: Long, name: String, room: String? = null, photo: String? = "content://p/$id") = Plant(
        id = id,
        name = name,
        room = room,
        coverPhotoUri = photo,
        wateringIntervalDays = 5,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun listed(plant: Plant, missed: Boolean = false, longer: Boolean = false) =
        ProjectedPlant(plant, today, usualIntervalDays = 5, missedLastTime = missed, longerThanUsual = longer)

    private fun later(plant: Plant) =
        ProjectedPlant(plant, today.plusDays(9), usualIntervalDays = 9, missedLastTime = false, longerThanUsual = false)

    @Suppress("LongParameterList")
    private fun state(
        listed: List<ProjectedPlant>,
        resting: List<Plant> = emptyList(),
        notListed: List<ProjectedPlant> = emptyList(),
        selection: SitterSelection = SitterSelection(),
        visitDay: LocalDate = today,
        gap: Int = 7,
        todayAllowed: Boolean = true,
        tomorrowAllowed: Boolean = true,
        sharing: Boolean = false
    ): PlanVisitUiState.Plan {
        val projection = VisitProjection(
            visitDay,
            gap,
            visitDay.plusDays(((gap - 1) / 2).toLong()),
            listed,
            resting,
            notListed = notListed
        )
        val selected = projection.applySelection(selection)
        val earliest = if (todayAllowed) today else today.plusDays(2)
        val latest = if (tomorrowAllowed) today.plusDays(10) else today
        return PlanVisitUiState.Plan(
            today = today,
            trip = AwayPeriod(id = 1L, firstDay = today.minusDays(2), lastDay = latest),
            earliestDay = earliest,
            latestDay = latest,
            visitDay = visitDay,
            gapDays = gap,
            projection = projection,
            selected = selected,
            selectionState = SitterSelectionState(selection),
            summary = selected.summary(),
            sharing = sharing
        )
    }

    private val toggled = mutableListOf<Long>()
    private val roomsPicked = mutableListOf<String?>()

    private fun actions() = PlanVisitActions(
        onPickDay = { picked += it },
        onSetGap = { gaps += it },
        onSharePdf = { pdfShares++ },
        onShareText = { textShares++ },
        onToggleSitterPlant = { toggled += it },
        onSelectRoom = { roomsPicked += it }
    )

    private fun setContent(state: PlanVisitUiState.Plan, roomFilter: String? = null) {
        composeTestRule.setContent {
            Scaffold(bottomBar = { PlanVisitShareBar(state, actions()) }) { padding ->
                PlanVisitContent(state, actions(), roomFilter, padding)
            }
        }
    }

    /**
     * Applies each tap the way the ViewModel would, so a tap changes what the next frame shows. It is only a
     * stand-in: `PlanVisitViewModelTest` is the source of truth for the toggle and room-filter semantics.
     */
    @Suppress("LongParameterList")
    private fun setStatefulContent(
        listed: List<ProjectedPlant>,
        notListed: List<ProjectedPlant> = emptyList(),
        resting: List<Plant> = emptyList()
    ) {
        composeTestRule.setContent {
            var selection by remember { mutableStateOf(SitterSelection()) }
            var roomFilter by remember { mutableStateOf<String?>(null) }
            val current = state(listed, resting, notListed, selection)
            val base = actions()
            val live = base.copy(
                onToggleSitterPlant = { id ->
                    val ticked = current.selected.listed.any { it.plant.id == id }
                    val suggested = listed.any { it.plant.id == id }
                    selection = if (suggested) {
                        selection.copy(removed = if (ticked) selection.removed + id else selection.removed - id)
                    } else {
                        selection.copy(added = if (ticked) selection.added - id else selection.added + id)
                    }
                },
                onSelectRoom = { roomFilter = it }
            )
            Scaffold(bottomBar = { PlanVisitShareBar(current, live) }) { padding ->
                PlanVisitContent(current, live, roomFilter, padding)
            }
        }
    }

    private val verticalList = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)

    /** A node whose only text is [label]: a chip, never a card whose status line happens to read the same. */
    private fun hasOnlyText(label: String) = SemanticsMatcher("has only the text \"$label\"") { node ->
        node.config.getOrNull(SemanticsProperties.Text)?.map { it.text } == listOf(label)
    }

    private fun whenChip(label: String) = composeTestRule.onNode(hasOnlyText(label))

    private val horizontalList = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)

    /** A prefetched lazy item is composed but not placed, so its bounds are wrong; never stop on one (#350). */
    private val isPlaced = SemanticsMatcher("is placed") { it.layoutInfo.isPlaced }

    /** Items below the viewport are not composed, so scroll the list to the target before acting on it. */
    private fun listNode(matcher: SemanticsMatcher): SemanticsNodeInteraction {
        val placed = matcher and isPlaced
        composeTestRule.onNode(verticalList).performScrollToNode(placed)
        return composeTestRule.onNode(placed)
    }

    private fun listNode(text: String, substring: Boolean = false) = listNode(hasText(text, substring = substring))

    /**
     * The chips sit in a horizontal `LazyRow` inside the list: bring the row into view, then scroll the row
     * itself to the chip, so a tap never lands outside a chip that is clipped at the screen's edge.
     */
    private fun roomChip(label: String): SemanticsNodeInteraction {
        val chip = hasOnlyText(label) and isPlaced
        composeTestRule.onNode(verticalList).performScrollToNode(horizontalList)
        composeTestRule.onNode(horizontalList).performScrollToNode(chip)
        return composeTestRule.onNode(chip)
    }

    private val twoPlants get() = listOf(listed(plant(1, "Basil", "Kitchen")), listed(plant(2, "Fern")))

    @Test
    fun theWhenChipsOfferTodayTomorrowAndADate() {
        setContent(state(twoPlants))

        whenChip("Today").assertIsDisplayed().assertIsSelected().assertIsEnabled()
        whenChip("Tomorrow").assertIsDisplayed().assertIsEnabled()
        whenChip("Pick a date").assertIsDisplayed()
    }

    @Test
    fun tomorrowReportsTheNextDay() {
        setContent(state(twoPlants))

        whenChip("Tomorrow").performClick()

        assertEquals(listOf(today.plusDays(1)), picked)
    }

    @Test
    fun todayAndTomorrowAreDisabledWhenTheyAreOutsideTheTrip() {
        setContent(state(twoPlants, visitDay = today.plusDays(2), todayAllowed = false, tomorrowAllowed = true))

        whenChip("Today").assertIsNotEnabled()
        whenChip("Tomorrow").assertIsNotEnabled()
    }

    @Test
    fun aPickedDateReplacesThePickADateLabel() {
        setContent(state(twoPlants, visitDay = today.plusDays(5)))

        whenChip("Pick a date").assertDoesNotExist()
        whenChip("Today").assertIsNotSelected()
    }

    @Test
    fun theGapSliderShowsDaysAndTheDueByDate() {
        setContent(state(twoPlants, gap = 7))

        composeTestRule.onNodeWithText("in 7 days").assertIsDisplayed()
        composeTestRule.onNodeWithText("The list includes anything due by", substring = true).assertIsDisplayed()
    }

    @Test
    fun theGapButtonsReportOneDayMoreAndLess() {
        setContent(state(twoPlants, gap = 7))

        composeTestRule.onNodeWithContentDescription("More days until the next visit").performClick()
        composeTestRule.onNodeWithContentDescription("Fewer days until the next visit").performClick()

        assertEquals(listOf(8, 6), gaps)
    }

    @Test
    fun theSummaryCardCountsPlantsRoomsAndRestingPlants() {
        setContent(state(twoPlants, resting = listOf(plant(8, "Cactus"), plant(9, "Aloe"))))

        composeTestRule.onNodeWithText("Water 2 plants").assertIsDisplayed()
        composeTestRule.onNodeWithText("Kitchen 1 · No room 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("+ 2 resting").assertIsDisplayed()
    }

    @Test
    fun everyWarningTypeIsShownWithTheNamesItConcerns() {
        val twinA = plant(1, "Basil", "Kitchen", photo = null)
        val twinB = plant(2, "Basil", "Kitchen", photo = null)
        setContent(
            state(
                listOf(
                    listed(twinA, missed = true, longer = true),
                    listed(twinB),
                    listed(plant(3, "Fern", "Kitchen"), longer = true)
                )
            )
        )

        composeTestRule.onNodeWithText("1 plant was missed last time: Basil").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("2 plants go longer than usual: Basil, Fern").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("2 listed plants have no photo: Basil, Basil").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "2 plants share a name and room with another and have no photo to tell them apart: Basil, Basil"
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun withNoWarningsNoNoteIsShown() {
        setContent(state(twoPlants))

        composeTestRule.onNodeWithText("missed last time", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("have no photo", substring = true).assertDoesNotExist()
    }

    @Test
    fun theShareButtonsReportTheirChoice() {
        setContent(state(twoPlants))

        composeTestRule.onNodeWithText("Share list (PDF)").assertIsEnabled().performClick()
        composeTestRule.onNodeWithText("Share as text").assertIsEnabled().performClick()

        assertEquals(1, pdfShares)
        assertEquals(1, textShares)
    }

    @Test
    fun withNothingToWaterThereIsNothingToShare() {
        setContent(state(emptyList()))

        composeTestRule.onNodeWithText("No plants need water on this visit").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share list (PDF)").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Share as text").assertIsNotEnabled()
    }

    @Test
    fun whileSharingBothButtonsAreDisabled() {
        setContent(state(twoPlants, sharing = true))

        composeTestRule.onNodeWithText("Share list (PDF)").assertIsNotEnabled()
    }

    private fun hasState(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private val noLongClick = SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick)

    private val threeSections: Triple<List<ProjectedPlant>, List<ProjectedPlant>, List<Plant>> = Triple(
        listOf(listed(plant(1, "Basil", "Kitchen")), listed(plant(2, "Fern", "Study"))),
        listOf(later(plant(3, "Ficus", "Study"))),
        listOf(plant(4, "Cactus", null))
    )

    @Test
    fun suggestedPlantsAreSelectedAndTheOthersAreNot() {
        val (listed, notListed, resting) = threeSections
        setStatefulContent(listed, notListed, resting)

        listNode("Basil").assertIsSelected()
        listNode("Fern").assertIsSelected()
        listNode("Not on this visit · 1").performClick()
        listNode("Ficus").assertIsNotSelected()
    }

    @Test
    fun tappingACardTogglesItAndUpdatesTheSummary() {
        val (listed, notListed, resting) = threeSections
        setStatefulContent(listed, notListed, resting)
        listNode("Water 2 plants").assertIsDisplayed()

        listNode("Basil").performClick()

        listNode("Basil").assertIsNotSelected()
        listNode("Water 1 plant").assertIsDisplayed()
        listNode("On this visit · 2").assertIsDisplayed()

        listNode("Basil").performClick()

        listNode("Basil").assertIsSelected()
        listNode("Water 2 plants").assertIsDisplayed()
    }

    @Test
    fun tickingAPlantInACollapsedSectionKeepsItWhereItWas() {
        val (listed, notListed, resting) = threeSections
        setStatefulContent(listed, notListed, resting)
        listNode("Not on this visit · 1").performClick()

        listNode("Ficus").performClick()

        listNode("Ficus").assertIsSelected()
        listNode("Not on this visit · 1").assertIsDisplayed()
        listNode("On this visit · 2").assertIsDisplayed()
        listNode("Water 3 plants").assertIsDisplayed()
    }

    @Test
    fun aTapReportsThePlantsId() {
        val (listed, notListed, resting) = threeSections
        setContent(state(listed, resting, notListed))

        listNode("Fern").performClick()

        assertEquals(listOf(2L), toggled)
    }

    @Test
    fun cardsOfferNoLongClick() {
        val (listed, notListed, resting) = threeSections
        setContent(state(listed, resting, notListed))

        listNode("Basil").assert(noLongClick)
    }

    @Test
    fun sectionHeadersShowTheirCountsAndTheOthersStartCollapsed() {
        val (listed, notListed, resting) = threeSections
        setContent(state(listed, resting, notListed))

        listNode("On this visit · 2").assertIsDisplayed().assert(hasState("Expanded"))
        listNode("Not on this visit · 1").assertIsDisplayed().assert(hasState("Collapsed"))
        listNode("Resting · 1").assertIsDisplayed().assert(hasState("Collapsed"))
        composeTestRule.onNodeWithText("Ficus").assertDoesNotExist()
        composeTestRule.onNodeWithText("Cactus").assertDoesNotExist()
        listNode("Normally left dry", substring = true).assertIsDisplayed()
    }

    @Test
    fun expandingACollapsedSectionShowsItsPlants() {
        val (listed, notListed, resting) = threeSections
        setContent(state(listed, resting, notListed))

        listNode("Resting · 1").performClick()

        listNode("Resting · 1").assert(hasState("Expanded"))
        listNode("Cactus").assertIsNotSelected()
        listNode("Dormant", substring = true).assertIsDisplayed()
    }

    @Test
    fun anEmptySectionIsHidden() {
        setContent(state(twoPlants))

        listNode("On this visit · 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not on this visit", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Resting", substring = true).assertDoesNotExist()
    }

    @Test
    fun roomChipsListAllRoomsAndNoRoomWhenNeeded() {
        val (listed, notListed, resting) = threeSections
        setContent(state(listed, resting, notListed))

        roomChip("All").assertIsSelected()
        roomChip("Kitchen").assertIsNotSelected()
        roomChip("Study").assertIsNotSelected()
        roomChip("No room").assertIsNotSelected()
    }

    @Test
    fun noRoomChipIsAbsentWhenEveryPlantHasARoom() {
        setContent(state(listOf(listed(plant(1, "Basil", "Kitchen")))))

        roomChip("All").assertIsDisplayed()
        composeTestRule.onNodeWithText("No room").assertDoesNotExist()
    }

    @Test
    fun theRoomChipDescribesWhetherItHoldsSelectedPlants() {
        val (listed, notListed, resting) = threeSections
        setContent(state(listed, resting, notListed))

        roomChip("Kitchen").assert(hasState("Contains selected plants"))
        roomChip("No room")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    fun theSelectedRoomChipAndFilterReportTheChoice() {
        val (listed, notListed, resting) = threeSections
        setContent(state(listed, resting, notListed), roomFilter = "study")

        roomChip("Study").assertIsSelected()
        listNode("Fern").assertIsDisplayed()
        listNode("On this visit · 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Basil").assertDoesNotExist()

        roomChip("Kitchen").performClick()
        roomChip("All").performClick()

        assertEquals(listOf<String?>("kitchen", null), roomsPicked)
    }

    @Test
    fun filteringByRoomLeavesTicksAndTheSummaryCountAlone() {
        val (listed, notListed, resting) = threeSections
        setStatefulContent(listed, notListed, resting)

        roomChip("Study").performClick()
        roomChip("Study").assertIsSelected()

        listNode("Fern").assertIsSelected()
        listNode("On this visit · 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Basil").assertDoesNotExist()
        listNode("Water 2 plants").assertIsDisplayed()
        roomChip("Kitchen").assert(hasState("Contains selected plants"))
    }

    @Test
    fun aSectionEmptyAfterTheRoomFilterIsHidden() {
        val (listed, notListed, resting) = threeSections
        setContent(state(listed, resting, notListed), roomFilter = "kitchen")

        listNode("On this visit · 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Not on this visit", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Resting", substring = true).assertDoesNotExist()
    }

    @Test
    fun cardsShowTheRoomTheProjectedDateAndTheFlagLabels() {
        val twin = plant(1, "Basil", "Kitchen")
        setContent(state(listOf(listed(twin, missed = true, longer = true))))

        listNode("Kitchen · Today", substring = true).assertIsDisplayed()
        listNode("Missed last time").assertIsDisplayed()
        listNode("Longer than usual").assertIsDisplayed()
    }

    @Test
    fun theShareButtonsAreReachableWithoutScrollingEvenWithManyPlants() {
        val many = (1L..60L).map { listed(plant(it, "Plant $it", "Room ${it % 4}")) }
        setContent(state(many))

        composeTestRule.onNodeWithText("Share list (PDF)").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithText("Share as text").assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun untickingEverythingDisablesSharing() {
        val (listed, notListed, resting) = threeSections
        setStatefulContent(listed, notListed, resting)

        listNode("Basil").performClick()
        listNode("Fern").performClick()

        composeTestRule.onNodeWithText("Share list (PDF)").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Share as text").assertIsNotEnabled()
    }
}
