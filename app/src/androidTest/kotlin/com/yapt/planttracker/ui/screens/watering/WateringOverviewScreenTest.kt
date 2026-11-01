package com.yapt.planttracker.ui.screens.watering

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class WateringOverviewScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val today = LocalDate.now()
    private val plants = MutableStateFlow<List<Plant>>(emptyList())
    private val lastWatered = MutableStateFlow<Map<Long, Long>>(emptyMap())
    private val plantRepository = mockk<PlantRepository>()
    private val careLogRepository = mockk<CareLogRepository>()

    private fun str(id: Int, vararg args: Any) =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun plant(
        id: Long,
        name: String,
        interval: Int? = 7,
        room: String? = null
    ) = Plant(
        id = id,
        name = name,
        room = room,
        wateringIntervalDays = interval,
        pinIntervalToBase = true
    )

    private fun setContent(
        onNavigateBack: () -> Unit = {},
        onNavigateToPlant: (Long, PlantDetailTab) -> Unit = { _, _ -> }
    ) {
        every { plantRepository.getAllPlants() } returns plants
        every { careLogRepository.observeLastCareAtByPlant(CareType.WATER) } returns lastWatered
        val viewModel = WateringOverviewViewModel(
            plantRepository = plantRepository,
            careLogRepository = careLogRepository,
            seasonalAmplitude = flowOf(0.0),
            dayChange = MutableStateFlow(today)
        )
        composeTestRule.setContent {
            WateringOverviewScreen(
                viewModel = viewModel,
                onNavigateBack = onNavigateBack,
                onNavigateToPlant = onNavigateToPlant
            )
        }
    }

    @Test
    fun noActivePlantsShowsTheEmptyStateWithoutCountLineOrBar() {
        setContent()

        composeTestRule.onNodeWithText(str(R.string.watering_overview_no_plants)).assertIsDisplayed()
        composeTestRule.onNodeWithText("0 plants", substring = true).assertDoesNotExist()
    }

    @Test
    fun backArrowIsLabelledAndNavigatesBack() {
        var backs = 0
        plants.value = listOf(plant(1, "Fern"))
        setContent(onNavigateBack = { backs++ })

        composeTestRule.onNodeWithContentDescription(str(R.string.cd_back)).performClick()

        assertEquals(1, backs)
    }

    @Test
    fun countLineAndBarDescriptionReadOutTheGroups() {
        plants.value = listOf(
            plant(1, "Fern", interval = 2),
            plant(2, "Aloe", interval = 20),
            plant(3, "Cactus", interval = null)
        )
        setContent()

        composeTestRule.onNodeWithText("3 plants · 2 on a schedule · 1 without a schedule").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(
            "Plants by watering frequency: Every 1–3 days 1, Every 15+ days 1, No watering schedule 1"
        ).assertIsDisplayed()
    }

    @Test
    fun groupsAppearInOrderWithTheirRows() {
        plants.value = listOf(
            plant(1, "Fern", interval = 2, room = "Kitchen"),
            plant(2, "Aloe", interval = 20),
            plant(3, "Cactus", interval = null)
        )
        setContent()

        composeTestRule.onNodeWithText("Every 1–3 days · 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fern").assertIsDisplayed()
        composeTestRule.onNodeWithText("Kitchen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Every 2 days", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Every 15+ days · 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Every 20 days", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("No watering schedule · 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Cactus").assertIsDisplayed()
        composeTestRule.onNodeWithText("Every 4–7 days", substring = true).assertDoesNotExist()

        fun top(text: String) = composeTestRule.onNodeWithText(text).fetchSemanticsNode().positionInRoot.y
        assertTrue(top("Every 1–3 days · 1") < top("Every 15+ days · 1"))
        assertTrue(top("Every 15+ days · 1") < top("No watering schedule · 1"))
    }

    @Test
    fun aNeverWateredPlantShowsItsNextWateringAsToday() {
        plants.value = listOf(plant(1, "Fern", interval = 7))
        setContent()

        composeTestRule.onNodeWithText("Every 7 days · Next: Today").assertIsDisplayed()
    }

    @Test
    fun aGroupOfSixShowsFiveRowsAndShowAllRevealsEveryRow() {
        plants.value = (1L..6L).map { plant(it, "Plant $it", interval = 5) }
        setContent()

        composeTestRule.onNodeWithTag(wateringRowTag(5)).performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithTag(wateringRowTag(6)).assertDoesNotExist()

        composeTestRule.onNodeWithText("Show all 6").performScrollTo().performClick()

        // Row 6 is lazily composed once "Show all" expands the group; scroll the list to it.
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasTestTag(wateringRowTag(6)))
        composeTestRule.onNodeWithTag(wateringRowTag(6)).assertIsDisplayed()
        composeTestRule.onNodeWithText("Show all 6").assertDoesNotExist()
    }

    @Test
    fun aGroupOfFiveHasNoShowAll() {
        plants.value = (1L..5L).map { plant(it, "Plant $it", interval = 5) }
        setContent()

        composeTestRule.onNodeWithText("Show all 5").assertDoesNotExist()
    }

    @Test
    fun tappingARowOpensThePlantOnTheWaterTab() {
        var opened: Pair<Long, PlantDetailTab>? = null
        plants.value = listOf(plant(7, "Fern"))
        setContent(onNavigateToPlant = { id, tab -> opened = id to tab })

        composeTestRule.onNodeWithText("Fern").performClick()

        assertTrue(opened == (7L to PlantDetailTab.WATER))
    }
}
