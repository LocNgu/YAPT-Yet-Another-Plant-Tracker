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
            "What's New sheet — see what changed after each update (that's this screen!)",
            "Keep screen on toggle in Settings — screen stays awake while you water your plants",
            "Interval suggestion is now an editable dialog — adjust the value before applying",
            "Location suggestion chips on Add/Edit Plant — tap a previously used room to fill the field exactly",
            "Skip - too soon button on plant detail and notification action: extends the watering interval by 1 day without logging a care event"
        ),
        fixed = listOf(
            "Overdue plants now always show \"Overdue\" (not \"Due today\") when the due date has passed",
            "Watering interval suggestions (Just right / Too soon / Too late) are more accurate",
            "Care-type chip no longer shows \"next:\" when care is already overdue",
            "Keyboard no longer covers the Notes field when editing a plant or care log",
            "Due dates compare by calendar day — \"due today\" stays correct throughout the day",
            "Watering history chart now shows for infrequently-watered plants on short ranges (1M/3M/6M) and for plants with only 2 total waterings"
        )
    )
}
