package com.yapt.planttracker.ui.screens.away

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.domain.away.AwayPeriodValidation
import com.yapt.planttracker.domain.model.AwayPeriod
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale
import java.util.TimeZone

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AwayModeTextTest {

    private lateinit var originalZone: TimeZone
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val today = LocalDate.of(2026, 10, 14)
    private val other = AwayPeriod(1L, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 21))

    @Before
    fun setUp() {
        originalZone = TimeZone.getDefault()
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalZone)
    }

    @Test
    fun `a day in the current year drops the year`() {
        assertEquals("Oct 21", awayDayText(LocalDate.of(2026, 10, 21), today, Locale.US))
    }

    @Test
    fun `a day in another year keeps the year`() {
        assertEquals("Jan 4, 2027", awayDayText(LocalDate.of(2027, 1, 4), today, Locale.US))
    }

    @Test
    fun `the picker decodes UTC midnight to the same calendar day in any zone`() {
        val day = LocalDate.of(2026, 10, 21)
        for (zone in listOf("Pacific/Kiritimati", "America/Los_Angeles", "UTC")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            assertEquals(zone, day, day.toDatePickerMillis().datePickerMillisToLocalDate())
        }
    }

    @Test
    fun `the picker encodes a local day as UTC midnight`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))

        assertEquals(1_792_540_800_000L, LocalDate.of(2026, 10, 21).toDatePickerMillis())
    }

    @Test
    fun `every validation failure has its own message`() {
        val messages = listOf(
            AwayPeriodValidation.FirstDayAfterLastDay,
            AwayPeriodValidation.EndsInThePast,
            AwayPeriodValidation.TooLong,
            AwayPeriodValidation.OverlapsTrip(other),
            AwayPeriodValidation.AnotherCurrentOrUpcomingTrip(other)
        ).map { context.awayValidationText(it, today) }

        assertEquals(messages.size, messages.toSet().size)
        assertTrue(messages.all { it.isNotBlank() })
        assertEquals("", context.awayValidationText(AwayPeriodValidation.Valid, today))
    }

    @Test
    fun `the conflict messages name the other trip's dates`() {
        val overlap = context.awayValidationText(AwayPeriodValidation.OverlapsTrip(other), today)
        val another = context.awayValidationText(AwayPeriodValidation.AnotherCurrentOrUpcomingTrip(other), today)

        assertTrue(overlap.contains("Oct 1") && overlap.contains("Oct 21"))
        assertTrue(another.contains("Oct 1") && another.contains("Oct 21"))
    }

    @Test
    fun `removed visits are counted with the right plural`() {
        assertEquals(
            "Removed 1 sitter visit that fell outside your trip",
            context.awayEventText(AwayModeEvent.VisitsRemoved(1), today)
        )
        assertEquals(
            "Removed 3 sitter visits that fell outside your trip",
            context.awayEventText(AwayModeEvent.VisitsRemoved(3), today)
        )
    }
}
