package com.yapt.planttracker.ui.components

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.WateringScheduleMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #802: a dormant plant's fertilizing label must show "Dormant" instead of a due/overdue
 * countdown, mirroring the watering label's existing [WateringScheduleMode.DORMANT_SUSPENDED]
 * branch. Assertions are on the user-visible label text only (#420), never tree structure.
 */
@RunWith(AndroidJUnit4::class)
class PlantCardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val now = System.currentTimeMillis()
    private val threeDaysAgo = now - 3L * 24 * 60 * 60 * 1000
    private val inTwoDays = now + 2L * 24 * 60 * 60 * 1000

    private fun basePlant() = Plant(
        id = 1L,
        name = "Monstera",
        wateringIntervalDays = 7,
        fertilizingIntervalDays = 14,
        useLiquidFertilizer = false
    )

    @Test
    fun dormantWithoutCadence_fertilizingLabelShowsDormant_noOverdueCountdown() {
        val status = PlantCareStatus(
            plant = basePlant(),
            lastWateredAt = threeDaysAgo,
            lastFertilizedAt = threeDaysAgo,
            daysSinceLastWatering = 3,
            nextWateringDueAt = threeDaysAgo,
            isOverdue = false,
            isDueSoon = false,
            nextFertilizingDueAt = threeDaysAgo,
            isFertilizingOverdue = false,
            isFertilizingDueSoon = false,
            totalCareLogs = 2,
            isDormant = true,
            wateringScheduleMode = WateringScheduleMode.DORMANT_SUSPENDED
        )

        composeTestRule.setContent {
            PlantCard(status = status, onClick = {}, onQuickWater = {}, onQuickFertilize = {})
        }

        // Both the watering and fertilizing labels read "Dormant" while fully suspended.
        composeTestRule.onAllNodesWithText("Dormant").assertCountEquals(2)
        composeTestRule.onNode(hasText("Overdue", substring = true)).assertDoesNotExist()
    }

    @Test
    fun dormantWithCadence_fertilizingLabelStillShowsDormant_whileWateringShowsCountdown() {
        val status = PlantCareStatus(
            plant = basePlant().copy(dormantWateringIntervalDays = 21),
            lastWateredAt = threeDaysAgo,
            lastFertilizedAt = threeDaysAgo,
            daysSinceLastWatering = 3,
            nextWateringDueAt = inTwoDays,
            isOverdue = false,
            isDueSoon = false,
            nextFertilizingDueAt = threeDaysAgo,
            isFertilizingOverdue = false,
            isFertilizingDueSoon = false,
            totalCareLogs = 2,
            isDormant = true,
            wateringScheduleMode = WateringScheduleMode.DORMANT_CADENCE
        )

        composeTestRule.setContent {
            PlantCard(status = status, onClick = {}, onQuickWater = {}, onQuickFertilize = {})
        }

        // Fertilizing is always paused during dormancy, even with a #785 dormant watering
        // cadence configured, so exactly one "Dormant" node (fertilizing) is shown here, while
        // watering shows its own countdown text instead.
        composeTestRule.onAllNodesWithText("Dormant").assertCountEquals(1)
        composeTestRule.onNodeWithText("In 2 days").assertIsDisplayed()
        composeTestRule.onNode(hasText("Overdue", substring = true)).assertDoesNotExist()
    }

    @Test
    fun notDormant_fertilizingLabelShowsOverdueCountdown() {
        val status = PlantCareStatus(
            plant = basePlant(),
            lastWateredAt = threeDaysAgo,
            lastFertilizedAt = threeDaysAgo,
            daysSinceLastWatering = 3,
            nextWateringDueAt = inTwoDays,
            isOverdue = false,
            isDueSoon = false,
            nextFertilizingDueAt = threeDaysAgo,
            isFertilizingOverdue = true,
            isFertilizingDueSoon = false,
            totalCareLogs = 2,
            isDormant = false,
            wateringScheduleMode = WateringScheduleMode.NORMAL
        )

        composeTestRule.setContent {
            PlantCard(status = status, onClick = {}, onQuickWater = {}, onQuickFertilize = {})
        }

        composeTestRule.onNodeWithText("Overdue by 3 days").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Dormant").assertCountEquals(0)
    }
}
