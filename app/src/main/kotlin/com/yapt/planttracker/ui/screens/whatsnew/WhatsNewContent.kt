package com.yapt.planttracker.ui.screens.whatsnew

data class ReleaseNotes(
    val versionCode: Int,
    val versionName: String,
    val added: List<String> = emptyList(),
    val fixed: List<String> = emptyList(),
    val changed: List<String> = emptyList()
)

object WhatsNewContent {
    val all: List<ReleaseNotes> = listOf(
        ReleaseNotes(
            versionCode = 129,
            versionName = "0.8.0",
            added = listOf(
                "Liquid fertilizer mode: mark a plant as using liquid fertilizer and logging a fertilize event will automatically pair it with a watering log",
                "Fertilizer type selector (Liquid / Solid) on the Add Care Log screen, pre-selected from plant default",
                "Plant list cards and detail screen show a 'With watering' label on the fertilizing chip for liquid-fertilizer plants"
            )
        ),
        ReleaseNotes(
            versionCode = 119,
            versionName = "0.7.1",
            fixed = listOf(
                "Restoring a large backup with many photos no longer crashes the app (photos are now streamed instead of loaded into memory)",
                "Exporting a backup to cloud destinations (e.g. Google Drive) no longer produces a broken empty ZIP file",
                "Temporary files are cleaned up correctly when you cancel the schema-warning dialog during a restore"
            )
        ),
        ReleaseNotes(
            versionCode = 108,
            versionName = "0.7.0",
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
        ),
        ReleaseNotes(
            versionCode = 91,
            versionName = "0.6.0",
            added = listOf(
                "What's New sheet — see what changed after each update (that's this screen!)",
                "Keep screen on toggle in Settings — screen stays awake while you water your plants",
                "Interval suggestion is now an editable dialog — adjust the value before applying"
            ),
            fixed = listOf(
                "Overdue plants now always show \"Overdue\" (not \"Due today\") when the due date has passed",
                "Watering interval suggestions (Still wet / Just right / Too dry) are more accurate",
                "Care-type chip no longer shows \"next:\" when care is already overdue",
                "Keyboard no longer covers the Notes field when editing a plant or care log",
                "Due dates compare by calendar day — \"due today\" stays correct throughout the day"
            )
        ),
        ReleaseNotes(
            versionCode = 70,
            versionName = "0.4.1",
            added = listOf(
                "\"Water + Fertilize due\" filter in the sort dropdown — shows only plants where both are due",
                "Tapping a care reminder notification opens that plant's detail screen directly"
            )
        ),
        ReleaseNotes(
            versionCode = 60,
            versionName = "0.4.0",
            added = listOf(
                "Larger plant photos — full-height thumbnail on list cards, hero image on the detail screen"
            )
        ),
        ReleaseNotes(
            versionCode = 40,
            versionName = "0.3.0",
            added = listOf(
                "Watering history chart on the plant detail screen with time-range chips (1M / 3M / 6M / 12M / All)"
            )
        ),
        ReleaseNotes(
            versionCode = 25,
            versionName = "0.2.0",
            added = listOf(
                "Sort controls on the plant list — Alphabetical, Watering due, Fertilizing due, Recently added; choice persists across restarts",
                "Countdown chips on plant cards: In X days / Due today / Overdue by X days",
                "Quick Water and Fertilize buttons on each plant card"
            )
        ),
        ReleaseNotes(
            versionCode = 10,
            versionName = "0.1.0",
            added = listOf(
                "Plant library — add, edit, and delete plants with a cover photo",
                "Care logging — Water, Fertilize, Prune, Mist, Repot, Note, Photo; custom dates; editable history",
                "Adaptive watering interval — suggestions based on Still wet / Just right / Too dry feedback",
                "Daily care reminders — configurable time in Settings",
                "Local backup and restore — export and import a .yapt archive"
            )
        )
    )
}
