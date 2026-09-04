package com.yapt.planttracker.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.RescheduleReason
import com.yapt.planttracker.domain.model.WateringReason
import com.yapt.planttracker.ui.theme.YaptTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The off-schedule reason prompts (#586, product ADR-0030). Assertions are on user-visible text and
 * on what each answer reports back — never on tree structure (#420).
 */
class WateringReasonBottomSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun string(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val plantNeededIt get() = string(R.string.water_reason_plant_needed_it)
    private val justMyTiming get() = string(R.string.water_reason_just_my_timing)
    private val justMyTimingLate get() = string(R.string.water_reason_just_my_timing_late)
    private val soilStillMoist get() = string(R.string.reschedule_reason_soil_still_moist)
    private val logLabel get() = string(R.string.quick_water_log)

    @Test
    fun wateringPrompt_showsBothReasonsAndTheQuestion() {
        composeTestRule.setContent {
            YaptTheme {
                WateringReasonBottomSheet(plantName = "Fern", onDismiss = {}, onLog = {})
            }
        }

        composeTestRule.onNodeWithText(string(R.string.water_reason_question)).assertIsDisplayed()
        composeTestRule.onNodeWithText(plantNeededIt).assertIsDisplayed()
        composeTestRule.onNodeWithText(justMyTiming).assertIsDisplayed()
    }

    /**
     * #586/#649: a gap that ran long gets late-direction wording *and* a different option set, not
     * merely relabeled chips — the late direction never offers a "shorten" attribution (product
     * ADR-0033), so "The plant needed it" must not appear at all once the gap ran long.
     */
    @Test
    fun wateringPrompt_whenTheGapRanLong_showsTheLateWording() {
        composeTestRule.setContent {
            YaptTheme {
                WateringReasonBottomSheet(
                    plantName = "Fern",
                    gapRanLong = true,
                    onDismiss = {},
                    onLog = {}
                )
            }
        }

        composeTestRule.onNodeWithText(string(R.string.water_reason_question_late)).assertIsDisplayed()
        composeTestRule.onNodeWithText(soilStillMoist).assertIsDisplayed()
        composeTestRule.onNodeWithText(justMyTimingLate).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.water_reason_question)).assertDoesNotExist()
        composeTestRule.onNodeWithText(plantNeededIt).assertDoesNotExist()
    }

    /**
     * #649 (product ADR-0033): unlike [WateringReason.JUST_MY_TIMING], the late direction's
     * plant-attribution chip is a genuinely different reason from the early one — "Soil was still
     * moist" reports [WateringReason.SOIL_STILL_MOIST], never [WateringReason.PLANT_NEEDED_IT], since
     * a late gap must never shorten the interval.
     */
    @Test
    fun wateringPrompt_choosingSoilStillMoistLate_reportsThatReason() {
        var logged: WateringReason? = null
        composeTestRule.setContent {
            YaptTheme {
                WateringReasonBottomSheet(
                    plantName = "Fern",
                    gapRanLong = true,
                    onDismiss = {},
                    onLog = { logged = it }
                )
            }
        }

        composeTestRule.onNodeWithText(soilStillMoist).performClick()
        composeTestRule.onNodeWithText(logLabel).performClick()

        assertEquals(WateringReason.SOIL_STILL_MOIST, logged)
    }

    @Test
    fun wateringPrompt_choosingJustMyTimingLate_reportsThatReason() {
        var logged: WateringReason? = null
        composeTestRule.setContent {
            YaptTheme {
                WateringReasonBottomSheet(
                    plantName = "Fern",
                    gapRanLong = true,
                    onDismiss = {},
                    onLog = { logged = it }
                )
            }
        }

        composeTestRule.onNodeWithText(justMyTimingLate).performClick()
        composeTestRule.onNodeWithText(logLabel).performClick()

        assertEquals(WateringReason.JUST_MY_TIMING, logged)
    }

    @Test
    fun wateringPrompt_logWithNoChipChosen_reportsNoReason() {
        var logged: WateringReason? = WateringReason.PLANT_NEEDED_IT
        var callCount = 0
        composeTestRule.setContent {
            YaptTheme {
                WateringReasonBottomSheet(
                    plantName = "Fern",
                    onDismiss = {},
                    onLog = { logged = it; callCount++ }
                )
            }
        }

        composeTestRule.onNodeWithText(logLabel).performClick()

        assertEquals(1, callCount)
        // Declining to attribute is a real answer: the watering is logged, the model learns nothing.
        assertNull(logged)
    }

    @Test
    fun wateringPrompt_choosingPlantNeededIt_reportsThatReason() {
        var logged: WateringReason? = null
        composeTestRule.setContent {
            YaptTheme {
                WateringReasonBottomSheet(plantName = "Fern", onDismiss = {}, onLog = { logged = it })
            }
        }

        composeTestRule.onNodeWithText(plantNeededIt).performClick()
        composeTestRule.onNodeWithText(logLabel).performClick()

        assertEquals(WateringReason.PLANT_NEEDED_IT, logged)
    }

    @Test
    fun wateringPrompt_choosingJustMyTiming_reportsThatReason() {
        var logged: WateringReason? = null
        composeTestRule.setContent {
            YaptTheme {
                WateringReasonBottomSheet(plantName = "Fern", onDismiss = {}, onLog = { logged = it })
            }
        }

        composeTestRule.onNodeWithText(justMyTiming).performClick()
        composeTestRule.onNodeWithText(logLabel).performClick()

        assertEquals(WateringReason.JUST_MY_TIMING, logged)
    }

    /** Product ADR-0024: the chip stays deselectable, so a mis-tap can be taken back. */
    @Test
    fun wateringPrompt_tappingTheSameChipTwiceClearsIt() {
        var logged: WateringReason? = WateringReason.PLANT_NEEDED_IT
        composeTestRule.setContent {
            YaptTheme {
                WateringReasonBottomSheet(plantName = "Fern", onDismiss = {}, onLog = { logged = it })
            }
        }

        composeTestRule.onNodeWithText(plantNeededIt).performClick()
        composeTestRule.onNodeWithText(plantNeededIt).performClick()
        composeTestRule.onNodeWithText(logLabel).performClick()

        assertNull(logged)
    }

    @Test
    fun reschedulePrompt_showsBothReasonsAndReportsTheChosenOne() {
        var chosen: RescheduleReason? = null
        composeTestRule.setContent {
            YaptTheme {
                RescheduleReasonBottomSheet(onDismiss = {}, onReasonChosen = { chosen = it })
            }
        }

        composeTestRule.onNodeWithText(string(R.string.reschedule_reason_question)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.reschedule_reason_cant_right_now)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.reschedule_reason_soil_still_moist)).performClick()

        assertEquals(RescheduleReason.SOIL_STILL_MOIST, chosen)
    }

    /** No confirm step here: choosing *is* the answer, so a reschedule can't be committed unanswered. */
    @Test
    fun reschedulePrompt_hasNoConfirmButton() {
        composeTestRule.setContent {
            YaptTheme {
                RescheduleReasonBottomSheet(onDismiss = {}, onReasonChosen = {})
            }
        }

        assertTrue(
            composeTestRule.onAllNodesWithText(logLabel)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }
}
