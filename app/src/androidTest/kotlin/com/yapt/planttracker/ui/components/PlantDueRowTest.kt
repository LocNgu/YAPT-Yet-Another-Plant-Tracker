package com.yapt.planttracker.ui.components

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
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
class PlantDueRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val plant = Plant(id = 1L, name = "Basil", room = "Kitchen", createdAt = 0L, updatedAt = 0L)

    private fun today(): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.date_relative_today)

    @Test
    fun readOnlyRowReadsNameRoomAndDueDateTogetherWithoutATickState() {
        composeTestRule.setContent { PlantDueRow(plant = plant, dueDate = LocalDate.now()) }

        composeTestRule.onNode(hasText("Basil") and hasText("Kitchen") and hasText(today()))
            .assertIsDisplayed()
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
    }

    @Test
    fun tickedRowReportsTheNewStateWhenClicked() {
        val changes = mutableListOf<Boolean>()
        composeTestRule.setContent {
            PlantDueRow(
                plant = plant,
                dueDate = LocalDate.now(),
                checked = false,
                onCheckedChange = { changes += it }
            )
        }

        composeTestRule.onNode(hasText("Basil") and hasText("Kitchen") and hasText(today()))
            .assertIsOff()
            .performClick()

        assertEquals(listOf(true), changes)
    }

    // --- rows that open the plant (#1018) ---

    private fun opensPlantDetails(): SemanticsMatcher {
        val label = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cd_open_plant_details)
        return SemanticsMatcher("click label is \"$label\"") {
            SemanticsActions.OnClick in it.config && it.config[SemanticsActions.OnClick].label == label
        }
    }

    @Test
    fun aTickedRowThatOpensThePlantOpensItOnTapAndTicksOnlyFromItsCheckbox() {
        val changes = mutableListOf<Boolean>()
        var opens = 0
        composeTestRule.setContent {
            PlantDueRow(
                plant = plant,
                dueDate = LocalDate.now(),
                checked = true,
                onCheckedChange = { changes += it },
                onOpen = { opens++ }
            )
        }

        composeTestRule.onNode(hasText("Basil") and hasText("Kitchen") and hasText(today()))
            .assert(opensPlantDetails())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
            .performClick()
        assertEquals(1, opens)
        assertEquals(emptyList<Boolean>(), changes)

        composeTestRule.onNode(isToggleable() and hasContentDescription("Basil"))
            .assertIsOn()
            .performClick()
        assertEquals(listOf(false), changes)
        assertEquals(1, opens)
    }

    @Test
    fun aReadOnlyRowThatOpensThePlantIsAButtonWithoutATickState() {
        var opens = 0
        composeTestRule.setContent { PlantDueRow(plant = plant, dueDate = LocalDate.now(), onOpen = { opens++ }) }

        composeTestRule.onNode(hasText("Basil") and hasText("Kitchen") and hasText(today()) and hasClickAction())
            .assert(opensPlantDetails())
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
            .performClick()

        assertEquals(1, opens)
    }
}
