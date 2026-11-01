package com.yapt.planttracker.ui.screens.away

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.SitterVisit
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ConfirmVisitContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val today = LocalDate.now()
    private val toggled = mutableListOf<Long>()
    private var continues = 0
    private var didNotHappen = 0

    private fun plant(id: Long, name: String) = Plant(id = id, name = name, createdAt = 0L, updatedAt = 0L)

    private fun setContent(rows: List<ConfirmRow>) {
        composeTestRule.setContent {
            ConfirmVisitContent(
                state = PlanVisitUiState.Confirm(SitterVisit(id = 1L, awayPeriodId = 1L, day = today.minusDays(2)), rows),
                today = today,
                actions = ConfirmVisitActions(
                    onToggle = { toggled += it },
                    onContinue = { continues++ },
                    onDidNotHappen = { didNotHappen++ }
                )
            )
        }
    }

    private val rows = listOf(ConfirmRow(plant(1, "Basil"), ticked = true), ConfirmRow(plant(2, "Fern"), ticked = false))

    @Test
    fun itListsTheVisitsPlantsWithTheirTickState() {
        setContent(rows)

        composeTestRule.onNodeWithText("Untick the plants the sitter skipped.", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Basil").assertIsOn()
        composeTestRule.onNodeWithText("Fern").assertIsOff()
    }

    @Test
    fun tappingAPlantReportsIt() {
        setContent(rows)

        composeTestRule.onNodeWithText("Fern").performClick()

        assertEquals(listOf(2L), toggled)
    }

    @Test
    fun continueAndTheVisitDidNotHappenAreSeparateActions() {
        setContent(rows)

        composeTestRule.onNodeWithText("Continue").performClick()
        composeTestRule.onNodeWithText("The visit didn't happen").performClick()

        assertEquals(1, continues)
        assertEquals(1, didNotHappen)
    }
}
