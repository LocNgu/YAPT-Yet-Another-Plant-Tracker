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
            versionCode = 410,
            versionName = "0.26.1",
            fixed = listOf(
                "Applying a suggested watering interval (with seasonal watering on and the plant not pinned) " +
                    "no longer silently drifts the plant's watering interval a little further every time you " +
                    "apply one — it was writing an internal value instead of the number the app actually " +
                    "shows you",
                "Applying a suggested watering interval from the Calendar or Plant List dialogs is now " +
                    "consistent with Plant Detail — those two had their own separate (and buggy) copy of " +
                    "this logic"
            )
        ),
        ReleaseNotes(
            versionCode = 400,
            versionName = "0.26.0",
            added = listOf(
                "A new \"Active issues\" option in the Plant List sort menu narrows the list to only plants " +
                    "currently flagged with a pest/health issue",
                "Adaptive watering now resets its learned confidence when you repot a plant or move it to a " +
                    "different room, and can cold-start its estimate from a plant's own watering history " +
                    "instead of starting from scratch — both show up in the \"Why this date?\" sheet's " +
                    "Recent adjustments"
            ),
            fixed = listOf(
                "With both adaptive and seasonal watering on, the \"Water every N days?\" suggestion dialog " +
                    "(on Plant Detail, Calendar, and Plant List alike) no longer shows a misleadingly large " +
                    "jump (or pops up at all) when the whole apparent change was just a unit mismatch between " +
                    "the suggestion's underlying value and the seasonally adjusted current interval"
            ),
            changed = listOf(
                "Plant Detail's seasonal watering curve preview now shows actual days (e.g. \"6d\", \"9d\") " +
                    "instead of the raw multiplier, based on that plant's watering interval. The Settings " +
                    "screen's version of the chart is unchanged, since it isn't tied to a specific plant"
            )
        ),
        ReleaseNotes(
            versionCode = 390,
            versionName = "0.25.1",
            fixed = listOf(
                "\"Soil still moist\" reschedules (in-app or from the notification) now actually clear the " +
                    "plant's due/overdue status when adaptive watering is on, instead of silently reverting a " +
                    "moment later"
            ),
            changed = listOf(
                "The Water/Reschedule watering row and Fertilize's action button now use the same margins as " +
                    "every other card on Plant Detail, instead of the wide gaps introduced to avoid overlapping " +
                    "the pinned Back/Edit/\"Log care\" buttons. Instead, the Edit button now fades out once " +
                    "you've scrolled past the plant's cover photo, and Back and \"Log care\" stay put"
            )
        ),
        ReleaseNotes(
            versionCode = 380,
            versionName = "0.25.0",
            changed = listOf(
                "Plant Detail's Water and Reschedule watering buttons are now always visible, not just when " +
                    "watering is due — Reschedule watering had no other way to reach it before this. With the " +
                    "\"Plant Detail tabs\" flag on, the now-redundant quick-log summary above the tabs is gone; " +
                    "Fertilize gets its own always-visible action button under its tab instead",
                "The off-schedule watering reason prompt now words itself for the direction the schedule was " +
                    "missed — a late watering asks \"Why was it late?\" — \"It was dry by then\" / \"Forgot, or " +
                    "no time\", naming forgetting outright instead of an abstract excuse. An early watering " +
                    "still asks \"Why now?\" — \"The plant needed it\" / \"Just my schedule\". What the " +
                    "adaptive model learns from either answer is unchanged — only the wording",
                "With the \"Plant Detail tabs\" flag on, the Water/Reschedule watering row (and Fertilize's " +
                    "action button) now sits above the interval settings on its tab instead of below. Water is " +
                    "a filled button with a water-drop icon; Reschedule watering is now an icon-only button, " +
                    "freeing up more width for Water"
            )
        ),
        ReleaseNotes(
            versionCode = 370,
            versionName = "0.24.0",
            added = listOf(
                "\"Why this date?\" watering transparency sheet — see exactly how a plant's next watering date " +
                    "was worked out (base interval, seasonal adjustment, learned confidence) and a log of the " +
                    "last few automatic adjustments, from the Water tab's inline settings. A new \"Ask before " +
                    "changing intervals\" setting lets you apply a suggested interval silently instead of " +
                    "confirming it every time",
                "Developer mode now has three more experimental feature flags, off by default: adaptive " +
                    "watering (a confidence-weighted interval model that learns faster early on and settles " +
                    "down over time), seasonal watering (stretches/compresses intervals for winter/summer using " +
                    "your device's timezone, with a per-plant \"Pin interval\" opt-out and a preview chart in " +
                    "Settings), and Check reminders (a \"Check {plant}\" notification with Watered/Still-moist " +
                    "actions instead of an instruction to water)"
            ),
            changed = listOf(
                "Plant Detail's watering-due actions are now just Water and Reschedule watering, and ask a " +
                    "quick reason whenever the action is off schedule (\"The plant needed it\" / \"Just my " +
                    "timing\" for watering; \"Soil still moist\" / \"I can't right now\" for rescheduling) — " +
                    "declining to answer records no data. Reschedule watering now offers Today / +1 / +2 / +3 " +
                    "days / a custom date",
                "Fertilizing interval slider now goes up to 180 days (was 90), for long-term or slow-release " +
                    "fertilizers",
                "With the \"Plant Detail tabs\" flag on, Custom Reminders and Active Issues become their own " +
                    "tabs behind a collapsible toggle instead of always-visible cards",
                "Plant Detail's Water and Reschedule watering buttons are now always available, not just when " +
                    "watering is due — Reschedule had no other way to reach it. With the \"Plant Detail tabs\" " +
                    "flag on, the quick-log summary above the tabs is gone (now redundant); Fertilize gets its " +
                    "own always-available button under the Fertilize tab instead, and the \"last watered\"/" +
                    "\"last fertilized\" text that summary used to show now appears in each tab's insights " +
                    "card instead. The classic layout is unchanged",
                "With the \"Plant Detail tabs\" flag on, the Water/Reschedule watering row (and Fertilize's " +
                    "action button) now sits above the interval settings on its tab instead of below. Water is " +
                    "a filled green button with a water-drop icon; Reschedule watering is now an icon-only " +
                    "button, freeing up more width for Water"
            )
        ),
        ReleaseNotes(
            versionCode = 360,
            versionName = "0.23.0",
            added = listOf(
                "Plant issues — report an ongoing pest or disease problem from a new \"Active issues\" card on " +
                    "the plant detail screen; it tracks how many days the issue has been going on, and a plant " +
                    "with an active issue gets a purple badge on its list card. Reporting an issue can " +
                    "optionally also set up a linked treatment reminder in the same step. Mark an issue resolved " +
                    "when it clears up, with an optional note on how you fixed it"
            )
        ),
        ReleaseNotes(
            versionCode = 350,
            versionName = "0.22.0",
            added = listOf(
                "Custom reminders — every plant now has a \"Custom reminders\" card on its detail screen where you " +
                    "can add any number of free-text recurring reminders (\"apply neem oil every 7 days\") for " +
                    "things no built-in care type covers. Mark one done and it writes a journal entry and starts " +
                    "its next interval; anything overdue or due today joins the daily care notification"
            ),
            fixed = listOf(
                "Watering or fertilizing the same plant twice on one day is no longer possible — a stray " +
                    "double-tap on a quick-log button now shows an \"Already watered today\" message instead of " +
                    "adding a duplicate entry. Bulk actions skip the plants already logged and tell you how many",
                "The \"How was the soil?\" chips in the quick-water sheet can be deselected again by tapping the " +
                    "selected one, like every other chip group in the app"
            )
        ),
        ReleaseNotes(
            versionCode = 340,
            versionName = "0.21.0",
            added = listOf(
                "Repotting reminder — give a plant its own repotting interval in months (3–36) on the " +
                    "Add/Edit Plant screen. A due or overdue repotting joins the daily care notification, and " +
                    "logging a Repot resets the schedule",
                "Settings → Reminders: new \"Notify for fertilizing\" toggle. Turn it off and a plant that only " +
                    "needs fertilizing won't notify you — plants that also need water still get their full " +
                    "reminder. On by default",
                "Developer mode — tap the version row in Settings → About five times to unlock a hidden " +
                    "Developer section with build info, feature flags, and debug actions (reset the What's New " +
                    "sheet, run the reminder check now, or seed and remove a set of demo plants)",
                "New Plant Detail layout with per-action tabs (Water · Fertilize · Repot · Photo), inline " +
                    "schedule editing, and per-tab insights — available as an opt-in \"Plant Detail tabs\" " +
                    "feature flag under Developer mode"
            ),
            fixed = listOf(
                "Watering history chart labels and gridlines now follow the app's own Light/Dark theme setting, " +
                    "so they're no longer white-on-white when the app is set to Light on a dark device",
                "Settings messages no longer cut each other off when several appear in quick succession"
            )
        ),
        ReleaseNotes(
            versionCode = 330,
            versionName = "0.20.1",
            fixed = listOf(
                "Internal fix to the release build pipeline — no change to how the app behaves"
            )
        ),
        ReleaseNotes(
            versionCode = 320,
            versionName = "0.20.0",
            added = listOf(
                "Settings → Reminders: new \"Combine reminders\" toggle sends a single daily digest (\"3 plants need care\") instead of one notification per plant. Off by default",
                "Full-screen photo viewer now shows each photo's exact date near the bottom, and it updates as you swipe between photos",
                "The Photo reminder on/off setting is now saved in your backup, so it survives moving to a new device"
            ),
            fixed = listOf(
                "Bulk care and \"Move to Graveyard\" actions on multiple selected plants now apply all-or-nothing, so an interrupted action can't leave the batch half-done"
            ),
            changed = listOf(
                "Updated the app's build toolchain (Android Gradle Plugin, Gradle, Kotlin) — no change to how the app behaves"
            )
        ),
        ReleaseNotes(
            versionCode = 310,
            versionName = "0.19.0",
            changed = listOf(
                "Add Care Log: picking the Photo type now shows Take photo and Choose from gallery buttons right in the photo section, so one tap goes straight to the camera or picker — no extra pop-up sheet"
            ),
            fixed = listOf(
                "Full-screen photo viewer no longer shows a thin strip of the screen behind it under the status bar — the whole background is now solid black for a clean, full-dark view"
            )
        ),
        ReleaseNotes(
            versionCode = 300,
            versionName = "0.18.0",
            added = listOf(
                "Tap and hold a plant to enter multi-select mode, then water, fertilize, prune, mist, repot, or move to the Graveyard for every selected plant at once from a bottom action sheet",
                "New \"Cared for today\" option in the plant list sort dropdown — filters the list to just the plants you've logged any care for today, most-recently-cared first (toggle for earliest-first)"
            ),
            fixed = listOf(
                "Watering two plants on the same day no longer suggests a 0-day interval or shows a fractional \"0.5 days\" average on the watering history chart — intervals are now floored at 1 day"
            )
        ),
        ReleaseNotes(
            versionCode = 290,
            versionName = "0.17.0",
            added = listOf(
                "Log a watering or fertilizing straight from a plant's detail page — just tap the Watering or Fertilizing stat chip. No need to open the separate Add Care Log screen for a quick log"
            ),
            fixed = listOf(
                "Calendar day sheet now offers a water-only button for liquid-fertilizer plants, so you can water on a day when only watering is due without also logging a fertilizing"
            ),
            changed = listOf(
                "The quick water/fertilize buttons on the plant list and the calendar day sheet now look and behave identically"
            )
        ),
        ReleaseNotes(
            versionCode = 280,
            versionName = "0.16.0",
            changed = listOf(
                "A plant with a watering interval set but no waterings logged yet now counts as due today — it shows up in the due-today list, the daily reminder, and the Calendar instead of \"Not scheduled\"",
                "A plant with a fertilizing interval set but never fertilized becomes due 30 days after you added it, giving new plants a grace period before feeding"
            )
        ),
        ReleaseNotes(
            versionCode = 270,
            versionName = "0.15.1",
            fixed = listOf(
                "Calendar no longer shows a separate fertilize entry for plants using liquid fertilizer — they're fertilized together with watering, so they now appear only on their watering day"
            )
        ),
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
