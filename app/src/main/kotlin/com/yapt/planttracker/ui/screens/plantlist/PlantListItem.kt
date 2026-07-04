package com.yapt.planttracker.ui.screens.plantlist

import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.util.toLocalDate
import java.time.LocalDate
import java.time.temporal.ChronoUnit

sealed class DateBucket {
    data object Overdue : DateBucket()
    data object Today : DateBucket()
    data object Tomorrow : DateBucket()
    data class Dated(val epochDay: Long) : DateBucket()
    data object Later : DateBucket()
    data object NotScheduled : DateBucket()
}

sealed class PlantListItem {
    data class DateHeader(val bucket: DateBucket) : PlantListItem()
    data class PlantRow(val status: PlantCareStatus) : PlantListItem()
}

private val DATE_GROUPED_SORTS = setOf(SortOption.WATERING_DUE, SortOption.FERTILIZING_DUE, SortOption.BOTH_DUE)

private fun DateBucket.rank(): Int = when (this) {
    DateBucket.Overdue -> 0
    DateBucket.Today -> 1
    DateBucket.Tomorrow -> 2
    is DateBucket.Dated -> 3
    DateBucket.Later -> 4
    DateBucket.NotScheduled -> 5
}

/**
 * Groups an already-filtered, already-sorted [statuses] list into headered sections when the
 * active sort is date-based (Watering due / Fertilizing due / Both due). Item order within each
 * bucket always follows the existing per-item sort order produced by `applySortOrder()`; only the
 * order of the buckets themselves reverses with the ASC/DESC toggle. Both due has no direction
 * toggle (see product ADR-0004) so it always reads Overdue -> Today -> ... -> Later.
 */
fun groupPlantsByDueDate(
    statuses: List<PlantCareStatus>,
    sortOrder: SortOrder,
    now: Long = System.currentTimeMillis()
): List<PlantListItem> {
    val sortOption = sortOrder.option
    if (sortOption !in DATE_GROUPED_SORTS) {
        return statuses.map { PlantListItem.PlantRow(it) }
    }

    val dueAtOf: (PlantCareStatus) -> Long? = if (sortOption == SortOption.FERTILIZING_DUE) {
        { it.nextFertilizingDueAt }
    } else {
        { it.nextWateringDueAt }
    }

    val nowDate = now.toLocalDate()
    val buckets = LinkedHashMap<DateBucket, MutableList<PlantCareStatus>>()
    for (status in statuses) {
        val bucket = bucketFor(dueAtOf(status), nowDate)
        buckets.getOrPut(bucket) { mutableListOf() }.add(status)
    }

    val ascendingBucketOrder = buckets.keys.sortedWith(
        compareBy({ it.rank() }, { (it as? DateBucket.Dated)?.epochDay ?: 0L })
    )
    val reverseBuckets = sortOption != SortOption.BOTH_DUE && sortOrder.direction == SortDirection.ASC
    val orderedBuckets = if (reverseBuckets) ascendingBucketOrder.reversed() else ascendingBucketOrder

    val items = mutableListOf<PlantListItem>()
    for (bucket in orderedBuckets) {
        items += PlantListItem.DateHeader(bucket)
        for (status in buckets.getValue(bucket)) {
            items += PlantListItem.PlantRow(status)
        }
    }
    return items
}

private fun bucketFor(dueAt: Long?, nowDate: LocalDate): DateBucket {
    if (dueAt == null) return DateBucket.NotScheduled
    val days = ChronoUnit.DAYS.between(nowDate, dueAt.toLocalDate())
    return when {
        days < 0L -> DateBucket.Overdue
        days == 0L -> DateBucket.Today
        days == 1L -> DateBucket.Tomorrow
        days in 2L..3L -> DateBucket.Dated(dueAt.toLocalDate().toEpochDay())
        else -> DateBucket.Later
    }
}
