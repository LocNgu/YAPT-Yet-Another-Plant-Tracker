package com.yapt.planttracker.ui.screens.away

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.domain.away.RhythmEvaluation
import com.yapt.planttracker.domain.away.RhythmFlaggedPlant
import com.yapt.planttracker.domain.away.RhythmOption
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class SitterRhythmContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val firstDay = LocalDate.now().plusDays(3)
    private val saved = mutableListOf<Int>()

    private fun plant(id: Long, name: String) = Plant(id = id, name = name, createdAt = 0L, updatedAt = 0L)

    private val basil = RhythmFlaggedPlant(plant(1, "Basil"), usualIntervalDays = 3)

    private fun state(savedEveryDays: Int? = null) = SitterRhythmUiState.Ready(
        trip = AwayPeriod(id = 1L, firstDay = firstDay, lastDay = firstDay.plusDays(10)),
        evaluation = RhythmEvaluation(
            lastDayAtHome = firstDay.minusDays(1),
            options = listOf(
                RhythmOption(4, emptyList()),
                RhythmOption(5, listOf(basil)),
                RhythmOption(7, listOf(basil)),
                RhythmOption(10, listOf(basil)),
                RhythmOption(14, listOf(basil))
            ),
            recommendedEveryDays = 4
        ),
        savedEveryDays = savedEveryDays
    )

    private fun setContent(savedEveryDays: Int? = null) {
        composeTestRule.setContent { SitterRhythmContent(state(savedEveryDays), onSave = { saved += it }) }
    }

    @Test
    fun itAsksHowOftenAndShowsEachRhythmWithItsFlaggedCount() {
        setContent()

        composeTestRule.onNodeWithText("How often should a sitter come?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Every 4 days").assertIsDisplayed()
        composeTestRule.onNodeWithText("No plants go longer than usual").assertIsDisplayed()
        composeTestRule.onNodeWithText("Every 7 days").assertIsDisplayed()
        composeTestRule.onNodeWithText("Recommended").assertIsDisplayed()
    }

    @Test
    fun theRecommendedRhythmIsPreselectedWhenNothingWasSaved() {
        setContent()

        composeTestRule.onNodeWithText("Every 4 days").assertIsSelected()
    }

    @Test
    fun theSavedRhythmIsPreselectedAndSaveWaitsForAChange() {
        setContent(savedEveryDays = 10)

        composeTestRule.onNodeWithText("Every 10 days").assertIsSelected()
        composeTestRule.onNodeWithText("Save").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun selectingARhythmListsItsFlaggedPlantsWithTheirUsualInterval() {
        setContent()
        composeTestRule.onNodeWithText("Basil (usually every 3 days)").assertDoesNotExist()

        composeTestRule.onNodeWithText("Every 7 days").performClick()

        composeTestRule.onNodeWithText("Every 7 days").assertIsSelected()
        composeTestRule.onNodeWithText("Basil (usually every 3 days)").assertIsDisplayed()
    }

    @Test
    fun theAskYourSitterBoxCarriesCopyableText() {
        setContent()
        composeTestRule.onNodeWithText("Every 7 days").performClick()

        composeTestRule.onNodeWithText("Ask your sitter").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Could you come about once a week?", substring = true)
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Copy").performScrollTo().assertIsEnabled()
    }

    @Test
    fun saveReportsTheChosenRhythm() {
        setContent()
        composeTestRule.onNodeWithText("Every 10 days").performClick()

        composeTestRule.onNodeWithText("Save").performScrollTo().assertIsEnabled().performClick()

        assertEquals(listOf(10), saved)
    }
}
