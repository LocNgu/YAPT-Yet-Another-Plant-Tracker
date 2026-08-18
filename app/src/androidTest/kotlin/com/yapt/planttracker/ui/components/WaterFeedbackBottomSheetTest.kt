package com.yapt.planttracker.ui.components

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasText
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

    private fun justRightLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.feedback_just_right)

    private fun logLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.quick_water_log)

    @Test
    fun justRightChip_isSelectedByDefault() {
        composeTestRule.setContent {
            WaterFeedbackBottomSheet(
                plantName = "Fern",
                onDismiss = {},
                onLog = {}
            )
        }

        composeTestRule
            .onNode(hasText(justRightLabel(), substring = true))
            .assertIsSelected()
    }

    @Test
    fun tappingSelectedChip_deselectsIt() {
        composeTestRule.setContent {
            WaterFeedbackBottomSheet(
                plantName = "Fern",
                onDismiss = {},
                onLog = {}
            )
        }

        composeTestRule.onNodeWithText(justRightLabel(), substring = true).performClick()

        composeTestRule
            .onNode(hasText(justRightLabel(), substring = true))
            .assertIsNotSelected()
    }

    @Test
    fun logButton_withNoSelection_invokesOnLogWithNull() {
        var loggedFeedback: WateringFeedback? = WateringFeedback.JUST_RIGHT
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

        composeTestRule.onNodeWithText(justRightLabel(), substring = true).performClick()
        composeTestRule.onNodeWithText(logLabel()).performClick()

        assertTrue(invoked)
        assertNull(loggedFeedback)
    }

    @Test
    fun logButton_withDefaultSelection_invokesOnLogWithJustRight() {
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

        composeTestRule.onNodeWithText(logLabel()).performClick()

        assertTrue(invoked)
        assertEquals(WateringFeedback.JUST_RIGHT, loggedFeedback)
    }
}
