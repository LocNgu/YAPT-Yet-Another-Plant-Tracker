package com.yapt.planttracker.ui.screens.away

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.AwayPeriod
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

    private fun setSection(periods: List<AwayPeriod>) {
        composeTestRule.setContent {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                AwayModeSettingsContent(AwayModeUiState(today, periods), actions)
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
}
