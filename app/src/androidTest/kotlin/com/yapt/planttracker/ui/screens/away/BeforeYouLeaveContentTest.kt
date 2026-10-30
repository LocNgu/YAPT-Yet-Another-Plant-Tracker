package com.yapt.planttracker.ui.screens.away

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class BeforeYouLeaveContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val today = LocalDate.now()
    private val toggled = mutableListOf<Long>()
    private var logCalls = 0

    private fun plant(id: Long, name: String) = Plant(id = id, name = name, createdAt = 0L, updatedAt = 0L)

    private fun string(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun state(
        water: List<BeforeYouLeaveRow> = listOf(
            BeforeYouLeaveRow(plant(1, "Basil"), today, ticked = true),
            BeforeYouLeaveRow(plant(2, "Fern"), today.plusDays(2), ticked = true)
        ),
        check: List<BeforeYouLeaveRow> = listOf(BeforeYouLeaveRow(plant(3, "Cactus"), today.plusDays(5), ticked = false)),
        canWait: Int = 4
    ) = BeforeYouLeaveUiState.Ready(today, today.plusDays(7), water, check, canWait)

    private fun setContent(state: BeforeYouLeaveUiState.Ready = state()) {
        composeTestRule.setContent {
            BeforeYouLeaveContent(state, onToggle = { toggled += it }, onLog = { logCalls++ })
        }
    }

    @Test
    fun itSplitsThePlantsIntoWaterTodayAndCheckTheSoil() {
        setContent()

        composeTestRule.onNodeWithText("Water today · 2").assertIsDisplayed()
        composeTestRule.onNodeWithText("Check the soil · 1").assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.before_leave_check_soil_hint)).assertIsDisplayed()
        composeTestRule.onNodeWithText("4 other plants can wait").assertIsDisplayed()
    }

    @Test
    fun waterTodayIsTickedAndCheckTheSoilIsNot() {
        setContent()

        composeTestRule.onNodeWithText("Basil").assertIsOn()
        composeTestRule.onNodeWithText("Fern").assertIsOn()
        composeTestRule.onNodeWithText("Cactus").assertIsOff()
    }

    @Test
    fun tappingAPlantReportsItAndTheActionCountsTheTickedOnes() {
        setContent()

        composeTestRule.onNodeWithText("Cactus").performClick()

        assertEquals(listOf(3L), toggled)
        composeTestRule.onNodeWithText("Log 2 as watered").assertIsEnabled().performClick()
        assertEquals(1, logCalls)
    }

    @Test
    fun withNothingTickedTheActionIsDisabled() {
        setContent(
            state(
                water = listOf(BeforeYouLeaveRow(plant(1, "Basil"), today, ticked = false)),
                check = emptyList()
            )
        )

        composeTestRule.onNodeWithText("Log 0 as watered").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Check the soil", substring = true).assertDoesNotExist()
    }

    @Test
    fun theSingularFormsRead() {
        setContent(
            state(
                water = listOf(BeforeYouLeaveRow(plant(1, "Basil"), today, ticked = true)),
                check = emptyList(),
                canWait = 1
            )
        )

        composeTestRule.onNodeWithText("1 other plant can wait").assertIsDisplayed()
        composeTestRule.onNodeWithText("Log 1 as watered").assertIsDisplayed()
    }
}
