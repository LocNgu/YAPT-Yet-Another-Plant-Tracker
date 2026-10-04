package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.ui.theme.YaptTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BulkActionBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    // Wider than any screen, so the chip row composes every action rather than only the visible ones.
    private fun setBarContent(onCareAction: (CareType) -> Unit = {}) {
        composeTestRule.setContent {
            YaptTheme {
                Box(Modifier.requiredWidth(1000.dp)) {
                    BulkActionBar(
                        selectedCount = 2,
                        onCareAction = onCareAction,
                        onMoveToGraveyard = {}
                    )
                }
            }
        }
    }

    @Test
    fun offersWaterFertilizePruneAndRepot() {
        setBarContent()

        composeTestRule.onNodeWithText("Water").assertExists()
        composeTestRule.onNodeWithText("Fertilize").assertExists()
        composeTestRule.onNodeWithText("Prune").assertExists()
        composeTestRule.onNodeWithText("Repot").assertExists()
    }

    @Test
    fun offersNoMistAction() {
        setBarContent()

        // Misting is retired for new logs (#875, product ADR-0061).
        composeTestRule.onNodeWithText("Mist").assertDoesNotExist()
    }

    @Test
    fun tappingAnActionReportsItsCareType() {
        val reported = mutableListOf<CareType>()
        setBarContent(onCareAction = { reported.add(it) })

        // requiredWidth centres the overflow, so a touch-click could land off-screen; invoke the action.
        composeTestRule.onNodeWithText("Prune").performSemanticsAction(SemanticsActions.OnClick)

        composeTestRule.runOnIdle { assertEquals(listOf(CareType.PRUNE), reported) }
    }
}
