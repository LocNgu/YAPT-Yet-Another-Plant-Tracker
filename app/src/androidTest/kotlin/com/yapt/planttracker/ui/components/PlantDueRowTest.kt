package com.yapt.planttracker.ui.components

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
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
}
