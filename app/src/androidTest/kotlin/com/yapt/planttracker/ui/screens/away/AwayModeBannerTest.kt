package com.yapt.planttracker.ui.screens.away

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.data.repository.AwayPeriodRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.TodayCareRepository
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.AwayPeriodWithVisits
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.SitterVisit
import com.yapt.planttracker.domain.model.SitterVisitPlant
import com.yapt.planttracker.domain.model.SitterVisitState
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayQueueSnapshot
import com.yapt.planttracker.domain.today.TodayTaskBucket
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.ui.screens.today.TodayScreen
import com.yapt.planttracker.ui.screens.today.TodayViewModel
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AwayModeBannerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val today = LocalDate.of(LocalDate.now().year, 10, 14)
    private var imBackCalls = 0
    private var planVisitCalls = 0
    private var beforeLeaveCalls = 0

    private fun trip(first: LocalDate, last: LocalDate) = AwayPeriod(id = 3L, firstDay = first, lastDay = last)

    private fun setBanner(periods: List<AwayPeriod>) {
        composeTestRule.setContent {
            AwayModeBannerContent(
                AwayModeUiState(today, periods),
                onImBack = { imBackCalls++ },
                onPlanVisit = { planVisitCalls++ },
                onBeforeYouLeave = { beforeLeaveCalls++ }
            )
        }
    }

    private fun hasStateDescription(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    @Test
    fun whileAwayItNamesTheLastDayAndOffersImBack() {
        setBanner(listOf(trip(today.minusDays(2), LocalDate.of(today.year, 10, 21))))

        composeTestRule.onNodeWithText("Away until Oct 21 · reminders paused")
            .assertIsDisplayed()
            .assert(hasStateDescription("Away"))
        composeTestRule.onNodeWithText("I'm back").performClick()

        assertEquals(1, imBackCalls)
    }

    @Test
    fun whileAwayItOffersToPlanASitterVisit() {
        setBanner(listOf(trip(today.minusDays(2), LocalDate.of(today.year, 10, 21))))

        composeTestRule.onNodeWithText("Plan a sitter visit").assertIsDisplayed().performClick()

        assertEquals(1, planVisitCalls)
        composeTestRule.onNodeWithText("Before you leave").assertDoesNotExist()
    }

    @Test
    fun onTheLastDayAtHomeItOffersBeforeYouLeave() {
        setBanner(listOf(trip(today.plusDays(1), today.plusDays(8))))

        composeTestRule.onNodeWithText("Before you leave").assertIsDisplayed().performClick()

        assertEquals(1, beforeLeaveCalls)
        composeTestRule.onNodeWithText("Plan a sitter visit").assertDoesNotExist()
    }

    @Test
    fun earlierThanTheLastDayAtHomeThereIsNoBeforeYouLeave() {
        setBanner(listOf(trip(today.plusDays(4), today.plusDays(9))))

        composeTestRule.onNodeWithText("Before you leave").assertDoesNotExist()
    }

    @Test
    fun beforeTheTripItSaysWhenRemindersPauseAndHasNoAction() {
        setBanner(listOf(trip(LocalDate.of(today.year, 10, 18), LocalDate.of(today.year, 10, 25))))

        composeTestRule.onNodeWithText("Reminders pause on Oct 18 · away until Oct 25")
            .assertIsDisplayed()
            .assert(hasStateDescription("Trip coming up"))
        composeTestRule.onNodeWithText("I'm back").assertDoesNotExist()
    }

    @Test
    fun aTripMoreThanAWeekAwayShowsNoBanner() {
        setBanner(listOf(trip(today.plusDays(8), today.plusDays(12))))

        composeTestRule.onNodeWithText("Reminders pause", substring = true).assertDoesNotExist()
    }

    @Test
    fun withoutATripOrAfterItEndedThereIsNoBanner() {
        setBanner(listOf(trip(today.minusDays(9), today.minusDays(1))))

        composeTestRule.onNodeWithText("reminders paused", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("I'm back").assertDoesNotExist()
    }

    @Test
    fun careShowsTheBannerAboveTheGridAndImBackEndsTheTrip() {
        val now = LocalDate.now()
        val period = AwayPeriod(id = 3L, firstDay = now.minusDays(1), lastDay = now.plusDays(2))
        val awayRepository = mockk<AwayPeriodRepository>(relaxed = true) {
            every { observeAll() } returns flowOf(listOf(period))
            every { observeAllWithVisits() } returns flowOf(listOf(AwayPeriodWithVisits(period, emptyList())))
            coEvery { getAllWithVisits() } returns listOf(AwayPeriodWithVisits(period, emptyList()))
            coEvery { getAll() } returns listOf(period)
        }
        val awayViewModel = AwayModeViewModel(awayRepository, onTodaysAwayStateChanged = {}, dayChange = flowOf(now))
        val fern = Plant(id = 1L, name = "Fern", createdAt = 0L, updatedAt = 0L)
        val snapshot = TodayQueueSnapshot(
            1,
            listOf(
                TodayCareTask(
                    id = "water:1",
                    plant = fern,
                    kind = TodayCareKind.WATER,
                    dueAt = System.currentTimeMillis(),
                    bucket = TodayTaskBucket.Today
                )
            )
        )
        val todayRepository = mockk<TodayCareRepository> { every { observeQueue() } returns flowOf(snapshot) }
        val viewModel = TodayViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            todayRepository,
            mockk<QuickLogUseCase>(relaxed = true),
            mockk<PlantRepository>(relaxed = true)
        )

        composeTestRule.setContent {
            TodayScreen(
                viewModel = viewModel,
                onNavigateToPlant = { _, _ -> },
                onNavigateToAdd = {},
                awayModeViewModel = awayViewModel
            )
        }

        val banner = composeTestRule.onNodeWithText("reminders paused", substring = true)
        banner.assertIsDisplayed()
        val bannerTop = banner.fetchSemanticsNode().positionInRoot.y
        val gridTop = composeTestRule.onNodeWithText("Watering · 1").fetchSemanticsNode().positionInRoot.y
        assertTrue(bannerTop < gridTop)

        composeTestRule.onNodeWithText("I'm back").performClick()
        composeTestRule.waitForIdle()

        coVerify(timeout = 5_000) { awayRepository.updatePeriodAndDeleteVisits(any(), any()) }
    }

    private val confirmed = mutableListOf<Long>()

    private fun visit(id: Long, day: LocalDate, state: SitterVisitState = SitterVisitState.PLANNED) = SitterVisit(
        id = id,
        awayPeriodId = 3L,
        day = day,
        state = state,
        plants = listOf(SitterVisitPlant(1L))
    )

    private fun setBannerWithVisits(todayOverride: LocalDate, visits: List<SitterVisit>) {
        val period = trip(today.minusDays(10), today.minusDays(1))
        composeTestRule.setContent {
            AwayModeBannerContent(
                AwayModeUiState(todayOverride, listOf(period), listOf(AwayPeriodWithVisits(period, visits))),
                onImBack = {},
                onConfirmVisit = { confirmed += it }
            )
        }
    }

    @Test
    fun afterTheTripItOffersTheUnconfirmedVisitsOldestFirst() {
        setBannerWithVisits(
            today,
            listOf(visit(2L, today.minusDays(3)), visit(1L, today.minusDays(6)))
        )

        composeTestRule.onNodeWithText("2 sitter visits to confirm").assertIsDisplayed()
        composeTestRule.onNodeWithText("Confirm visit").performClick()

        assertEquals(listOf(1L), confirmed)
    }

    @Test
    fun anAnsweredVisitIsNotCounted() {
        setBannerWithVisits(
            today,
            listOf(visit(1L, today.minusDays(6), SitterVisitState.CONFIRMED), visit(2L, today.minusDays(3)))
        )

        composeTestRule.onNodeWithText("1 sitter visit to confirm").assertIsDisplayed()
    }

    @Test
    fun theOfferIsGoneOnceTheWindowHasPassed() {
        setBannerWithVisits(today.plusDays(14), listOf(visit(1L, today.minusDays(6))))

        composeTestRule.onNodeWithText("1 sitter visit to confirm").assertDoesNotExist()
        composeTestRule.onNodeWithText("Confirm visit").assertDoesNotExist()
    }
}
