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
            versionCode = 260,
            versionName = "0.15.0",
            added = listOf(
                "New Calendar tab — a month view shows how many plants need care on each day; tap a day to see the plants and water or fertilize them right from the list"
            ),
            fixed = listOf(
                "Taking a photo from the photo reminder now also adds a photo entry to the plant's care history and the watering chart"
            )
        ),
        ReleaseNotes(
            versionCode = 250,
            versionName = "0.14.0",
            added = listOf(
                "Plant list now shows date-group dividers (Overdue / Today / Tomorrow / Later / Not scheduled) when sorted by Watering due, Fertilizing due, or Both due"
            ),
            fixed = listOf(
                "Rapidly double-tapping the back button no longer leaves the app on a blank white screen"
            ),
            changed = listOf(
                "Watering history chart line is now a smooth curve instead of straight zig-zag segments",
                "Photo reminder now also triggers after using the quick water/fertilize buttons on the plant list, not just when opening Plant Detail"
            )
        ),
        ReleaseNotes(
            versionCode = 240,
            versionName = "0.13.0",
            added = listOf(
                "Watering history chart now shows a water-drop icon for each individual watering, with the line connecting each event at day-level precision",
                "Tap a care event icon on the watering history chart to see the care type and the date(s) of the event(s)",
                "Photo reminder — an optional Settings toggle prompts you to photograph a plant you haven't pictured in 30 days; a \"Take photo\" button opens the in-app camera and saves straight to the plant's gallery"
            ),
            fixed = listOf(
                "Daily reminder now fires at your configured time instead of always at 09:00"
            )
        ),
        ReleaseNotes(
            versionCode = 230,
            versionName = "0.12.1",
            fixed = listOf(
                "Care history and Plant Graveyard entries older than 14 days now show an exact date (e.g. \"Jun 10, 2026\") instead of a relative \"X days ago\" label",
                "BackupManager: photo files are no longer deleted if an exception occurs after a successful DB transaction, preventing dangling photo URIs",
                "Skip watering stepper dialog: +/− buttons now fill the dialog width evenly, matching the Cancel/Confirm action row layout"
            ),
            changed = listOf(
                "Skip watering button on Plant Detail is now an OutlinedButton below the watering stats row"
            )
        ),
        ReleaseNotes(
            versionCode = 220,
            versionName = "0.12.0",
            added = listOf(
                "Plant Graveyard — deleted plants are now archived instead of immediately removed; restore them or permanently delete from Settings → Plant Graveyard"
            ),
            fixed = listOf(
                "Backup export and import now block navigation while in progress, preventing corrupt exports or incomplete restores"
            )
        ),
        ReleaseNotes(
            versionCode = 210,
            versionName = "0.11.0",
            added = listOf(
                "Care event markers on the watering history chart — icons appear at the bottom of the chart for each care type logged that month; same-day events stack"
            ),
            fixed = listOf(
                "Daily reminder now fires at your configured time on first install (default time is saved to settings on launch)",
                "\"Last: X days ago\" on plant cards and the detail screen now uses calendar days, not a rolling 24-hour window",
                "Watering history chart refreshes immediately when a new watering is logged while the screen is open"
            ),
            changed = listOf(
                "Quick-water+fertilize button on liquid-fertilizer plant cards now opens a soil feedback sheet before logging, matching the standalone quick-water button"
            )
        ),
        ReleaseNotes(
            versionCode = 200,
            versionName = "0.10.0",
            added = listOf(
                "Delete photos from the gallery — long-press a thumbnail or tap the trash icon in the full-screen viewer; care log entries are preserved when their photo is deleted"
            ),
            fixed = listOf(
                "Adding the same photo to a plant twice no longer shows a duplicate thumbnail"
            ),
            changed = listOf(
                "Saving a Photo care log entry now updates the plant's cover photo to the attached image"
            )
        ),
        ReleaseNotes(
            versionCode = 190,
            versionName = "0.9.0",
            added = listOf(
                "Per-plant photo gallery — photos you add on the Add/Edit Plant screen now build a gallery instead of replacing the cover; the Plant Detail screen shows a scrollable gallery of all plant and care-log photos sorted by date",
                "Full-screen photo viewer — tap any photo to open it full-screen; swipe left/right to browse all gallery photos; a page indicator (e.g. \"2 / 5\") appears when there are multiple photos",
                "In-app camera capture — tap the photo button on any screen to choose between taking a new photo or picking from the gallery; graceful error if the device has no camera"
            ),
            fixed = listOf(
                "Photo care log: the Save button is now disabled until a photo is attached, with an inline error hint below the photo picker",
                "Duplicate photos can no longer be added to the gallery"
            ),
            changed = listOf(
                "Quick-water button now opens a soil feedback sheet (Still wet / Just right / Too dry). Just right stays the default — one more tap gets you there."
            )
        ),
        ReleaseNotes(
            versionCode = 170,
            versionName = "0.8.1",
            added = listOf(
                "Care history collapses to the 5 most recent logs — tap the chevron chip to expand all entries and tap again to collapse"
            ),
            fixed = listOf(
                "\"Got it\" button in What's New is now always visible even when there are many release entries",
                "Liquid-fertilizer plant cards now show a time-based countdown instead of a static \"With watering\" label"
            )
        ),
        ReleaseNotes(
            versionCode = 130,
            versionName = "0.8.0",
            added = listOf(
                "Liquid fertilizer mode: mark a plant as using liquid fertilizer and logging a fertilize event will automatically pair it with a watering log",
                "Fertilizer type selector (Liquid / Solid) on the Add Care Log screen, pre-selected from plant default",
                "Plant list cards and detail screen show a \"With watering\" label on the fertilizing chip for liquid-fertilizer plants",
                "What's New sheet now shows the full release history, scrollable and grouped by version — reopen it any time from Settings"
            ),
            changed = listOf(
                "Watering feedback renamed: \"Still wet\" (was Too soon), \"Just right\" (unchanged), \"Too dry\" (was Too late); question is now \"What did you find?\""
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
