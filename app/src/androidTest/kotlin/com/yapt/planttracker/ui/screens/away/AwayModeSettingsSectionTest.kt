package com.yapt.planttracker.ui.screens.away

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.AwayPeriodWithVisits
import com.yapt.planttracker.domain.model.SitterVisit
import com.yapt.planttracker.domain.model.SitterVisitPlant
import com.yapt.planttracker.domain.model.SitterVisitState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class AwayModeSettingsSectionTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val today = LocalDate.now().withMonth(10).withDayOfMonth(14)

    private val saves = mutableListOf<Triple<LocalDate, LocalDate, Long?>>()
    private var cancelCalls = 0
    private var imBackCalls = 0

    private val actions = AwayModeActions(
        onSave = { first, last, id -> saves += Triple(first, last, id) },
        onCancelTrip = { cancelCalls++ },
        onImBack = { imBackCalls++ }
    )

    private fun trip(first: LocalDate, last: LocalDate) = AwayPeriod(id = 7L, firstDay = first, lastDay = last)

    private val opened = mutableListOf<String>()
    private val navigation = AwayModeNavigation(
        onSitterRhythm = { opened += "rhythm" },
        onBeforeYouLeave = { opened += "before" },
        onPlanVisit = { opened += "plan" }
    )

    private fun setSection(periods: List<AwayPeriod>) {
        composeTestRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AwayModeSettingsContent(AwayModeUiState(today, periods), actions, navigation)
            }
        }
    }

    private fun hasStateDescription(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    @Test
    fun withoutATripItOffersToPlanOne() {
        setSection(emptyList())

        composeTestRule.onNodeWithText("Away mode").assertIsDisplayed()
        composeTestRule.onNodeWithText("No trip planned").assert(hasStateDescription("No trip"))
        composeTestRule.onNodeWithText("Edit dates").assertDoesNotExist()
        composeTestRule.onNodeWithText("Plan a trip").assertIsDisplayed()
    }

    @Test
    fun planningOpensTheDateSheetWithSaveDisabledUntilDatesAreChosen() {
        setSection(emptyList())

        composeTestRule.onNodeWithText("Plan a trip").performClick()

        composeTestRule.onNodeWithTag(AWAY_DATE_RANGE_SHEET_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun aPlannedTripReadsItsDatesAndOffersEditAndCancel() {
        setSection(listOf(trip(LocalDate.of(today.year, 10, 20), LocalDate.of(today.year, 10, 30))))

        composeTestRule.onNodeWithText("Oct 20 – Oct 30 · reminders resume Oct 31")
            .assert(hasStateDescription("Trip planned"))
        composeTestRule.onNodeWithText("Edit dates").assertIsDisplayed()
        composeTestRule.onNodeWithText("I'm back").assertDoesNotExist()

        composeTestRule.onNodeWithText("Cancel trip").performClick()

        assertEquals(1, cancelCalls)
    }

    @Test
    fun anActiveTripSaysAwayAndOffersImBack() {
        setSection(listOf(trip(LocalDate.of(today.year, 10, 10), LocalDate.of(today.year, 10, 21))))

        composeTestRule.onNodeWithText("Away now · Oct 10 – Oct 21 · reminders resume Oct 22")
            .assert(hasStateDescription("Away"))
        composeTestRule.onNodeWithText("Cancel trip").assertDoesNotExist()

        composeTestRule.onNodeWithText("I'm back").performScrollTo().performClick()

        assertEquals(1, imBackCalls)
    }

    @Test
    fun editingAnActiveTripPreselectsItsDatesAndSavesThemUnderTheTripId() {
        setSection(listOf(trip(LocalDate.of(today.year, 10, 10), LocalDate.of(today.year, 10, 21))))

        composeTestRule.onNodeWithText("Edit dates").performClick()
        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals(
            listOf(Triple(LocalDate.of(today.year, 10, 10), LocalDate.of(today.year, 10, 21), 7L)),
            saves
        )
    }

    private fun showSheet(periods: List<AwayPeriod>, first: LocalDate, last: LocalDate, editingId: Long? = null) {
        composeTestRule.setContent {
            AwayModeDateRangeSheet(
                state = AwayModeUiState(today, periods),
                editingId = editingId,
                initialRange = first to last,
                onDismiss = {},
                onSave = { f, l -> saves += Triple(f, l, editingId) }
            )
        }
    }

    private fun assertRejectedWith(message: String) {
        composeTestRule.onNodeWithText(message).assertIsDisplayed()
        composeTestRule.onNodeWithText("Save").assertIsNotEnabled()
    }

    @Test
    fun aLastDayInThePastShowsItsOwnMessage() {
        showSheet(emptyList(), today.minusDays(6), today.minusDays(2))

        assertRejectedWith(context.getString(R.string.away_error_ends_in_past))
    }

    @Test
    fun aTripOverNinetyDaysShowsItsOwnMessage() {
        showSheet(emptyList(), today.plusDays(1), today.plusDays(91))

        assertRejectedWith(context.getString(R.string.away_error_too_long, 90))
    }

    @Test
    fun anOverlapShowsWhichTripItOverlaps() {
        val existing = trip(LocalDate.of(today.year, 11, 1), LocalDate.of(today.year, 11, 10))
        showSheet(listOf(existing), LocalDate.of(today.year, 11, 5), LocalDate.of(today.year, 11, 15))

        assertRejectedWith(context.getString(R.string.away_error_overlaps, "Nov 1", "Nov 10"))
    }

    @Test
    fun anotherPlannedTripShowsItsOwnMessage() {
        val existing = trip(LocalDate.of(today.year, 11, 1), LocalDate.of(today.year, 11, 10))
        showSheet(listOf(existing), LocalDate.of(today.year, 11, 20), LocalDate.of(today.year, 11, 25))

        assertRejectedWith(context.getString(R.string.away_error_another_trip, "Nov 1", "Nov 10"))
    }

    @Test
    fun aValidRangeSavesExactlyTheChosenDays() {
        val first = LocalDate.of(today.year, 11, 3)
        val last = LocalDate.of(today.year, 11, 9)
        showSheet(emptyList(), first, last)

        composeTestRule.onNodeWithText("Save").assertIsEnabled().performClick()

        assertTrue(saves.single().let { it.first == first && it.second == last && it.third == null })
    }

    @Test
    fun withoutATripThereAreNoSitterEntryPoints() {
        setSection(emptyList())

        composeTestRule.onNodeWithText("Sitter rhythm").assertDoesNotExist()
        composeTestRule.onNodeWithText("Plan a sitter visit").assertDoesNotExist()
        composeTestRule.onNodeWithText("Before you leave").assertDoesNotExist()
    }

    @Test
    fun aTripOffersTheRhythmAndPlanningAVisit() {
        setSection(listOf(trip(today.minusDays(2), today.plusDays(7))))

        composeTestRule.onNodeWithText("Sitter rhythm").performScrollTo().performClick()
        composeTestRule.onNodeWithText("Plan a sitter visit").performScrollTo().performClick()

        assertEquals(listOf("rhythm", "plan"), opened)
        composeTestRule.onNodeWithText("Before you leave").assertDoesNotExist()
    }

    @Test
    fun beforeYouLeaveIsOfferedOnTheLastDayAtHome() {
        setSection(listOf(trip(today.plusDays(1), today.plusDays(8))))

        composeTestRule.onNodeWithText("Before you leave").performScrollTo().performClick()

        assertEquals(listOf("before"), opened)
    }

    @Test
    fun beforeYouLeaveIsOfferedDuringTheLeadInBeforeTheLastDay() {
        setSection(listOf(trip(today.plusDays(2), today.plusDays(8))))

        composeTestRule.onNodeWithText("Before you leave").performScrollTo().performClick()

        assertEquals(listOf("before"), opened)
    }

    @Test
    fun beforeYouLeaveIsOfferedAWeekAhead() {
        setSection(listOf(trip(today.plusDays(7), today.plusDays(12))))

        composeTestRule.onNodeWithText("Before you leave").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun beforeYouLeaveIsNotOfferedMoreThanAWeekAhead() {
        setSection(listOf(trip(today.plusDays(8), today.plusDays(12))))

        composeTestRule.onNodeWithText("Before you leave").assertDoesNotExist()
        composeTestRule.onNodeWithText("Sitter rhythm").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun beforeYouLeaveIsNotOfferedOnTheFirstDayOfTheTrip() {
        setSection(listOf(trip(today, today.plusDays(5))))

        composeTestRule.onNodeWithText("Before you leave").assertDoesNotExist()
        composeTestRule.onNodeWithText("Plan a sitter visit").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theTripRowSaysHowOftenTheSitterComes() {
        setSection(listOf(trip(today.minusDays(2), today.plusDays(7)).copy(visitEveryDays = 10)))

        composeTestRule.onNodeWithText("Sitter visits: about every 10 days").assertIsDisplayed()
    }

    @Test
    fun theTripRowSaysWhenNoRhythmIsChosenYet() {
        setSection(listOf(trip(today.minusDays(2), today.plusDays(7))))

        composeTestRule.onNodeWithText("Sitter visits: how often isn't set yet").assertIsDisplayed()
    }

    private val confirmRequests = mutableListOf<Long>()

    private fun visit(
        id: Long,
        day: LocalDate,
        state: SitterVisitState,
        vararg watered: Boolean?
    ) = SitterVisit(
        id = id,
        awayPeriodId = 7L,
        day = day,
        state = state,
        plants = watered.mapIndexed { index, value -> SitterVisitPlant(index + 1L, value) }
    )

    private fun setSectionWithVisits(period: AwayPeriod, visits: List<SitterVisit>) {
        composeTestRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AwayModeSettingsContent(
                    AwayModeUiState(today, listOf(period), listOf(AwayPeriodWithVisits(period, visits))),
                    actions,
                    navigation,
                    onConfirmVisit = { confirmRequests += it }
                )
            }
        }
    }

    @Test
    fun theTripsVisitsAreListedWithTheirState() {
        val period = trip(today.minusDays(10), today.minusDays(1))
        setSectionWithVisits(
            period,
            listOf(
                visit(1L, today.minusDays(8), SitterVisitState.CONFIRMED, true, false),
                visit(2L, today.minusDays(5), SitterVisitState.DID_NOT_HAPPEN, null),
                visit(3L, today.minusDays(2), SitterVisitState.PLANNED, null, null, null)
            )
        )

        composeTestRule.onNodeWithText("Sitter visits").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("Confirmed · 1 of 2 plants watered").performScrollTo()
            .assert(hasStateDescription("Confirmed"))
        composeTestRule.onNodeWithText("Didn't happen · 1 plant").performScrollTo()
            .assert(hasStateDescription("Didn't happen"))
        composeTestRule.onNodeWithText("Planned · 3 plants").performScrollTo()
            .assert(hasStateDescription("Planned"))
    }

    @Test
    fun aPlannedVisitCanBeConfirmedAfterTheTripAndAnAnsweredOneCannot() {
        val period = trip(today.minusDays(10), today.minusDays(1))
        setSectionWithVisits(
            period,
            listOf(
                visit(1L, today.minusDays(8), SitterVisitState.CONFIRMED, true),
                visit(3L, today.minusDays(2), SitterVisitState.PLANNED, null)
            )
        )

        composeTestRule.onAllNodesWithText("Confirm").assertCountEquals(1)
        composeTestRule.onNodeWithText("Confirm").performScrollTo().performClick()

        assertEquals(listOf(3L), confirmRequests)
    }

    @Test
    fun aPlannedVisitCanBeConfirmedDuringTheTripButNotBeforeItsDay() {
        val period = trip(today.minusDays(3), today.plusDays(5))
        setSectionWithVisits(
            period,
            listOf(
                visit(1L, today.minusDays(1), SitterVisitState.PLANNED, null),
                visit(2L, today.plusDays(2), SitterVisitState.PLANNED, null)
            )
        )

        composeTestRule.onAllNodesWithText("Confirm").assertCountEquals(1)
        composeTestRule.onNodeWithText("Confirm").performScrollTo().performClick()

        assertEquals(listOf(1L), confirmRequests)
    }
}
