package com.yapt.planttracker.ui.screens.whatsnew

/**
 * Released What's New entries for 0.20.0 and older, newest first (#813). Rewritten once in #958 to the short
 * plain-language style (.claude/rules/whats-new.md, product ADR-0072); it was split out of [WhatsNewContent]
 * so that append-only object stays under Detekt's `LargeClass` threshold instead of being baselined.
 * [WhatsNewContent.all] appends this list after its own newer entries. Frozen: a release cut only
 * ever promotes `WhatsNewContent.unreleased` into [WhatsNewContent.all], never into this list.
 */
internal val legacyReleaseNotes: List<ReleaseNotes> = listOf(
    ReleaseNotes(
        versionCode = 320,
        versionName = "0.20.0",
        added = listOf(
            "Combine reminders: a Settings toggle sends one daily digest instead of a notification per plant",
            "The full-screen photo viewer now shows each photo's exact date as you swipe",
            "The Photo reminder setting is now saved in backups, so it survives a move to a new phone"
        ),
        fixed = listOf(
            "Bulk care and Move to Graveyard on several plants no longer leave a batch half-done if interrupted"
        )
    ),
    ReleaseNotes(
        versionCode = 310,
        versionName = "0.19.0",
        fixed = listOf(
            "The full-screen photo viewer no longer shows a strip of the screen behind the status bar"
        ),
        changed = listOf(
            "Add Care Log's Photo type now has Take photo and Choose from gallery buttons, with no pop-up"
        )
    ),
    ReleaseNotes(
        versionCode = 300,
        versionName = "0.18.0",
        added = listOf(
            "Long-press a plant to select several, then water, fertilize, prune, mist, repot or send them to the " +
                "Graveyard",
            "A new Cared for today option in the plant list sort menu shows the plants you have cared for today"
        ),
        fixed = listOf(
            "Two waterings on the same day no longer suggest a 0-day interval or a fractional chart average"
        )
    ),
    ReleaseNotes(
        versionCode = 290,
        versionName = "0.17.0",
        added = listOf(
            "Tap the Watering or Fertilizing chip on a plant's page to log it without opening Add Care Log"
        ),
        fixed = listOf(
            "The Calendar day sheet now has a water-only button for liquid-fertilizer plants"
        ),
        changed = listOf(
            "The quick water and fertilize buttons on the plant list and Calendar now look and behave alike"
        )
    ),
    ReleaseNotes(
        versionCode = 280,
        versionName = "0.16.0",
        changed = listOf(
            "A plant with a watering interval but no waterings yet is now due today instead of Not scheduled",
            "A plant with a fertilizing interval but never fertilized becomes due 30 days after you added it"
        )
    ),
    ReleaseNotes(
        versionCode = 270,
        versionName = "0.15.1",
        fixed = listOf(
            "Liquid-fertilizer plants now appear in the Calendar only on their watering day"
        )
    ),
    ReleaseNotes(
        versionCode = 260,
        versionName = "0.15.0",
        added = listOf(
            "A new Calendar tab shows how many plants need care each day, and lets you log care from a tapped day"
        ),
        fixed = listOf(
            "Taking a photo from the photo reminder now also adds a photo entry to the plant's history and chart"
        )
    ),
    ReleaseNotes(
        versionCode = 250,
        versionName = "0.14.0",
        added = listOf(
            "The plant list shows Overdue, Today, Tomorrow and Later dividers when sorted by due date"
        ),
        fixed = listOf(
            "Double-tapping the back button quickly no longer leaves the app on a blank white screen"
        ),
        changed = listOf(
            "The watering history chart line is now a smooth curve instead of straight segments",
            "The photo reminder now also triggers after the quick water and fertilize buttons on the plant list"
        )
    ),
    ReleaseNotes(
        versionCode = 240,
        versionName = "0.13.0",
        added = listOf(
            "The watering history chart now shows a water-drop icon for each watering",
            "Tap a care event icon on the watering history chart to see its care type and date",
            "An optional Photo reminder prompts you to photograph a plant you haven't pictured in 30 days"
        ),
        fixed = listOf(
            "The daily reminder now fires at your configured time instead of always at 09:00"
        )
    ),
    ReleaseNotes(
        versionCode = 230,
        versionName = "0.12.1",
        fixed = listOf(
            "Care history and Plant Graveyard entries older than 14 days now show an exact date",
            "A late error during a backup restore no longer leaves restored plants pointing at deleted photos",
            "The Skip watering dialog's +/− buttons now fill its width evenly"
        ),
        changed = listOf(
            "The Skip watering button on a plant's page is now an outlined button below the watering stats"
        )
    ),
    ReleaseNotes(
        versionCode = 220,
        versionName = "0.12.0",
        added = listOf(
            "Deleted plants now go to a Plant Graveyard in Settings, where you can restore or delete them for good"
        ),
        fixed = listOf(
            "Backup export and import now block navigation until they finish, preventing corrupt backups"
        )
    ),
    ReleaseNotes(
        versionCode = 210,
        versionName = "0.11.0",
        added = listOf(
            "Care event markers on the watering history chart show each care type logged that month"
        ),
        fixed = listOf(
            "The daily reminder now fires at your configured time on first install",
            "Last: X days ago on plant cards and the detail screen now counts calendar days",
            "The watering history chart refreshes immediately when a watering is logged while it's open"
        ),
        changed = listOf(
            "The quick water + fertilize button on liquid-fertilizer plant cards now asks about the soil first"
        )
    ),
    ReleaseNotes(
        versionCode = 200,
        versionName = "0.10.0",
        added = listOf(
            "Delete gallery photos with a long-press or the full-screen trash icon, keeping their care log entries"
        ),
        fixed = listOf(
            "Adding the same photo to a plant twice no longer shows a duplicate thumbnail"
        ),
        changed = listOf(
            "Saving a Photo care log entry now sets the plant's cover photo to the attached image",
            "The quick-water button now opens a soil feedback sheet with Still wet, Just right and Too dry"
        )
    ),
    ReleaseNotes(
        versionCode = 190,
        versionName = "0.9.0",
        added = listOf(
            "Each plant now has a photo gallery of its own and care-log photos, sorted by date",
            "Tap any photo to open it full-screen and swipe to browse the rest of the gallery",
            "Add photos by taking one with the in-app camera or picking one from the gallery"
        ),
        fixed = listOf(
            "The Photo care log's Save button stays disabled until a photo is attached, with a hint below",
            "Duplicate photos can no longer be added to the gallery"
        )
    ),
    ReleaseNotes(
        versionCode = 170,
        versionName = "0.8.1",
        added = listOf(
            "Care history shows the 5 most recent logs, with a chevron to expand or collapse the rest"
        ),
        fixed = listOf(
            "The Got it button in What's New is now always visible, even with many releases",
            "Liquid-fertilizer plant cards now show a time-based countdown instead of a static With watering label"
        )
    ),
    ReleaseNotes(
        versionCode = 130,
        versionName = "0.8.0",
        added = listOf(
            "Liquid fertilizer mode pairs every fertilize log with a watering and marks the plant as With watering",
            "Add Care Log has a Liquid or Solid fertilizer selector, preset from the plant's default",
            "What's New now shows the full release history, grouped by version, and can be reopened from Settings"
        ),
        changed = listOf(
            "Watering feedback is now Still wet, Just right or Too dry, asked as What did you find?"
        )
    ),
    ReleaseNotes(
        versionCode = 119,
        versionName = "0.7.1",
        fixed = listOf(
            "Restoring a large backup with many photos no longer crashes the app",
            "Exporting a backup to cloud destinations such as Google Drive no longer produces an empty ZIP",
            "Cancelling the warning shown during a restore now cleans up its temporary files"
        )
    ),
    ReleaseNotes(
        versionCode = 108,
        versionName = "0.7.0",
        added = listOf(
            "Tap Unassigned on the plant list to show only plants without a room",
            "Add/Edit Plant suggests rooms you've used before as tappable chips",
            "Skip watering pushes the next due date forward 1-7 days from a plant's page"
        ),
        fixed = listOf(
            "No interval suggestion appears when you water on the due day with Just right feedback",
            "The watering chart now also shows for rarely watered plants and plants with only two waterings",
            "The reminder schedule updates immediately after restoring a backup",
            "The backup error message is now readable when importing a non-YAPT file",
            "Photos left over from a backup restore that fails partway are now cleaned up"
        )
    ),
    ReleaseNotes(
        versionCode = 91,
        versionName = "0.6.0",
        added = listOf(
            "A What's New sheet shows what changed after each update",
            "A Keep screen on toggle in Settings keeps the screen awake while you water",
            "The interval suggestion is now an editable dialog, so you can adjust the value before applying"
        ),
        fixed = listOf(
            "Plants past their due date now show Overdue, and Due today stays correct throughout the day",
            "Care-type chips no longer say next: when care is already overdue",
            "Watering interval suggestions based on your feedback are now more accurate",
            "The keyboard no longer covers the Notes field when editing a plant or care log"
        )
    ),
    ReleaseNotes(
        versionCode = 70,
        versionName = "0.4.1",
        added = listOf(
            "A Water + Fertilize due filter in the sort dropdown shows only plants where both are due",
            "Tapping a care reminder notification opens that plant's page directly"
        )
    ),
    ReleaseNotes(
        versionCode = 60,
        versionName = "0.4.0",
        added = listOf(
            "Larger plant photos: full-height thumbnails on list cards and a hero image on the detail screen"
        )
    ),
    ReleaseNotes(
        versionCode = 40,
        versionName = "0.3.0",
        added = listOf(
            "A watering history chart on the plant detail screen, with 1M, 3M, 6M, 12M and All time ranges"
        )
    ),
    ReleaseNotes(
        versionCode = 25,
        versionName = "0.2.0",
        added = listOf(
            "Sort the plant list by name, watering due, fertilizing due or recently added, remembered across restarts",
            "Plant cards show countdown chips: In X days, Due today or Overdue by X days",
            "Each plant card has quick Water and Fertilize buttons"
        )
    ),
    ReleaseNotes(
        versionCode = 10,
        versionName = "0.1.0",
        added = listOf(
            "Plant library: add, edit and delete plants with a cover photo",
            "Care logging for Water, Fertilize, Prune, Mist, Repot, Note and Photo, with custom dates and editable " +
                "history",
            "Adaptive watering interval: suggestions based on your feedback after each watering",
            "Daily care reminders at a time you choose in Settings",
            "Local backup and restore by exporting and importing a .yapt archive"
        )
    )
)
