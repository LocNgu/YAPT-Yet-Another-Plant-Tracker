package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.schedule.WateringConfidenceLevel
import com.yapt.planttracker.domain.schedule.WateringExplanation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WateringExplanationSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dormantSheetExplainsSuspensionAndShowsDormancyAdjustments() {
        val now = System.currentTimeMillis()
        val explanation = WateringExplanation(
            nextWateringDueAt = now,
            lastWateredAt = now,
            effectiveIntervalDays = 7,
            waterLogCount = 2,
            baseIntervalDays = 7,
            confidenceLevel = WateringConfidenceLevel.GETTING_THERE,
            isDormant = true,
            recentAdjustments = listOf(
                WateringAdjustment(
                    plantId = 1L,
                    triggeredAt = now,
                    trigger = WateringAdjustmentTrigger.DORMANCY_EXCLUDED,
                    beforeIntervalDays = 7,
                    afterIntervalDays = 7
                ),
                WateringAdjustment(
                    plantId = 1L,
                    triggeredAt = now,
                    trigger = WateringAdjustmentTrigger.DORMANCY_EXIT,
                    beforeIntervalDays = 7,
                    afterIntervalDays = 7
                )
            )
        )

        composeTestRule.setContent { WateringExplanationSheet(explanation, onDismiss = {}) }

        composeTestRule.onNodeWithText("Suspended during dormancy").assertIsDisplayed()
        composeTestRule.onNodeWithText("dormant — not counted").assertIsDisplayed()
        composeTestRule.onNodeWithText("left dormancy").assertIsDisplayed()
        composeTestRule.onNodeWithText("Watering every 7 days").assertDoesNotExist()
    }
}
