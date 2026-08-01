package com.yapt.planttracker.domain.insights

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.GalleryPhoto
import com.yapt.planttracker.domain.schedule.CareSchedule
import kotlin.math.roundToInt

/**
 * Pure per-tab summary stats for Plant Detail (#436, sub-task 3). Everything here is derived from
 * care logs / gallery photos with no Android or Context dependency, so it is JVM-unit-testable.
 * Intervals are whole calendar days via [CareSchedule.daysBetween] (technical ADR-0013), matching
 * the chart and countdown logic.
 */
object CareInsights {

    /** Summary of one care type: how many events, when the latest was, and the mean gap between them. */
    data class CareTypeSummary(
        val count: Int,
        val lastAt: Long?,
        /** Mean gap between consecutive events in whole days; `null` when there are fewer than two. */
        val averageIntervalDays: Int?
    )

    fun summarize(logs: List<CareLog>, type: CareType): CareTypeSummary {
        val timestamps = logs.asSequence()
            .filter { it.careType == type }
            .map { it.loggedAt }
            .sorted()
            .toList()
        return CareTypeSummary(
            count = timestamps.size,
            lastAt = timestamps.lastOrNull(),
            averageIntervalDays = averageIntervalDays(timestamps)
        )
    }

    /**
     * Mean of consecutive calendar-day gaps for [sortedTimestamps] (ascending), rounded to the
     * nearest whole day and floored at 1; `null` when there are fewer than two events.
     */
    fun averageIntervalDays(sortedTimestamps: List<Long>): Int? {
        if (sortedTimestamps.size < 2) return null
        val gaps = sortedTimestamps.zipWithNext { earlier, later -> CareSchedule.daysBetween(earlier, later) }
        return (gaps.sum().toDouble() / gaps.size).roundToInt().coerceAtLeast(1)
    }

    /** Photo-timeline summary: how many photos and the earliest / latest capture timestamps. */
    data class PhotoSummary(
        val count: Int,
        val firstAt: Long?,
        val lastAt: Long?
    )

    fun summarizePhotos(photos: List<GalleryPhoto>): PhotoSummary {
        val timestamps = photos.map { it.timestamp }.sorted()
        return PhotoSummary(
            count = timestamps.size,
            firstAt = timestamps.firstOrNull(),
            lastAt = timestamps.lastOrNull()
        )
    }
}
