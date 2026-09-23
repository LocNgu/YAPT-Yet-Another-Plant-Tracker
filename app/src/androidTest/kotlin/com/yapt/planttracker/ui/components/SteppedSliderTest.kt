package com.yapt.planttracker.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private val TEST_LABELS = SteppedSliderLabels(
    decreaseContentDescription = "Decrease",
    increaseContentDescription = "Increase"
)

@RunWith(AndroidJUnit4::class)
class SteppedSliderTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun increaseButton_bumpsValueByOne_andInvokesOnValueChange() {
        var value by mutableStateOf(5)
        composeTestRule.setContent {
            SteppedSlider(
                value = value,
                range = 1..10,
                callbacks = SteppedSliderCallbacks(onValueChange = { value = it }),
                labels = TEST_LABELS
            )
        }

        composeTestRule.onNodeWithContentDescription("Increase").performClick()

        composeTestRule.runOnIdle { assertEquals(6, value) }
    }

    @Test
    fun decreaseButton_dropsValueByOne_andInvokesOnValueChange() {
        var value by mutableStateOf(5)
        composeTestRule.setContent {
            SteppedSlider(
                value = value,
                range = 1..10,
                callbacks = SteppedSliderCallbacks(onValueChange = { value = it }),
                labels = TEST_LABELS
            )
        }

        composeTestRule.onNodeWithContentDescription("Decrease").performClick()

        composeTestRule.runOnIdle { assertEquals(4, value) }
    }

    @Test
    fun decreaseButton_disabledAtLowerBound() {
        composeTestRule.setContent {
            SteppedSlider(
                value = 1,
                range = 1..10,
                callbacks = SteppedSliderCallbacks(onValueChange = {}),
                labels = TEST_LABELS
            )
        }

        composeTestRule.onNodeWithContentDescription("Decrease").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Increase").assertIsEnabled()
    }

    @Test
    fun increaseButton_disabledAtUpperBound() {
        composeTestRule.setContent {
            SteppedSlider(
                value = 10,
                range = 1..10,
                callbacks = SteppedSliderCallbacks(onValueChange = {}),
                labels = TEST_LABELS
            )
        }

        composeTestRule.onNodeWithContentDescription("Increase").assertIsNotEnabled()
        composeTestRule.onNodeWithContentDescription("Decrease").assertIsEnabled()
    }

    @Test
    fun disabledButtonTap_neverProducesAnOutOfRangeValue() {
        var value by mutableStateOf(10)
        composeTestRule.setContent {
            SteppedSlider(
                value = value,
                range = 1..10,
                callbacks = SteppedSliderCallbacks(onValueChange = { value = it }),
                labels = TEST_LABELS
            )
        }

        // A disabled IconButton does not dispatch its onClick, so this is a no-op — asserting the
        // value never moves out of range even though the click gesture itself is still sent.
        composeTestRule.onNodeWithContentDescription("Increase").performClick()
        composeTestRule.runOnIdle { assertEquals(10, value) }
    }

    @Test
    fun onValueChangeFinished_isCalledOnButtonTap() {
        var value by mutableStateOf(5)
        var finishedCallCount = 0
        composeTestRule.setContent {
            SteppedSlider(
                value = value,
                range = 1..10,
                callbacks = SteppedSliderCallbacks(
                    onValueChange = { value = it },
                    onValueChangeFinished = { finishedCallCount++ }
                ),
                labels = TEST_LABELS
            )
        }

        composeTestRule.onNodeWithContentDescription("Increase").performClick()

        composeTestRule.runOnIdle {
            assertEquals(6, value)
            assertEquals(1, finishedCallCount)
        }
    }

    @Test
    fun stateDescription_reflectsCurrentValue_andUpdatesAfterATap() {
        var value by mutableStateOf(6)
        var stateDescription by mutableStateOf("Every 6 days")
        composeTestRule.setContent {
            SteppedSlider(
                value = value,
                range = 1..10,
                callbacks = SteppedSliderCallbacks(onValueChange = { value = it }),
                labels = TEST_LABELS.copy(stateDescription = stateDescription)
            )
        }

        composeTestRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Every 6 days"))
            .assertIsEnabled()

        composeTestRule.onNodeWithContentDescription("Increase").performClick()
        composeTestRule.runOnIdle {
            assertEquals(7, value)
            stateDescription = "Every 7 days"
        }

        composeTestRule.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Every 7 days"))
            .assertIsEnabled()
    }

    @Test
    fun contentDescriptionsAreDistinctAndPresent() {
        composeTestRule.setContent {
            SteppedSlider(
                value = 5,
                range = 1..10,
                callbacks = SteppedSliderCallbacks(onValueChange = {}),
                labels = SteppedSliderLabels(
                    decreaseContentDescription = "Decrease watering interval",
                    increaseContentDescription = "Increase watering interval"
                )
            )
        }

        composeTestRule.onNodeWithContentDescription("Decrease watering interval").assertIsEnabled()
        composeTestRule.onNodeWithContentDescription("Increase watering interval").assertIsEnabled()
    }
}
