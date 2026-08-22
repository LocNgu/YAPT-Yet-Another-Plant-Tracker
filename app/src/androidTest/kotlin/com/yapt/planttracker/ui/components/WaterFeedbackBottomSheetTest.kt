package com.yapt.planttracker.ui.components

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.WateringFeedback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WaterFeedbackBottomSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun wasDryLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.care_log_feedback_was_dry_label)

    private fun logLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.quick_water_log)

    // Single optional flag, not a 3-way chip group (#570, product ADR-0027); nothing pre-selected.

    @Test
    fun wasDryFlag_isUnselectedByDefault() {
        composeTestRule.setContent {
            WaterFeedbackBottomSheet(
                plantName = "Fern",
                onDismiss = {},
                onLog = {}
            )
        }

        composeTestRule
            .onNodeWithText(wasDryLabel())
            .assertIsNotSelected()
    }

    @Test
    fun tappingWasDryFlag_selectsIt() {
        composeTestRule.setContent {
            WaterFeedbackBottomSheet(
                plantName = "Fern",
                onDismiss = {},
                onLog = {}
            )
        }

        composeTestRule.onNodeWithText(wasDryLabel()).performClick()

        composeTestRule
            .onNodeWithText(wasDryLabel())
            .assertIsSelected()
    }

    @Test
    fun logButton_withDefaultSelection_invokesOnLogWithNull() {
        var loggedFeedback: WateringFeedback? = WateringFeedback.TOO_LATE
        var invoked = false

        composeTestRule.setContent {
            WaterFeedbackBottomSheet(
                plantName = "Fern",
                onDismiss = {},
                onLog = { feedback ->
                    invoked = true
                    loggedFeedback = feedback
                }
            )
        }

        composeTestRule.onNodeWithText(logLabel()).performClick()

        assertTrue(invoked)
        assertNull(loggedFeedback)
    }

    @Test
    fun logButton_withWasDryFlagSelected_invokesOnLogWithTooLate() {
        var loggedFeedback: WateringFeedback? = null
        var invoked = false

        composeTestRule.setContent {
            WaterFeedbackBottomSheet(
                plantName = "Fern",
                onDismiss = {},
                onLog = { feedback ->
                    invoked = true
                    loggedFeedback = feedback
                }
            )
        }

        composeTestRule.onNodeWithText(wasDryLabel()).performClick()
        composeTestRule.onNodeWithText(logLabel()).performClick()

        assertTrue(invoked)
        assertEquals(WateringFeedback.TOO_LATE, loggedFeedback)
    }
}
