package com.yapt.planttracker.ui.screens.away

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.domain.away.ProjectedPlant
import com.yapt.planttracker.domain.away.VisitProjection
import com.yapt.planttracker.domain.away.summary
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PlanVisitContentTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val today = LocalDate.now()
    private val picked = mutableListOf<LocalDate>()
    private val gaps = mutableListOf<Int>()
    private var pdfShares = 0
    private var textShares = 0

    private fun plant(id: Long, name: String, room: String? = null, photo: String? = "content://p/$id") = Plant(
        id = id,
        name = name,
        room = room,
        coverPhotoUri = photo,
        wateringIntervalDays = 5,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun listed(plant: Plant, missed: Boolean = false, longer: Boolean = false) =
        ProjectedPlant(plant, today, usualIntervalDays = 5, missedLastTime = missed, longerThanUsual = longer)

    @Suppress("LongParameterList")
    private fun state(
        listed: List<ProjectedPlant>,
        resting: List<Plant> = emptyList(),
        visitDay: LocalDate = today,
        gap: Int = 7,
        todayAllowed: Boolean = true,
        tomorrowAllowed: Boolean = true,
        sharing: Boolean = false
    ): PlanVisitUiState.Plan {
        val projection = VisitProjection(visitDay, gap, visitDay.plusDays(((gap - 1) / 2).toLong()), listed, resting)
        val earliest = if (todayAllowed) today else today.plusDays(2)
        val latest = if (tomorrowAllowed) today.plusDays(10) else today
        return PlanVisitUiState.Plan(
            today = today,
            trip = AwayPeriod(id = 1L, firstDay = today.minusDays(2), lastDay = latest),
            earliestDay = earliest,
            latestDay = latest,
            visitDay = visitDay,
            gapDays = gap,
            projection = projection,
            summary = projection.summary(),
            sharing = sharing
        )
    }

    private fun setContent(state: PlanVisitUiState.Plan) {
        composeTestRule.setContent {
            PlanVisitContent(
                state,
                PlanVisitActions(
                    onPickDay = { picked += it },
                    onSetGap = { gaps += it },
                    onSharePdf = { pdfShares++ },
                    onShareText = { textShares++ }
                )
            )
        }
    }

    private val twoPlants get() = listOf(listed(plant(1, "Basil", "Kitchen")), listed(plant(2, "Fern")))

    @Test
    fun theWhenChipsOfferTodayTomorrowAndADate() {
        setContent(state(twoPlants))

        composeTestRule.onNodeWithText("Today").assertIsDisplayed().assertIsSelected().assertIsEnabled()
        composeTestRule.onNodeWithText("Tomorrow").assertIsDisplayed().assertIsEnabled()
        composeTestRule.onNodeWithText("Pick a date").assertIsDisplayed()
    }

    @Test
    fun tomorrowReportsTheNextDay() {
        setContent(state(twoPlants))

        composeTestRule.onNodeWithText("Tomorrow").performClick()

        assertEquals(listOf(today.plusDays(1)), picked)
    }

    @Test
    fun todayAndTomorrowAreDisabledWhenTheyAreOutsideTheTrip() {
        setContent(state(twoPlants, visitDay = today.plusDays(2), todayAllowed = false, tomorrowAllowed = true))

        composeTestRule.onNodeWithText("Today").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Tomorrow").assertIsNotEnabled()
    }

    @Test
    fun aPickedDateReplacesThePickADateLabel() {
        setContent(state(twoPlants, visitDay = today.plusDays(5)))

        composeTestRule.onNodeWithText("Pick a date").assertDoesNotExist()
        composeTestRule.onNodeWithText("Today").assertIsNotSelected()
    }

    @Test
    fun theGapSliderShowsDaysAndTheDueByDate() {
        setContent(state(twoPlants, gap = 7))

        composeTestRule.onNodeWithText("in 7 days").assertIsDisplayed()
        composeTestRule.onNodeWithText("The list includes anything due by", substring = true).assertIsDisplayed()
    }

    @Test
    fun theGapButtonsReportOneDayMoreAndLess() {
        setContent(state(twoPlants, gap = 7))

        composeTestRule.onNodeWithContentDescription("More days until the next visit").performClick()
        composeTestRule.onNodeWithContentDescription("Fewer days until the next visit").performClick()

        assertEquals(listOf(8, 6), gaps)
    }

    @Test
    fun theSummaryCardCountsPlantsRoomsAndRestingPlants() {
        setContent(state(twoPlants, resting = listOf(plant(8, "Cactus"), plant(9, "Aloe"))))

        composeTestRule.onNodeWithText("Water 2 plants").assertIsDisplayed()
        composeTestRule.onNodeWithText("Kitchen 1 · No room 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("+ 2 resting").assertIsDisplayed()
    }

    @Test
    fun everyWarningTypeIsShownWithTheNamesItConcerns() {
        val twinA = plant(1, "Basil", "Kitchen", photo = null)
        val twinB = plant(2, "Basil", "Kitchen", photo = null)
        setContent(
            state(
                listOf(
                    listed(twinA, missed = true, longer = true),
                    listed(twinB),
                    listed(plant(3, "Fern", "Kitchen"), longer = true)
                )
            )
        )

        composeTestRule.onNodeWithText("1 plant was missed last time: Basil").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("2 plants go longer than usual: Basil, Fern").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("2 listed plants have no photo: Basil, Basil").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "2 plants share a name and room with another and have no photo to tell them apart: Basil, Basil"
        ).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun withNoWarningsNoNoteIsShown() {
        setContent(state(twoPlants))

        composeTestRule.onNodeWithText("missed last time", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("have no photo", substring = true).assertDoesNotExist()
    }

    @Test
    fun theShareButtonsReportTheirChoice() {
        setContent(state(twoPlants))

        composeTestRule.onNodeWithText("Share list (PDF)").performScrollTo().assertIsEnabled().performClick()
        composeTestRule.onNodeWithText("Share as text").performScrollTo().assertIsEnabled().performClick()

        assertEquals(1, pdfShares)
        assertEquals(1, textShares)
    }

    @Test
    fun withNothingToWaterThereIsNothingToShare() {
        setContent(state(emptyList()))

        composeTestRule.onNodeWithText("No plants need water on this visit").assertIsDisplayed()
        composeTestRule.onNodeWithText("Share list (PDF)").performScrollTo().assertIsNotEnabled()
        composeTestRule.onNodeWithText("Share as text").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun whileSharingBothButtonsAreDisabled() {
        setContent(state(twoPlants, sharing = true))

        composeTestRule.onNodeWithText("Share list (PDF)").performScrollTo().assertIsNotEnabled()
    }
}
