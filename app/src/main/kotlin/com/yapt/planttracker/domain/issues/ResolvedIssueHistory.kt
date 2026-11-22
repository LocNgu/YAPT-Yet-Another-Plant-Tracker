package com.yapt.planttracker.domain.issues

import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.GalleryPhoto
import com.yapt.planttracker.domain.model.PlantIssue
import com.yapt.planttracker.domain.schedule.CareSchedule

/**
 * One resolved [PlantIssue] as shown in Plant Detail's "Resolved" history (#1000).
 *
 * @property durationDays whole calendar days from `startedAt` to `resolvedAt`
 *   ([CareSchedule.daysBetween], technical ADR-0013), floored at 1 so a same-day resolution reads "1 day".
 * @property treatments care logs linked to the issue's treatment reminder, newest first; empty when the
 *   issue has no linked reminder or none of its logs remain.
 * @property beforePhoto the plant's latest photo taken before `startedAt`, if any.
 * @property duringPhotos the plant's photos taken between `startedAt` and `resolvedAt` (inclusive), oldest first.
 */
data class ResolvedIssueEntry(
    val issue: PlantIssue,
    val durationDays: Int,
    val treatments: List<CareLog>,
    val beforePhoto: GalleryPhoto?,
    val duringPhotos: List<GalleryPhoto>
)

/**
 * Pure builder for the resolved health-issue history (#1000). No schema involvement: everything is
 * derived from data `plant_issues`, `care_logs` and the merged photo gallery already hold.
 * - Treatments match by `CareLog.customReminderId == PlantIssue.linkedReminderId` (technical ADR-0019's
 *   one-way link), so they survive the reminder itself being deleted.
 * - Photos are inferred by date, never hand-picked.
 */
object ResolvedIssueHistory {

    /** Entries for every resolved issue in [issues], most recently resolved first. Active issues are skipped. */
    fun build(
        issues: List<PlantIssue>,
        careLogs: List<CareLog>,
        photos: List<GalleryPhoto>
    ): List<ResolvedIssueEntry> {
        val photosOldestFirst = photos.sortedBy { it.timestamp }
        return issues
            .mapNotNull { issue ->
                issue.resolvedAt?.let { resolvedAt -> entryFor(issue, resolvedAt, careLogs, photosOldestFirst) }
            }
            .sortedByDescending { it.issue.resolvedAt }
    }

    private fun entryFor(
        issue: PlantIssue,
        resolvedAt: Long,
        careLogs: List<CareLog>,
        photosOldestFirst: List<GalleryPhoto>
    ): ResolvedIssueEntry {
        val reminderId = issue.linkedReminderId
        val treatments = if (reminderId == null) {
            emptyList()
        } else {
            careLogs.filter { it.customReminderId == reminderId }.sortedByDescending { it.loggedAt }
        }
        return ResolvedIssueEntry(
            issue = issue,
            durationDays = CareSchedule.daysBetween(issue.startedAt, resolvedAt).coerceAtLeast(1),
            treatments = treatments,
            beforePhoto = photosOldestFirst.lastOrNull { it.timestamp < issue.startedAt },
            duringPhotos = photosOldestFirst.filter { it.timestamp in issue.startedAt..resolvedAt }
        )
    }
}
