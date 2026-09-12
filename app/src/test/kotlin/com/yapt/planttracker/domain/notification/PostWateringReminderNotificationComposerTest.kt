package com.yapt.planttracker.domain.notification

import com.yapt.planttracker.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class PostWateringReminderNotificationComposerTest {

    private val zone = ZoneId.systemDefault()

    @Test
    fun `same local calendar day is eligible for a reminder`() {
        val now = LocalDate.of(2026, 9, 8).atTime(20, 0).atZone(zone).toInstant().toEpochMilli()
        val loggedAt = LocalDate.of(2026, 9, 8).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()

        assertTrue(PostWateringReminderNotificationComposer.shouldSchedule(loggedAt, now))
    }

    @Test
    fun `backdated watering is not eligible for a reminder`() {
        val now = LocalDate.of(2026, 9, 8).atTime(8, 0).atZone(zone).toInstant().toEpochMilli()
        val loggedAt = LocalDate.of(2026, 9, 7).atTime(23, 59).atZone(zone).toInstant().toEpochMilli()

        assertFalse(PostWateringReminderNotificationComposer.shouldSchedule(loggedAt, now))
    }

    @Test
    fun `compose selects the standing-water title and body resources`() {
        val content = PostWateringReminderNotificationComposer.compose()

        assertEquals(R.string.post_watering_notification_title, content.titleRes)
        assertEquals(R.string.post_watering_notification_body, content.bodyRes)
    }
}
