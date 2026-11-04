package com.yapt.planttracker.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #990: in selection mode, a tap or long-press on the hidden quick-log strip at the card's trailing
 * edge must reach the card's selection toggle and never quick-log. Disabled `IconButton`s there used
 * to consume the pointer events, so the strip was a dead zone; the #450 test only checked semantics
 * and never tapped it. These tests inject real touches at the buttons' positions (read in normal
 * mode, where the buttons exist) and assert the user-visible `selected` state (#420).
 */
@RunWith(AndroidJUnit4::class)
class PlantCardSelectionModeTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private var selectionMode by mutableStateOf(false)
    private var selected by mutableStateOf(false)
    private var quickWaterCalls = 0
    private var quickFertilizeCalls = 0

    @Test
    fun selectionMode_hiddenQuickLogStrip_togglesSelection_neverQuickLogs() {
        assertHiddenStripTogglesSelection(useLiquidFertilizer = false)
    }

    @Test
    fun selectionMode_hiddenQuickLogStrip_liquidFertilizer_togglesSelection_neverQuickLogs() {
        assertHiddenStripTogglesSelection(useLiquidFertilizer = true)
    }

    private fun assertHiddenStripTogglesSelection(useLiquidFertilizer: Boolean) {
        setCard(useLiquidFertilizer)
        val card = composeTestRule.onNodeWithText("Monstera")
        val cardBounds = card.fetchSemanticsNode().boundsInRoot
        val water = buttonCenter("Quick water") - cardBounds.topLeft
        val fertilize = buttonCenter("Quick fertilize") - cardBounds.topLeft

        selectionMode = true
        composeTestRule.waitForIdle()

        assertEquals(cardBounds.height, card.fetchSemanticsNode().boundsInRoot.height, 0.5f)
        composeTestRule.onNodeWithContentDescription("Quick water").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Quick fertilize").assertDoesNotExist()

        card.assertIsNotSelected()
        card.performTouchInput { click(water) }
        card.assertIsSelected()
        card.performTouchInput { click(fertilize) }
        card.assertIsNotSelected()
        card.performTouchInput { longClick(water) }
        card.assertIsSelected()

        assertEquals(0, quickWaterCalls)
        assertEquals(0, quickFertilizeCalls)
    }

    private fun buttonCenter(contentDescription: String): Offset =
        composeTestRule.onNodeWithContentDescription(contentDescription).fetchSemanticsNode().boundsInRoot.center

    private fun setCard(useLiquidFertilizer: Boolean) {
        val status = PlantCareStatus(
            plant = Plant(
                id = 1L,
                name = "Monstera",
                wateringIntervalDays = 7,
                fertilizingIntervalDays = 14,
                useLiquidFertilizer = useLiquidFertilizer
            ),
            lastWateredAt = null,
            lastFertilizedAt = null,
            daysSinceLastWatering = null,
            nextWateringDueAt = null,
            isOverdue = false,
            isDueSoon = false,
            nextFertilizingDueAt = null,
            isFertilizingOverdue = false,
            isFertilizingDueSoon = false,
            totalCareLogs = 0
        )
        composeTestRule.setContent {
            PlantCard(
                status = status,
                onClick = {},
                onQuickWater = { quickWaterCalls++ },
                onQuickFertilize = { quickFertilizeCalls++ },
                selectionMode = selectionMode,
                selected = selected,
                onLongClick = { selected = !selected },
                onToggleSelect = { selected = !selected }
            )
        }
    }
}
