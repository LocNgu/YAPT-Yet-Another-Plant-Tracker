package com.yapt.planttracker.domain.model

/**
 * An ongoing pest/disease/health problem on a plant (issue #564) — a status with a start date and
 * optional resolution, not a recurring task like [CustomReminder]. Multiple issues can be active
 * (unresolved) on the same plant at once; [resolvedAt] null means still active. Optionally linked to
 * a [CustomReminder] created in the same "report an issue" flow via [linkedReminderId] — a one-way,
 * unenforced link (same pattern as [CareLog.customReminderId], see technical ADR-0019): resolving or
 * deleting this issue never touches the linked reminder, and the reminder may since have been
 * deleted, in which case [linkedReminderId] simply fails to resolve to a name.
 */
data class PlantIssue(
    val id: Long = 0,
    val plantId: Long,
    val name: String,
    val startedAt: Long = System.currentTimeMillis(),
    val resolvedAt: Long? = null,
    val resolutionNote: String? = null,
    val linkedReminderId: Long? = null
) {
    val isActive: Boolean get() = resolvedAt == null
}
