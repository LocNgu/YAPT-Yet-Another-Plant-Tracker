package com.yapt.planttracker.ui.screens.plantdetail

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PhotoReminderTest {

    private val now = LocalDate.of(2026, 6, 28)
    private fun daysAgo(days: Long): Long =
        now.minusDays(days)
            .atStartOfDay(java.time.ZoneId.of("UTC"))
            .toInstant()
            .toEpochMilli()

    @Test
    fun `no photos and plant is 29 days old does not show reminder`() {
        assertFalse(
            PlantDetailViewModel.shouldShowPhotoReminder(
                lastPhotoTimestampMs = null,
                plantCreatedAtMs = daysAgo(29),
                nowDate = now
            )
        )
    }

    @Test
    fun `no photos and plant is exactly 30 days old shows reminder`() {
        assertTrue(
            PlantDetailViewModel.shouldShowPhotoReminder(
                lastPhotoTimestampMs = null,
                plantCreatedAtMs = daysAgo(30),
                nowDate = now
            )
        )
    }

    @Test
    fun `no photos and plant is more than 30 days old shows reminder`() {
        assertTrue(
            PlantDetailViewModel.shouldShowPhotoReminder(
                lastPhotoTimestampMs = null,
                plantCreatedAtMs = daysAgo(60),
                nowDate = now
            )
        )
    }

    @Test
    fun `last photo from plant_photos 35 days ago shows reminder`() {
        assertTrue(
            PlantDetailViewModel.shouldShowPhotoReminder(
                lastPhotoTimestampMs = daysAgo(35),
                plantCreatedAtMs = daysAgo(90),
                nowDate = now
            )
        )
    }

    @Test
    fun `last photo from care_log 15 days ago does not show reminder`() {
        assertFalse(
            PlantDetailViewModel.shouldShowPhotoReminder(
                lastPhotoTimestampMs = daysAgo(15),
                plantCreatedAtMs = daysAgo(90),
                nowDate = now
            )
        )
    }

    @Test
    fun `both sources available max timestamp is recent does not show reminder`() {
        // plant_photos has photo 20 days ago, care_log has photo 40 days ago
        // galleryPhotos sorts newest-first, so caller passes daysAgo(20) as lastPhotoTimestampMs
        assertFalse(
            PlantDetailViewModel.shouldShowPhotoReminder(
                lastPhotoTimestampMs = daysAgo(20),
                plantCreatedAtMs = daysAgo(90),
                nowDate = now
            )
        )
    }

    @Test
    fun `both sources available max timestamp is exactly 30 days ago shows reminder`() {
        // plant_photos has photo 30 days ago, care_log has photo 40 days ago
        // galleryPhotos sorts newest-first, so caller passes daysAgo(30) as lastPhotoTimestampMs
        assertTrue(
            PlantDetailViewModel.shouldShowPhotoReminder(
                lastPhotoTimestampMs = daysAgo(30),
                plantCreatedAtMs = daysAgo(90),
                nowDate = now
            )
        )
    }
}
