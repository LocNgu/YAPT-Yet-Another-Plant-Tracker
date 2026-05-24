package com.yapt.planttracker.ui.screens.whatsnew

import com.yapt.planttracker.BuildConfig

data class ReleaseNotes(
    val versionName: String,
    val added: List<String> = emptyList(),
    val fixed: List<String> = emptyList(),
    val changed: List<String> = emptyList()
)

object WhatsNewContent {
    val current = ReleaseNotes(
        versionName = BuildConfig.VERSION_NAME,
        added = listOf(
            "Unassigned filter — tap \"Unassigned\" on the plant list to show only plants without a room",
            "Location suggestion chips on Add/Edit Plant — tap a previously used room to fill the field",
            "Skip watering — push the next due date forward 1–7 days from the plant detail screen"
        ),
        fixed = listOf(
            "No spurious interval suggestion when you water on the due day with Just Right feedback",
            "Watering history chart now appears for infrequently-watered plants and for plants with only 2 waterings",
            "Reminder schedule updates immediately after restoring a backup",
            "Backup error message is now readable when importing a non-YAPT file",
            "Orphaned photos are cleaned up if a backup restore fails partway through"
        )
    )
}
