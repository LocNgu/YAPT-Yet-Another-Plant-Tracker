package com.yapt.planttracker.domain.reminder

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Shared photo-reminder session state and policy.
 *
 * Owns the once-per-session-per-plant dedup set, the age threshold, and the pure
 * "how old is the newest photo" / "should we remind" functions used by every surface
 * that can trigger the photo reminder: [com.yapt.planttracker.ui.screens.plantdetail.PlantDetailViewModel],
 * `PlantListViewModel`/`CalendarViewModel` (via [com.yapt.planttracker.domain.usecase.QuickLogUseCase]).
 *
 * Extracted from `PlantDetailViewModel`'s companion object (#410) so no screen's
 * ViewModel depends on another screen's ViewModel for this shared state.
 */
object PhotoReminderPolicy {

    /**
     * Process-wide set of plant IDs whose photo reminder has already been shown this
     * app session. Shared across all triggering surfaces so the dialog appears at most
     * once per plant per session.
     */
    val shownThisSession = mutableSetOf<Long>()

    const val PHOTO_REMINDER_INTERVAL_DAYS = 30L

    /**
     * Calendar-day age of the newest photo (or, when there is none, of the plant itself),
     * used to decide whether the plant is overdue for a fresh photo.
     */
    fun lastPhotoDaysSince(
        lastPhotoTimestampMs: Long?,
        plantCreatedAtMs: Long,
        nowDate: LocalDate = LocalDate.now()
    ): Long {
        val anchorMs = lastPhotoTimestampMs ?: plantCreatedAtMs
        val anchorDate = Instant.ofEpochMilli(anchorMs)
            .atZone(ZoneId.systemDefault()).toLocalDate()
        return ChronoUnit.DAYS.between(anchorDate, nowDate)
    }

    fun shouldShowPhotoReminder(
        lastPhotoTimestampMs: Long?,
        plantCreatedAtMs: Long,
        nowDate: LocalDate = LocalDate.now()
    ): Boolean = lastPhotoDaysSince(lastPhotoTimestampMs, plantCreatedAtMs, nowDate) >= PHOTO_REMINDER_INTERVAL_DAYS
}
