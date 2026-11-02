package com.yapt.planttracker.ui.screens.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayTaskBucket
import com.yapt.planttracker.domain.today.TodayWateredPlant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sticky Care section headers (#931, product ADR-0069). These compose [TodayCareGrid] in a normal-height
 * viewport and scroll it, unlike `TodayScreenTest`'s `tall` viewport that only checks existence and order.
 */
@RunWith(AndroidJUnit4::class)
class TodayCareGridStickyHeaderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val noActions = TodayTaskActions(
        onOpen = { _, _ -> },
        onComplete = {},
        onLogWatering = {},
        onReschedule = {},
        onRepot = {},
        onPhoto = {}
    )

    private fun plant(id: Long) = Plant(id = id, name = "Plant $id", createdAt = 0L, updatedAt = 0L)

    private fun task(id: String, plantId: Long, kind: TodayCareKind) = TodayCareTask(
        id = id,
        plant = plant(plantId),
        kind = kind,
        dueAt = System.currentTimeMillis(),
        bucket = TodayTaskBucket.Today
    )

    private fun waterTasks(count: Int) = (1..count).map { task("water:$it", it.toLong(), TodayCareKind.WATER) }

    private fun fertilizeTasks(count: Int) =
        (1..count).map { task("fertilize:$it", (100 + it).toLong(), TodayCareKind.FERTILIZE) }

    private fun watered(count: Int) = (1..count).map {
        TodayWateredPlant(plant(WATERED_ID_BASE + it), wateredAt = WATERED_AT - it)
    }

    private fun setGrid(
        tasks: List<TodayCareTask>,
        initiallyCollapsed: Set<String> = emptySet(),
        wateredToday: List<TodayWateredPlant> = emptyList()
    ) {
        composeTestRule.setContent {
            val gridState = rememberLazyGridState()
            var collapsed by remember { mutableStateOf(initiallyCollapsed) }
            Box(Modifier.fillMaxWidth().height(VIEWPORT)) {
                TodayCareGrid(
                    tasks = tasks,
                    gridState = gridState,
                    collapsedGroups = collapsed,
                    onToggleGroup = { key -> collapsed = if (key in collapsed) collapsed - key else collapsed + key },
                    actions = noActions,
                    wateredToday = wateredToday
                )
            }
        }
    }

    private fun scrollToIndex(index: Int) {
        composeTestRule.onNodeWithTag(TODAY_TASK_LIST_TAG).performScrollToIndex(index)
        composeTestRule.waitForIdle()
    }

    private fun gridTop() = composeTestRule.onNodeWithTag(TODAY_TASK_LIST_TAG).fetchSemanticsNode().boundsInRoot.top

    private fun gridBottom() =
        composeTestRule.onNodeWithTag(TODAY_TASK_LIST_TAG).fetchSemanticsNode().boundsInRoot.bottom

    private fun headerTop(text: String) = composeTestRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    private fun isInViewport(text: String): Boolean =
        composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes().any {
            it.boundsInRoot.bottom > gridTop() && it.boundsInRoot.top < gridBottom()
        }

    private fun hasStateDescription(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    @Test
    fun theHeaderOfTheSectionBeingScrolledStaysPinnedAtTheTopOfTheGrid() {
        setGrid(waterTasks(WATER_COUNT) + fertilizeTasks(FERTILIZE_COUNT))

        scrollToIndex(PINNED_SCROLL_INDEX)

        composeTestRule.onNodeWithText(WATERING_HEADER).assertIsDisplayed()
        assertEquals(gridTop(), headerTop(WATERING_HEADER), POSITION_TOLERANCE)
        composeTestRule.onAllNodesWithText(WATERING_HEADER).assertCountEquals(1)
    }

    @Test
    fun theNextSectionsHeaderTakesOverWhenItReachesTheTop() {
        setGrid(waterTasks(WATER_COUNT) + fertilizeTasks(FERTILIZE_COUNT))

        scrollToIndex(PINNED_SCROLL_INDEX)
        assertTrue(isInViewport(WATERING_HEADER))

        scrollToIndex(FERTILIZING_HEADER_INDEX)

        composeTestRule.onNodeWithText(FERTILIZING_HEADER).assertIsDisplayed()
        assertEquals(gridTop(), headerTop(FERTILIZING_HEADER), POSITION_TOLERANCE)
        composeTestRule.onAllNodesWithText(FERTILIZING_HEADER).assertCountEquals(1)
        assertTrue(!isInViewport(WATERING_HEADER))
    }

    @Test
    fun tappingThePinnedHeaderCollapsesItsSectionAndLandsOnThatHeader() {
        setGrid(waterTasks(WATER_COUNT) + fertilizeTasks(FERTILIZE_COUNT))
        scrollToIndex(PINNED_SCROLL_INDEX)

        composeTestRule.onNodeWithText(WATERING_HEADER).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(WATERING_HEADER).assert(hasStateDescription("Collapsed"))
        composeTestRule.onNodeWithContentDescription("Plant 1, watering").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Plant $PINNED_SCROLL_INDEX, watering").assertDoesNotExist()
        assertEquals(gridTop(), headerTop(WATERING_HEADER), POSITION_TOLERANCE)
        composeTestRule.onNodeWithText(FERTILIZING_HEADER).assertIsDisplayed()
        assertTrue(headerTop(WATERING_HEADER) < headerTop(FERTILIZING_HEADER))

        composeTestRule.onNodeWithText(WATERING_HEADER).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(WATERING_HEADER).assert(hasStateDescription("Expanded"))
        composeTestRule.onNodeWithContentDescription("Plant 1, watering").assertIsDisplayed()
    }

    @Test
    fun collapsingAHeaderThatIsOnScreenLeavesTheScrollPositionAlone() {
        setGrid(waterTasks(2) + fertilizeTasks(FERTILIZE_COUNT))
        val fertilizingTop = headerTop(FERTILIZING_HEADER)
        assertTrue(fertilizingTop > gridTop())

        composeTestRule.onNodeWithText(FERTILIZING_HEADER).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(FERTILIZING_HEADER).assert(hasStateDescription("Collapsed"))
        composeTestRule.onNodeWithContentDescription("Plant 101, fertilizing").assertDoesNotExist()
        assertEquals(fertilizingTop, headerTop(FERTILIZING_HEADER), POSITION_TOLERANCE)
    }

    @Test
    fun collapseKeysFromBeforeTheFlatWateringSectionsHaveNoEffect() {
        val tasks = waterTasks(2) + fertilizeTasks(1)
        setGrid(tasks, initiallyCollapsed = setOf("section:WATERING", "watering:NEXT_THREE_DAYS"))

        composeTestRule.onNodeWithText("Watering · Today · 2").assert(hasStateDescription("Expanded"))
        composeTestRule.onNodeWithContentDescription("Plant 1, watering").assertIsDisplayed()
    }

    @Test
    fun theNewCollapseKeysHideExactlyTheirOwnSections() {
        val tasks = waterTasks(2) + fertilizeTasks(1)
        setGrid(tasks, initiallyCollapsed = setOf("watering:TODAY"))

        composeTestRule.onNodeWithText("Watering · Today · 2").assert(hasStateDescription("Collapsed"))
        composeTestRule.onNodeWithContentDescription("Plant 1, watering").assertDoesNotExist()
        composeTestRule.onNodeWithText("Fertilizing · 1").assert(hasStateDescription("Expanded"))
        composeTestRule.onNodeWithContentDescription("Plant 101, fertilizing").assertIsDisplayed()
    }

    @Test
    fun tappingThePinnedWateredTodayHeaderCollapsesItAndLandsOnThatHeader() {
        setGrid(fertilizeTasks(FERTILIZE_COUNT), wateredToday = watered(WATERED_COUNT))
        scrollToIndex(WATERED_ROW_INDEX)
        assertEquals(gridTop(), headerTop(WATERED_HEADER), POSITION_TOLERANCE)
        composeTestRule.onAllNodesWithText(WATERED_HEADER).assertCountEquals(1)

        composeTestRule.onNodeWithText(WATERED_HEADER).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(WATERED_HEADER).assert(hasStateDescription("Collapsed"))
        composeTestRule.onNodeWithContentDescription("Plant 201, watered today").assertDoesNotExist()
        assertEquals(gridTop(), headerTop(WATERED_HEADER), POSITION_TOLERANCE)
        composeTestRule.onNodeWithText(FERTILIZING_HEADER).assertIsDisplayed()
        assertTrue(headerTop(WATERED_HEADER) < headerTop(FERTILIZING_HEADER))
    }

    @Test
    fun collapsingAWateredTodayHeaderThatIsOnScreenLeavesTheScrollPositionAlone() {
        setGrid(waterTasks(2) + fertilizeTasks(FERTILIZE_COUNT), wateredToday = watered(WATERED_COUNT))
        val wateredTop = headerTop(WATERED_HEADER)
        assertTrue(wateredTop > gridTop())
        composeTestRule.onNodeWithContentDescription("Plant 201, watered today").assertExists()

        composeTestRule.onNodeWithText(WATERED_HEADER).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(WATERED_HEADER).assert(hasStateDescription("Collapsed"))
        composeTestRule.onNodeWithContentDescription("Plant 201, watered today").assertDoesNotExist()
        assertEquals(wateredTop, headerTop(WATERED_HEADER), POSITION_TOLERANCE)
    }

    private companion object {
        val VIEWPORT = 400.dp
        const val WATERED_COUNT = 3
        const val WATERED_ID_BASE = 200L
        const val WATERED_AT = 1_700_000_000_000L
        const val WATERED_HEADER = "Watered today · 3"
        const val WATERED_ROW_INDEX = 1
        const val WATER_COUNT = 12
        const val FERTILIZE_COUNT = 12
        const val WATERING_HEADER = "Watering · Today · 12"
        const val FERTILIZING_HEADER = "Fertilizing · 12"
        const val POSITION_TOLERANCE = 1f
        const val PINNED_SCROLL_INDEX = 5
        const val FERTILIZING_HEADER_INDEX = WATER_COUNT + 1
    }
}
