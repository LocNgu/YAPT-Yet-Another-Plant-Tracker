package com.yapt.planttracker.domain.insights

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.GalleryPhoto
import com.yapt.planttracker.domain.model.GalleryPhotoSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class CareInsightsTest {

    private val day = TimeUnit.DAYS.toMillis(1)
    private val base = 1_700_000_000_000L // 2023-11-14 22:13:20 UTC

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun log(careType: CareType, loggedAt: Long) =
        CareLog(plantId = 1L, careType = careType, loggedAt = loggedAt)

    @Test
    fun `summarize counts only the requested care type`() {
        val logs = listOf(
            log(CareType.WATER, base),
            log(CareType.FERTILIZE, base),
            log(CareType.WATER, base + 5 * day)
        )

        val summary = CareInsights.summarize(logs, CareType.WATER)

        assertEquals(2, summary.count)
        assertEquals(base + 5 * day, summary.lastAt)
    }

    @Test
    fun `summarize with no matching logs is empty`() {
        val summary = CareInsights.summarize(listOf(log(CareType.WATER, base)), CareType.REPOT)

        assertEquals(0, summary.count)
        assertNull(summary.lastAt)
        assertNull(summary.averageIntervalDays)
    }

    @Test
    fun `lastAt is the latest even when logs are unsorted`() {
        val logs = listOf(
            log(CareType.REPOT, base + 10 * day),
            log(CareType.REPOT, base),
            log(CareType.REPOT, base + 3 * day)
        )

        assertEquals(base + 10 * day, CareInsights.summarize(logs, CareType.REPOT).lastAt)
    }

    @Test
    fun `averageIntervalDays is null for fewer than two events`() {
        assertNull(CareInsights.averageIntervalDays(emptyList()))
        assertNull(CareInsights.averageIntervalDays(listOf(base)))
    }

    @Test
    fun `averageIntervalDays is the mean of consecutive day gaps`() {
        // gaps: 2, 4 -> mean 3
        val result = CareInsights.averageIntervalDays(listOf(base, base + 2 * day, base + 6 * day))
        assertEquals(3, result)
    }

    @Test
    fun `averageIntervalDays rounds to the nearest day`() {
        // gaps: 2, 3 -> mean 2.5 -> rounds to 3 (round-half-up via roundToInt)
        val result = CareInsights.averageIntervalDays(listOf(base, base + 2 * day, base + 5 * day))
        assertEquals(3, result)
    }

    @Test
    fun `averageIntervalDays floors at one day for same-day events`() {
        val sameDay = listOf(base, base + TimeUnit.HOURS.toMillis(2), base + TimeUnit.HOURS.toMillis(5))
        assertEquals(1, CareInsights.averageIntervalDays(sameDay))
    }

    @Test
    fun `summarizePhotos reports count and first-last timestamps`() {
        val photos = listOf(
            GalleryPhoto("a", base + 4 * day, GalleryPhotoSource.FromCareLog(1L)),
            GalleryPhoto("b", base, GalleryPhotoSource.FromCareLog(2L)),
            GalleryPhoto("c", base + 9 * day, GalleryPhotoSource.FromCareLog(3L))
        )

        val summary = CareInsights.summarizePhotos(photos)

        assertEquals(3, summary.count)
        assertEquals(base, summary.firstAt)
        assertEquals(base + 9 * day, summary.lastAt)
    }

    @Test
    fun `summarizePhotos is empty for no photos`() {
        val summary = CareInsights.summarizePhotos(emptyList())

        assertEquals(0, summary.count)
        assertNull(summary.firstAt)
        assertNull(summary.lastAt)
    }
}
