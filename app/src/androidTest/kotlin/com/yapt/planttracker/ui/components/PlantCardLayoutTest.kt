package com.yapt.planttracker.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The shared [PlantCardLayout] shell (#980). Assertions are on user-visible semantics only (#420):
 * the card's clickable modifier merges its descendants into one node, so the tests find it through
 * its name text and never assert tree structure or testTags.
 */
@RunWith(AndroidJUnit4::class)
class PlantCardLayoutTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun selectionMode_tapTogglesSelectedSemantics() {
        var selected by mutableStateOf(false)
        composeTestRule.setContent {
            PlantCardLayout(
                name = "Monstera",
                species = null,
                photoUri = null,
                onClick = {},
                selectionMode = true,
                selected = selected,
                onToggleSelect = { selected = !selected }
            )
        }

        composeTestRule.onNodeWithText("Monstera").assertIsNotSelected()
        composeTestRule.onNodeWithText("Monstera").performClick()
        composeTestRule.onNodeWithText("Monstera").assertIsSelected()
        composeTestRule.onNodeWithText("Monstera").performClick()
        composeTestRule.onNodeWithText("Monstera").assertIsNotSelected()
    }

    @Test
    fun nullLongClick_exposesNoLongClickAction() {
        composeTestRule.setContent {
            PlantCardLayout(
                name = "Monstera",
                species = null,
                photoUri = null,
                onClick = {},
                onLongClick = null
            )
        }

        composeTestRule.onNodeWithText("Monstera").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    @Test
    fun nonNullLongClick_exposesLongClickActionLabelledSelectPlant() {
        val expectedLabel = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.cd_bulk_select_plant)
        composeTestRule.setContent {
            PlantCardLayout(
                name = "Monstera",
                species = null,
                photoUri = null,
                onClick = {},
                onLongClick = {}
            )
        }

        composeTestRule.onNodeWithText("Monstera").assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
        composeTestRule.onNodeWithText("Monstera").assert(
            SemanticsMatcher("long-click label is \"$expectedLabel\"") {
                it.config.getOrNull(SemanticsActions.OnLongClick)?.label == expectedLabel
            }
        )
    }

    @Test
    fun trailingSlot_contentIsShown() {
        composeTestRule.setContent {
            PlantCardLayout(
                name = "Monstera",
                species = null,
                photoUri = null,
                onClick = {},
                trailing = { Text("Trailing content") }
            )
        }

        composeTestRule.onNodeWithText("Trailing content").assertIsDisplayed()
    }
}
