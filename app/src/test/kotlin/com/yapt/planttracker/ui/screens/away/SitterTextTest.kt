package com.yapt.planttracker.ui.screens.away

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** The sitter-flow copy helpers (#899): the "Ask your sitter" text, name lists and the day picker rule. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-rUS")
class SitterTextTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val today = LocalDate.of(2026, 10, 1)

    @Test
    fun `a weekly rhythm asks for about once a week`() {
        assertEquals(
            "Could you come about once a week? The first time by Wed, Oct 7.",
            context.askYourSitterText(7, LocalDate.of(2026, 10, 7), today)
        )
    }

    @Test
    fun `a fortnightly rhythm asks for every two weeks`() {
        assertEquals(
            "Could you come about every two weeks? The first time by Wed, Oct 21.",
            context.askYourSitterText(14, LocalDate.of(2026, 10, 21), today)
        )
    }

    @Test
    fun `any other rhythm asks for every n days`() {
        assertEquals(
            "Could you come about every 5 days? The first time by Sun, Oct 11.",
            context.askYourSitterText(5, LocalDate.of(2026, 10, 11), today)
        )
    }

    @Test
    fun `a few names are listed in full and a long list is cut with a count`() {
        assertEquals("Basil, Fern", context.namesText(listOf("Basil", "Fern")))
        assertEquals(
            "A, B, C, D, E and 2 more",
            context.namesText(listOf("A", "B", "C", "D", "E", "F", "G"))
        )
    }

    @Test
    fun `the date picker accepts only days inside the trip and not in the past`() {
        val earliest = LocalDate.of(2026, 10, 14)
        val latest = LocalDate.of(2026, 10, 20)

        assertFalse(isSelectableVisitDay(LocalDate.of(2026, 10, 13).toDatePickerMillis(), earliest, latest))
        assertTrue(isSelectableVisitDay(earliest.toDatePickerMillis(), earliest, latest))
        assertTrue(isSelectableVisitDay(latest.toDatePickerMillis(), earliest, latest))
        assertFalse(isSelectableVisitDay(LocalDate.of(2026, 10, 21).toDatePickerMillis(), earliest, latest))
    }

    @Test
    fun `the before you leave message says how many were logged and skipped`() {
        assertEquals(
            "Logged 3 plants as watered",
            context.beforeYouLeaveLoggedText(BeforeYouLeaveEvent.Logged(3, 0))
        )
        assertEquals(
            "Logged 1 plant as watered · 2 were already watered today",
            context.beforeYouLeaveLoggedText(BeforeYouLeaveEvent.Logged(1, 2))
        )
    }
}
