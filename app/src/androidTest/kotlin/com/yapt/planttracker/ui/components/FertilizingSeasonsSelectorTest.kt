package com.yapt.planttracker.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.SeasonalFertilizing
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.ui.util.labelRes
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class FertilizingSeasonsSelectorTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun targetContext() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun seasonLabel(season: FertilizingSeason): String = targetContext().getString(season.labelRes())

    private fun currentSeasonAndOthers(): Pair<FertilizingSeason, List<FertilizingSeason>> {
        val hemisphere = SeasonalWatering.currentHemisphere()
        val current = SeasonalFertilizing.season(LocalDate.now(), hemisphere)
        return current to FertilizingSeason.entries.filterNot { it == current }
    }

    @Test
    fun everySeasonSelected_everyChipShowsSelected() {
        composeTestRule.setContent {
            FertilizingSeasonsSelector(selected = FertilizingSeason.entries.toSet(), onToggle = {})
        }

        FertilizingSeason.entries.forEach { season ->
            composeTestRule.onNodeWithText(seasonLabel(season), substring = true).assertIsSelected()
        }
    }

    @Test
    fun unselectedChip_showsNotSelected_andTapAddsItToTheSelection() {
        val (current, others) = currentSeasonAndOthers()
        var selected by mutableStateOf(setOf(current))
        composeTestRule.setContent {
            FertilizingSeasonsSelector(
                selected = selected,
                onToggle = { season -> selected = if (season in selected) selected - season else selected + season }
            )
        }

        val toAdd = others.first()
        composeTestRule.onNodeWithText(seasonLabel(toAdd), substring = true).assertIsNotSelected().performClick()

        composeTestRule.runOnIdle { assertEquals(setOf(current, toAdd), selected) }
        composeTestRule.onNodeWithText(seasonLabel(toAdd), substring = true).assertIsSelected()
    }

    @Test
    fun selectedChip_withMoreThanOneActive_tapRemovesItFromTheSelection() {
        val (current, others) = currentSeasonAndOthers()
        val toRemove = others.first()
        var selected by mutableStateOf(setOf(current, toRemove))
        composeTestRule.setContent {
            FertilizingSeasonsSelector(
                selected = selected,
                onToggle = { season -> selected = if (season in selected) selected - season else selected + season }
            )
        }

        composeTestRule.onNodeWithText(seasonLabel(toRemove), substring = true).assertIsEnabled().performClick()

        composeTestRule.runOnIdle { assertEquals(setOf(current), selected) }
    }

    @Test
    fun lastRemainingSelectedChip_isDisabled_soATapCannotDeselectIt() {
        val (current, _) = currentSeasonAndOthers()
        composeTestRule.setContent {
            FertilizingSeasonsSelector(selected = setOf(current), onToggle = {})
        }

        // Disabled is the user-visible (and screen-reader-announced) signal that this chip's tap
        // is a no-op — a disabled Compose node exposes no click action at all, so there is nothing
        // further to assert about the tap itself.
        composeTestRule.onNodeWithText(seasonLabel(current), substring = true)
            .assertIsSelected()
            .assertIsNotEnabled()
    }

    @Test
    fun currentSeasonChip_announcesItselfAsTheCurrentSeason() {
        val (current, _) = currentSeasonAndOthers()
        composeTestRule.setContent {
            FertilizingSeasonsSelector(selected = FertilizingSeason.entries.toSet(), onToggle = {})
        }

        val expectedLabel = targetContext().getString(R.string.fertilizing_current_season, seasonLabel(current))
        composeTestRule.onNodeWithText(expectedLabel).assertIsSelected()
    }
}
