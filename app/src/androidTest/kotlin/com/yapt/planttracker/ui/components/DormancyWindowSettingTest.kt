package com.yapt.planttracker.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DormancyWindowSettingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun offByDefault_revealsMonthPickers_andClearsBothMonths() {
        var start by mutableStateOf<Int?>(null)
        var end by mutableStateOf<Int?>(null)
        composeTestRule.setContent {
            DormancyWindowSetting(start, end) { nextStart, nextEnd ->
                start = nextStart
                end = nextEnd
            }
        }

        composeTestRule.onNodeWithContentDescription("Dormancy window").assertIsOff().performClick()
        composeTestRule.onNodeWithContentDescription("Dormancy window").assertIsOn()
        composeTestRule.onNodeWithText("Start").assertExists()
        composeTestRule.onNodeWithText("End").assertExists()
        composeTestRule.onNodeWithContentDescription("Dormancy window").performClick()
        composeTestRule.onNodeWithContentDescription("Dormancy window").assertIsOff()
        composeTestRule.onNodeWithText("Start").assertDoesNotExist()
        composeTestRule.runOnIdle {
            assertEquals(null, start)
            assertEquals(null, end)
        }
    }

    @Test
    fun wrappingRange_canBeChangedWithMonthDropdowns() {
        var start by mutableStateOf<Int?>(11)
        var end by mutableStateOf<Int?>(2)
        composeTestRule.setContent {
            DormancyWindowSetting(start, end) { nextStart, nextEnd ->
                start = nextStart
                end = nextEnd
            }
        }

        composeTestRule.onNodeWithContentDescription("Start: November").performClick()
        composeTestRule.onNodeWithText("October").performClick()
        composeTestRule.onNodeWithContentDescription("End: February").performClick()
        composeTestRule.onNodeWithText("March").performClick()
        composeTestRule.onNodeWithContentDescription("Start: October").assertExists()
        composeTestRule.onNodeWithContentDescription("End: March").assertExists()
        composeTestRule.runOnIdle {
            assertEquals(10, start)
            assertEquals(3, end)
        }
    }

    @Test
    fun rapidSelections_keepTheLatestPairWhileParentStillEmitsOlderValues() {
        var persistedStart by mutableStateOf<Int?>(11)
        var persistedEnd by mutableStateOf<Int?>(2)
        val writes = mutableListOf<Pair<Int?, Int?>>()
        composeTestRule.setContent {
            DormancyWindowSetting(persistedStart, persistedEnd) { nextStart, nextEnd ->
                writes += nextStart to nextEnd
            }
        }

        composeTestRule.onNodeWithContentDescription("Start: November").performClick()
        composeTestRule.onNodeWithText("October").performClick()
        composeTestRule.onNodeWithContentDescription("Start: October").assertExists()
        composeTestRule.onNodeWithContentDescription("End: February").performClick()
        composeTestRule.onNodeWithText("March").performClick()

        composeTestRule.runOnIdle {
            assertEquals(listOf(10 to 2, 10 to 3), writes)
            persistedStart = 10
        }
        composeTestRule.onNodeWithContentDescription("Start: October").assertExists()
        composeTestRule.onNodeWithContentDescription("End: March").assertExists()

        composeTestRule.runOnIdle { persistedEnd = 3 }
        composeTestRule.onNodeWithContentDescription("Start: October").assertExists()
        composeTestRule.onNodeWithContentDescription("End: March").assertExists()
    }

    @Test
    fun dormantWatering_offersOnlySupportedCadences_andDisablingClearsSelection() {
        var start by mutableStateOf<Int?>(11)
        var end by mutableStateOf<Int?>(2)
        var cadence by mutableStateOf<Int?>(null)
        composeTestRule.setContent {
            DormancyWindowSetting(start, end, cadence) { nextStart, nextEnd, nextCadence ->
                start = nextStart
                end = nextEnd
                cadence = nextCadence
            }
        }

        composeTestRule.onNodeWithText("Every 28 days").assertExists()
        composeTestRule.onNodeWithText("Every 35 days").assertExists().performClick()
        composeTestRule.onNodeWithText("Every 42 days").assertExists()
        composeTestRule.onNodeWithText("Every 30 days").assertDoesNotExist()
        composeTestRule.runOnIdle { assertEquals(35, cadence) }

        composeTestRule.onNodeWithContentDescription("Dormancy window").performClick()
        composeTestRule.runOnIdle { assertEquals(null, cadence) }
    }
}
