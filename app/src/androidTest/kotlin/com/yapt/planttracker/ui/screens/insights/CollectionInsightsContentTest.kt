package com.yapt.planttracker.ui.screens.insights

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.insights.ActiveIssueItem
import com.yapt.planttracker.domain.insights.CareCountItem
import com.yapt.planttracker.domain.insights.CollectionInsights
import com.yapt.planttracker.domain.insights.LateWateringItem
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantIssue
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CollectionInsightsContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val fern = Plant(id = 1, name = "Fern")
    private val aloe = Plant(id = 2, name = "Aloe")

    private fun setContent(
        insights: CollectionInsights,
        onOpen: (Long, PlantDetailTab) -> Unit = { _, _ -> }
    ) = composeTestRule.setContent { CollectionInsightsContent(insights, onOpen) }

    private fun scrollTo(text: String) {
        composeTestRule.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
    }

    @Test
    fun emptySectionsEachShowANothingToActOnMessage() {
        setContent(CollectionInsights(emptyList(), emptyList(), emptyList()))

        composeTestRule.onNodeWithText(str(R.string.collection_insights_late_empty)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.collection_insights_care_empty)).assertIsDisplayed()
        scrollTo(str(R.string.collection_insights_issues_empty))
        composeTestRule.onNodeWithText(str(R.string.collection_insights_issues_empty)).assertIsDisplayed()
    }

    @Test
    fun lateRowsShowCountAndOpenTheWaterTab() {
        var opened: Pair<Long, PlantDetailTab>? = null
        setContent(
            CollectionInsights(listOf(LateWateringItem(fern, 3)), emptyList(), emptyList()),
            onOpen = { id, tab -> opened = id to tab }
        )

        composeTestRule.onNodeWithText("Watered late 3 times").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fern").performClick()

        assertEquals(1L to PlantDetailTab.WATER, opened)
    }

    @Test
    fun careCountsListEachTypeWithItsCount() {
        setContent(
            CollectionInsights(
                emptyList(),
                listOf(CareCountItem(CareType.WATER, 12), CareCountItem(CareType.PRUNE, 1)),
                emptyList()
            )
        )

        composeTestRule.onNodeWithText("Watered: 12").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pruned: 1").assertIsDisplayed()
    }

    @Test
    fun issueRowsShowDurationAndOpenTheIssuesTab() {
        var opened: Pair<Long, PlantDetailTab>? = null
        val issues = listOf(
            ActiveIssueItem(aloe, PlantIssue(id = 5, plantId = 2, name = "Mites"), daysActive = 12),
            ActiveIssueItem(fern, PlantIssue(id = 6, plantId = 1, name = "Gnats"), daysActive = 0)
        )
        setContent(
            CollectionInsights(emptyList(), emptyList(), issues),
            onOpen = { id, tab -> opened = id to tab }
        )

        scrollTo("Aloe · Active for 12 days")
        composeTestRule.onNodeWithText("Aloe · Active for 12 days").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fern · Started today").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mites").performClick()

        assertEquals(2L to PlantDetailTab.ISSUES, opened)
    }
}
