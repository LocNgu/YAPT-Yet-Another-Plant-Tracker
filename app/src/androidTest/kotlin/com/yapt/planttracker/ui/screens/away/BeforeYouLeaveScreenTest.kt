package com.yapt.planttracker.ui.screens.away

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.AwayPeriodRepository
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.away.AWAY_BANNER_LEAD_DAYS
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

/** Before you leave through the real ViewModel (#969, product ADR-0076): the window, and the read-only hold list. */
@RunWith(AndroidJUnit4::class)
class BeforeYouLeaveScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val today = LocalDate.now()

    private fun noon(day: LocalDate): Long =
        day.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun trip(first: LocalDate, last: LocalDate) = AwayPeriod(id = 1L, firstDay = first, lastDay = last)

    private fun fern() = Plant(
        id = 1L,
        name = "Fern",
        wateringIntervalDays = 21,
        pinIntervalToBase = true,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun wateredOn(day: LocalDate) = CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = noon(day))

    private fun setScreen(trip: AwayPeriod?, plants: List<Plant> = emptyList(), logs: List<CareLog> = emptyList()) {
        val awayRepository = mockk<AwayPeriodRepository> { every { observeAll() } returns flowOf(listOfNotNull(trip)) }
        val plantRepository = mockk<PlantRepository> { every { getAllPlants() } returns flowOf(plants) }
        val careLogRepository = mockk<CareLogRepository> { every { getAllLogs() } returns flowOf(logs) }
        val viewModel = BeforeYouLeaveViewModel(
            awayRepository,
            plantRepository,
            careLogRepository,
            mockk<QuickLogUseCase>(relaxed = true),
            seasonalAmplitude = flowOf(0.0),
            dayChange = flowOf(today)
        )
        composeTestRule.setContent { BeforeYouLeaveScreen(viewModel, onNavigateBack = {}) }
    }

    private fun notAvailableText(): String {
        val leadDays = AWAY_BANNER_LEAD_DAYS.toInt()
        return InstrumentationRegistry.getInstrumentation().targetContext.resources
            .getQuantityString(R.plurals.before_leave_not_available, leadDays, leadDays)
    }

    @Test
    fun withoutATripItSaysWhenItOpens() {
        setScreen(trip = null)

        composeTestRule.onNodeWithText(notAvailableText()).assertIsDisplayed()
    }

    @Test
    fun moreThanAWeekBeforeTheTripItSaysWhenItOpens() {
        setScreen(trip(today.plusDays(AWAY_BANNER_LEAD_DAYS + 1), today.plusDays(20)))

        composeTestRule.onNodeWithText(notAvailableText()).assertIsDisplayed()
    }

    @Test
    fun onceTheTripHasStartedItSaysWhenItOpens() {
        setScreen(trip(today, today.plusDays(10)))

        composeTestRule.onNodeWithText(notAvailableText()).assertIsDisplayed()
    }

    @Test
    fun beforeTheLastDayItListsWhatToHoldWithNoLogAction() {
        // Watered 20 days ago on a 21-day interval: due tomorrow, and only 3 days late once held to the last day.
        setScreen(
            trip = trip(today.plusDays(5), today.plusDays(25)),
            plants = listOf(fern()),
            logs = listOf(wateredOn(today.minusDays(20)))
        )

        composeTestRule.onNodeWithText("Hold for your last day · 1").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fern").assertIsDisplayed()
        composeTestRule.onNodeWithText("as watered", substring = true).assertDoesNotExist()
    }

    @Test
    fun beforeTheLastDayWithNothingToHoldItSaysSo() {
        setScreen(trip(today.plusDays(5), today.plusDays(25)))

        composeTestRule.onNodeWithText("Nothing to hold", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("as watered", substring = true).assertDoesNotExist()
    }

    @Test
    fun onTheLastDayAtHomeItIsTheLastDayPlan() {
        setScreen(trip(today.plusDays(1), today.plusDays(10)))

        composeTestRule.onNodeWithText("Log 0 as watered").assertIsNotEnabled()
        composeTestRule.onNodeWithText("Nothing to hold", substring = true).assertDoesNotExist()
    }
}
