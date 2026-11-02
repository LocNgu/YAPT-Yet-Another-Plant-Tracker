package com.yapt.planttracker.ui.screens.away

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
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

    private fun plant(id: Long, name: String, room: String? = null) =
        Plant(id = id, name = name, room = room, createdAt = 0L, updatedAt = 0L)

    private fun context() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun string(id: Int): String = context().getString(id)

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

    @Test
    fun eachRowReadsNameRoomDueDateAndTickTogether() {
        setContent(
            state(
                water = listOf(BeforeYouLeaveRow(plant(1, "Basil", room = "Kitchen"), today, ticked = true)),
                check = listOf(
                    BeforeYouLeaveRow(plant(3, "Cactus", room = "Hall"), today.plusDays(1), ticked = false),
                    BeforeYouLeaveRow(plant(4, "Aloe"), today.plusDays(2), ticked = false)
                )
            )
        )

        composeTestRule.onNode(hasText("Basil") and hasText("Kitchen") and hasText(string(R.string.date_relative_today)))
            .assertIsOn()
        composeTestRule.onNode(hasText("Cactus") and hasText("Hall") and hasText(string(R.string.date_group_tomorrow)))
            .assertIsOff()
        composeTestRule.onNodeWithText("Aloe").assertIsOff()
    }

    @Test
    fun tappingASectionHeaderHidesAndShowsItsPlants() {
        setContent()

        composeTestRule.onNodeWithText("Check the soil · 1").performClick()
        composeTestRule.onNodeWithText("Cactus").assertDoesNotExist()
        composeTestRule.onNodeWithText("Basil").assertIsDisplayed()
        composeTestRule.onNodeWithText("Log 2 as watered").assertIsEnabled()

        composeTestRule.onNodeWithText("Check the soil · 1").performClick()
        composeTestRule.onNodeWithText("Cactus").assertIsOff()
    }

    // --- the lead-in: a read-only hold list (#969, product ADR-0076) ---

    private val lastDay = today.plusDays(4)

    private fun leadIn(
        holds: List<BeforeYouLeaveHoldRow> = listOf(
            BeforeYouLeaveHoldRow(plant(1, "Basil", room = "Kitchen"), today),
            BeforeYouLeaveHoldRow(plant(2, "Fern"), today.plusDays(1))
        )
    ) = BeforeYouLeaveUiState.LeadIn(today, lastDay, holds)

    private fun setLeadInContent(state: BeforeYouLeaveUiState.LeadIn = leadIn()) {
        composeTestRule.setContent { BeforeYouLeaveLeadInContent(state) }
    }

    private fun lastDayText(): String = awayDayText(lastDay, today)

    @Test
    fun leadInListsThePlantsToHoldUnderTheirHeadingWithAHint() {
        setLeadInContent()

        composeTestRule.onNodeWithText("Hold for your last day · 2").assertIsDisplayed()
        composeTestRule.onNodeWithText(context().getString(R.string.before_leave_hold_hint, lastDayText()))
            .assertIsDisplayed()
        composeTestRule.onNode(
            hasText("Basil") and hasText("Kitchen") and hasText(string(R.string.date_relative_today))
        ).assertIsDisplayed()
        composeTestRule.onNode(hasText("Fern") and hasText(string(R.string.date_group_tomorrow)))
            .assertIsDisplayed()
    }

    @Test
    fun leadInRowsCannotBeTickedOrTapped() {
        setLeadInContent()

        composeTestRule.onNode(hasText("Basil") and hasText("Kitchen"))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
            .assertHasNoClickAction()
        composeTestRule.onNodeWithText("Fern")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
            .assertHasNoClickAction()
    }

    @Test
    fun leadInHasNoLogActionAndNoLastDayLists() {
        setLeadInContent()

        composeTestRule.onNodeWithText("as watered", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Water today", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Check the soil", substring = true).assertDoesNotExist()
    }

    @Test
    fun leadInWithNothingToHoldSaysWhenToComeBack() {
        setLeadInContent(leadIn(holds = emptyList()))

        composeTestRule.onNodeWithText("Nothing to hold. Come back on ${lastDayText()} for your last-day plan.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Hold for your last day", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("as watered", substring = true).assertDoesNotExist()
    }

    @Test
    fun tappingTheHoldHeadingHidesAndShowsItsPlants() {
        setLeadInContent()

        composeTestRule.onNodeWithText("Hold for your last day · 2").performClick()
        composeTestRule.onNodeWithText("Fern").assertDoesNotExist()
        composeTestRule.onNodeWithText(context().getString(R.string.before_leave_hold_hint, lastDayText()))
            .assertIsDisplayed()

        composeTestRule.onNodeWithText("Hold for your last day · 2").performClick()
        composeTestRule.onNodeWithText("Fern").assertIsDisplayed()
    }
}
