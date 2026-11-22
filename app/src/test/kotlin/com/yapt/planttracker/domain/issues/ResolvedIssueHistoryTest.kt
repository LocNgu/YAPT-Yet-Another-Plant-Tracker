package com.yapt.planttracker.domain.issues

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.GalleryPhoto
import com.yapt.planttracker.domain.model.GalleryPhotoSource
import com.yapt.planttracker.domain.model.PlantIssue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class ResolvedIssueHistoryTest {

    private val zone = ZoneId.systemDefault()

    private fun at(day: Int, hour: Int = 12): Long =
        LocalDate.of(2026, 3, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun issue(
        id: Long = 1L,
        startedAt: Long = at(5),
        resolvedAt: Long? = at(12),
        linkedReminderId: Long? = null
    ) = PlantIssue(
        id = id,
        plantId = 1L,
        name = "Spider mites",
        startedAt = startedAt,
        resolvedAt = resolvedAt,
        linkedReminderId = linkedReminderId
    )

    private fun photo(uri: String, timestamp: Long) =
        GalleryPhoto(uri = uri, timestamp = timestamp, source = GalleryPhotoSource.FromCareLog(0L))

    private fun customLog(id: Long, loggedAt: Long, reminderId: Long?) = CareLog(
        id = id,
        plantId = 1L,
        careType = CareType.CUSTOM,
        loggedAt = loggedAt,
        customReminderId = reminderId
    )

    @Test
    fun `active issues are skipped and resolved ones are newest-resolved first`() {
        val older = issue(id = 1L, resolvedAt = at(10))
        val newer = issue(id = 2L, resolvedAt = at(20))
        val active = issue(id = 3L, resolvedAt = null)

        val entries = ResolvedIssueHistory.build(listOf(older, active, newer), emptyList(), emptyList())

        assertEquals(listOf(2L, 1L), entries.map { it.issue.id })
    }

    @Test
    fun `duration is calendar days between start and resolution`() {
        val entry = ResolvedIssueHistory.build(
            listOf(issue(startedAt = at(5, hour = 23), resolvedAt = at(12, hour = 1))),
            emptyList(),
            emptyList()
        ).single()

        assertEquals(7, entry.durationDays)
    }

    @Test
    fun `same-day resolution reads as one day`() {
        val entry = ResolvedIssueHistory.build(
            listOf(issue(startedAt = at(5, hour = 8), resolvedAt = at(5, hour = 20))),
            emptyList(),
            emptyList()
        ).single()

        assertEquals(1, entry.durationDays)
    }

    @Test
    fun `treatments are logs linked to the issue's reminder, newest first`() {
        val logs = listOf(
            customLog(10L, at(6), reminderId = 7L),
            customLog(11L, at(9), reminderId = 7L),
            customLog(12L, at(8), reminderId = 99L),
            CareLog(id = 13L, plantId = 1L, careType = CareType.WATER, loggedAt = at(7))
        )

        val entry = ResolvedIssueHistory.build(listOf(issue(linkedReminderId = 7L)), logs, emptyList()).single()

        assertEquals(listOf(11L, 10L), entry.treatments.map { it.id })
    }

    @Test
    fun `no linked reminder means no treatments even when untagged logs exist`() {
        val logs = listOf(customLog(10L, at(6), reminderId = null))

        val entry = ResolvedIssueHistory.build(listOf(issue(linkedReminderId = null)), logs, emptyList()).single()

        assertTrue(entry.treatments.isEmpty())
    }

    @Test
    fun `photos are inferred by date with the latest earlier photo as before`() {
        val photos = listOf(
            photo("old", at(1)),
            photo("before", at(4)),
            photo("start", at(5)),
            photo("mid", at(8)),
            photo("end", at(12)),
            photo("after", at(13))
        )

        val entry = ResolvedIssueHistory.build(listOf(issue()), emptyList(), photos.shuffled()).single()

        assertEquals("before", entry.beforePhoto?.uri)
        assertEquals(listOf("start", "mid", "end"), entry.duringPhotos.map { it.uri })
    }

    @Test
    fun `no photos in the window or before yields empty photo fields`() {
        val entry = ResolvedIssueHistory.build(listOf(issue()), emptyList(), listOf(photo("after", at(20)))).single()

        assertNull(entry.beforePhoto)
        assertTrue(entry.duringPhotos.isEmpty())
    }
}
