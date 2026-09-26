package com.yapt.planttracker.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
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
            composeTestRule.onNodeWithText(seasonLabel(season)).assertIsSelected()
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
        composeTestRule.onNodeWithText(seasonLabel(toAdd)).assertIsNotSelected().performClick()

        composeTestRule.runOnIdle { assertEquals(setOf(current, toAdd), selected) }
        composeTestRule.onNodeWithText(seasonLabel(toAdd)).assertIsSelected()
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

        composeTestRule.onNodeWithText(seasonLabel(toRemove)).assertIsEnabled().performClick()

        composeTestRule.runOnIdle { assertEquals(setOf(current), selected) }
    }

    @Test
    fun lastRemainingSelectedChip_staysEnabledAndSelected_tapKeepsSelectionAndLocksInsteadOfToggling() {
        val (current, _) = currentSeasonAndOthers()
        var onToggleCallCount by mutableIntStateOf(0)
        var onLastSeasonLockedCallCount by mutableIntStateOf(0)
        composeTestRule.setContent {
            FertilizingSeasonsSelector(
                selected = setOf(current),
                onToggle = { onToggleCallCount++ },
                onLastSeasonLocked = { onLastSeasonLockedCallCount++ }
            )
        }

        composeTestRule.onNodeWithText(seasonLabel(current))
            .assertIsEnabled()
            .assertIsSelected()
            .performClick()

        composeTestRule.onNodeWithText(seasonLabel(current)).assertIsSelected()
        composeTestRule.runOnIdle {
            assertEquals(0, onToggleCallCount)
            assertEquals(1, onLastSeasonLockedCallCount)
        }
    }

    @Test
    fun lastRemainingSelectedChip_repeatedTaps_eachInvokeTheLockCallback() {
        val (current, _) = currentSeasonAndOthers()
        var onLastSeasonLockedCallCount by mutableIntStateOf(0)
        composeTestRule.setContent {
            FertilizingSeasonsSelector(
                selected = setOf(current),
                onToggle = {},
                onLastSeasonLocked = { onLastSeasonLockedCallCount++ }
            )
        }

        val chip = composeTestRule.onNodeWithText(seasonLabel(current))
        chip.performClick()
        chip.performClick()
        chip.performClick()

        composeTestRule.runOnIdle { assertEquals(3, onLastSeasonLockedCallCount) }
    }

    @Test
    fun currentSeasonChip_showsThePlainSeasonName_withCurrentSeasonInItsSemantics() {
        val (current, _) = currentSeasonAndOthers()
        composeTestRule.setContent {
            FertilizingSeasonsSelector(selected = FertilizingSeason.entries.toSet(), onToggle = {})
        }

        composeTestRule.onNodeWithText(seasonLabel(current)).assertIsSelected()

        val expectedStateDescription = targetContext().getString(R.string.fertilizing_current_season)
        composeTestRule
            .onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, expectedStateDescription))
            .assertIsSelected()
    }
}
