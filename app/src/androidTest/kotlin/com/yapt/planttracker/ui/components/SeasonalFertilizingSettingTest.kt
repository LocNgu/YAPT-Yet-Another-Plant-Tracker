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
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.SeasonalFertilizing
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class SeasonalFertilizingSettingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun sameForAllToggle_revealsFields_acceptsAndClearsAnOverride() {
        var sameForAll by mutableStateOf(true)
        var summer by mutableStateOf<Int?>(null)
        composeTestRule.setContent {
            SeasonalFertilizingSetting(
                sameForAllSeasons = sameForAll,
                fallbackDays = 30,
                intervalForSeason = { if (it == FertilizingSeason.SUMMER) summer else null },
                onSameForAllSeasonsChange = { sameForAll = it },
                onIntervalChange = { season, days -> if (season == FertilizingSeason.SUMMER) summer = days }
            )
        }

        val toggle = composeTestRule.onNodeWithContentDescription("Same for all seasons")
        toggle.assertIsOn().performClick()
        toggle.assertIsOff()
        composeTestRule.onNodeWithText("Spring", substring = true).assertExists()
        composeTestRule.onNodeWithText("Winter", substring = true).assertExists()

        val current = SeasonalFertilizing.season(LocalDate.now(), SeasonalWatering.currentHemisphere())
        val summerDescription = if (current == FertilizingSeason.SUMMER) "Summer · Current season" else "Summer"
        val summerField = composeTestRule.onNodeWithContentDescription(summerDescription)
        summerField.performTextInput("14")
        composeTestRule.runOnIdle { assertEquals(14, summer) }
        summerField.performTextClearance()
        composeTestRule.runOnIdle { assertEquals(null, summer) }
    }

    @Test
    fun summary_showsOverridesFallbackAndEditAction() {
        var editClicked = false
        composeTestRule.setContent {
            SeasonalFertilizingSummary(
                plant = Plant(
                    name = "Fern",
                    fertilizingIntervalDays = 30,
                    fertilizingIntervalSummer = 14,
                    dormancyStartMonth = 11,
                    dormancyEndMonth = 2
                ),
                onEdit = { editClicked = true }
            )
        }

        composeTestRule.onNodeWithText("Spring: 30 days (main interval)").assertExists()
        composeTestRule.onNodeWithText("Summer: 14 days").assertExists()
        composeTestRule.onNodeWithText(
            "Fertilizing reminders pause during this plant’s dormancy window."
        ).assertExists()
        composeTestRule.onNodeWithText("Edit seasonal schedule").performClick()
        composeTestRule.runOnIdle { assertTrue(editClicked) }
    }
}
