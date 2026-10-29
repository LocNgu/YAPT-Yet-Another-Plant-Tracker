package com.yapt.planttracker.ui.screens.whatsnew

data class ReleaseNotes(
    val versionCode: Int,
    val versionName: String,
    val added: List<String> = emptyList(),
    val fixed: List<String> = emptyList(),
    val changed: List<String> = emptyList()
)

object WhatsNewContent {
    // Implementer appends here per PR (dev workflow step 5): one short plain-language sentence per change,
    // not a copy of CHANGELOG's [Unreleased] bullet (.claude/rules/whats-new.md, product ADR-0072).
    val unreleased: ReleaseNotes = ReleaseNotes(
        versionCode = 0,
        versionName = "Unreleased",
        added = listOf(
            "New Propagate tab: log cuttings, then add a new plant that starts with the parent's care settings",
            "A plant's Family card shows the plant it came from and the plants grown from it"
        )
    )

    // Released entries only, newest first — promoted from `unreleased` at release-cut. 0.20.0 and older
    // live in `legacyReleaseNotes` (WhatsNewLegacyReleases.kt), appended after these (#813).
    val all: List<ReleaseNotes> = listOf(
        ReleaseNotes(
            versionCode = 582,
            versionName = "0.40.0",
            added = listOf(
                "Care has a \"Watered today\" section showing the plants you have already watered"
            ),
            changed = listOf(
                "What's New now opens the releases you haven't seen yet and folds older ones away until you tap them"
            )
        ),
        ReleaseNotes(
            versionCode = 580,
            versionName = "0.39.0",
            fixed = listOf(
                "Saving a plant without touching its watering interval no longer changes its schedule"
            ),
            changed = listOf(
                "Edit Plant's watering slider now starts at the interval the plant uses today, matching its page",
                "Care's section titles now stay pinned at the top as you scroll, and tapping one collapses it",
                "On Care, watering is split into Overdue, Today and Tomorrow, and other care shows only what's due " +
                    "or overdue"
            )
        ),
        ReleaseNotes(
            versionCode = 570,
            versionName = "0.38.1",
            fixed = listOf(
                "Letting go of the watering slider without moving it no longer changes a plant's schedule"
            ),
            changed = listOf(
                "A plant's watering interval now follows the season, and tapping it shows why"
            )
        ),
        ReleaseNotes(
            versionCode = 560,
            versionName = "0.38.0",
            added = listOf(
                "You can tell YAPT the soil was already too dry when logging a watering, so its interval can shorten"
            ),
            changed = listOf(
                "On Care, watering from a plant tile's long-press menu now opens the date picker, with \"Soil was " +
                    "already too dry\""
            )
        ),
        ReleaseNotes(
            versionCode = 550,
            versionName = "0.37.0",
            added = listOf(
                "Away mode: plan a trip and YAPT pauses care reminders while you're gone",
                "Waterings logged while you're away don't change how often YAPT thinks a plant needs water",
                "Plan plant-sitter visits for your trip and share a list of what to water as a PDF or text",
                "Back from a trip, you can confirm what a sitter watered and it counts as watered",
                "Hide a plant to take it out of Care, reminders, the Calendar and your list, and unhide it later"
            )
        ),
        ReleaseNotes(
            versionCode = 540,
            versionName = "0.36.0",
            added = listOf(
                "A new Watering overview in Settings groups your plants by how often they need water"
            ),
            fixed = listOf(
                "Plants you've selected now stay selected when you change room, sort or search"
            )
        ),
        ReleaseNotes(
            versionCode = 530,
            versionName = "0.35.0",
            added = listOf(
                "A plant's page now opens on a new Home tab, with a new Prune tab for logging prunings"
            ),
            fixed = listOf(
                "A quick second action on a plant's page no longer silently undoes the first"
            ),
            changed = listOf(
                "Care history now shows once, on a plant's Home tab, and the Water tab lists only its waterings",
                "A plant's Repot tab now sits with Reminders and Issues, behind the arrow at the end of the tab row",
                "The \"Fertilize\" button now shows even without a fertilizing schedule, and \"Water + Fertilize\" " +
                    "moved off the Water tab",
                "Misting and Notes can no longer be logged, and the ones you already logged stay in your history",
                "The + button on a plant's page is gone, so log care from its tabs instead"
            )
        ),
        ReleaseNotes(
            versionCode = 510,
            versionName = "0.34.0",
            added = listOf(
                "Plan a repot for a season from the Repot tab, with reminders on Care and in the daily notification",
                "A recurring repot can now be limited to the seasons you choose when adding or editing a plant",
                "A new Repotting overview in Settings lists the plants you haven't repotted in a long time"
            ),
            changed = listOf(
                "Settings now has its own tab in the bottom bar, replacing the gear icon on the plant list"
            )
        ),
        ReleaseNotes(
            versionCode = 500,
            versionName = "0.33.0",
            added = listOf(
                "A new Care tab, now the first screen, lists everything due in one queue, with watering first",
                "Care shows each task as a plant tile: tap to open the matching tab, long-press for quick actions",
                "Search your plants by name or species from the search icon on the plant list",
                "Deleted photos now free up their storage instead of lingering on your phone"
            ),
            fixed = listOf(
                "\"Cared for today\" and the Calendar now refresh at midnight instead of showing yesterday",
                "A photo taken with the camera is no longer lost if Android closes the app meanwhile",
                "Moving to a new phone on Android 12+ now carries over your plants, history, settings and photos",
                "Backdating a forgotten watering now adjusts the schedule from the watering just before it",
                "Due dates no longer land a day early right after a daylight saving time change"
            )
        ),
        ReleaseNotes(
            versionCode = 490,
            versionName = "0.32.1",
            fixed = listOf(
                "Selected options, such as fertilizing seasons, now have a green fill so they clearly look selected",
                "The last active fertilizing season stays tappable and explains why it can't be turned off",
                "Backups now include archived plants and their history, and restores no longer leave broken photos"
            )
        ),
        ReleaseNotes(
            versionCode = 480,
            versionName = "0.32.0",
            added = listOf(
                "Dormant plants can keep watering paused or use a simple 1-12 week schedule",
                "Choose which seasons a plant is fertilized in, from any combination of the four",
                "Interval sliders now have −/+ buttons for exact one-step nudges"
            ),
            changed = listOf(
                "A plant's dormancy window now pauses its fertilizing reminders too, not just watering"
            )
        ),
        ReleaseNotes(
            versionCode = 470,
            versionName = "0.31.0",
            added = listOf(
                "Give a plant a dormancy window, the months it rests, and watering reminders pause during them",
                "Dormant plants now show as Dormant on the Plant List and Calendar"
            ),
            fixed = listOf(
                "A late watering marked \"Soil was still moist\" can no longer shorten a plant's watering interval",
                "\"Reschedule watering\" now shows the date each choice will set",
                "\"Reschedule watering\" no longer offers dates that do nothing, and \"Today\" is no longer wrongly " +
                    "greyed out",
                "The suggested-interval dialog now appears only when a watering actually taught YAPT something",
                "Applying or retyping a suggested watering interval now saves the interval you expect",
                "The reminder's \"Not now\" button now pushes an overdue plant to at least tomorrow in one tap"
            ),
            changed = listOf(
                "A plant's page now always uses tabs, with \"Water\" and \"Fertilize\" buttons that are always visible",
                "Custom Reminders and Issues now have their own tabs, behind the arrow at the end of the tab row",
                "Rescheduling watering no longer asks why, and the reminder's \"Still moist\" button is gone",
                "Photos taken in YAPT now use much less storage, and backups can optionally shrink photos too"
            )
        ),
        ReleaseNotes(
            versionCode = 460,
            versionName = "0.30.0",
            fixed = listOf(
                "Turning on seasonal watering no longer silently reverts a plant's interval to an old value",
                "Dismissing a suggested interval from Calendar or Plant List now shows up under \"Why this date?\""
            ),
            changed = listOf(
                "The \"Log watering\" date picker now slides up as a bottom sheet instead of a centered dialog",
                "Repot and Photo actions now ask for a date, and \"Add photo\" offers camera or gallery in one sheet"
            )
        ),
        ReleaseNotes(
            versionCode = 450,
            versionName = "0.29.0",
            added = listOf(
                "After you water, YAPT can remind you to check plant saucers and pour away standing water",
                "The Repot and Photo tabs on a plant's page now have their own quick actions"
            ),
            fixed = listOf(
                "Backdating a watering no longer discards an active reschedule or compares against the wrong watering"
            ),
            changed = listOf(
                "Seasonal watering, which stretches intervals in winter and shortens them in summer, is now always on",
                "Plants that aren't pinned now default to \"Standard\" seasonal adjustment, which Settings can change",
                "The watering check reminder, with \"Watered\", \"Still moist\" and \"Not now\" buttons, is now " +
                    "always on"
            )
        ),
        ReleaseNotes(
            versionCode = 440,
            versionName = "0.28.0",
            fixed = listOf(
                "A plant's current interval no longer briefly shows a stale number after YAPT learns from its history"
            ),
            changed = listOf(
                "\"Water\" on a plant's page now always opens a date picker first, so you can backdate a forgotten " +
                    "watering",
                "Watering interval suggestions that learn from your feedback are now always on"
            )
        ),
        ReleaseNotes(
            versionCode = 430,
            versionName = "0.27.1",
            fixed = listOf(
                "The late-watering prompt now says \"Soil was still moist\", and a late watering can't shorten the " +
                    "interval"
            )
        ),
        ReleaseNotes(
            versionCode = 420,
            versionName = "0.27.0",
            added = listOf(
                "A \"Rescheduled +N days\" label on a plant's page shows a pending reschedule and undoes it in one tap"
            ),
            fixed = listOf(
                "Applying a suggested watering interval no longer drifts, and works the same on every screen"
            ),
            changed = listOf(
                "A plant's seasonal watering curve preview now shows days, such as 6d, instead of a multiplier"
            )
        ),
        ReleaseNotes(
            versionCode = 400,
            versionName = "0.26.0",
            added = listOf(
                "A new \"Active issues\" option in the Plant List sort menu shows only plants with a pest or health " +
                    "issue",
                "Watering suggestions start learning afresh after a repot or room move, and can use a plant's past " +
                    "waterings"
            ),
            fixed = listOf(
                "The watering interval suggestion no longer shows a misleading jump or pops up when it shouldn't"
            )
        ),
        ReleaseNotes(
            versionCode = 390,
            versionName = "0.25.1",
            fixed = listOf(
                "A \"Soil still moist\" reschedule now clears a plant's overdue status instead of reverting a moment " +
                    "later"
            )
        ),
        ReleaseNotes(
            versionCode = 380,
            versionName = "0.25.0",
            changed = listOf(
                "\"Water\" and \"Reschedule watering\" now stay visible on a plant's page even before it is due",
                "The watering prompt now asks \"Why was it late?\" for late waterings and keeps \"Why now?\" for " +
                    "early ones"
            )
        ),
        ReleaseNotes(
            versionCode = 370,
            versionName = "0.24.0",
            added = listOf(
                "A \"Why this date?\" sheet explains a plant's next watering date and its recent adjustments",
                "A new \"Ask before changing intervals\" setting lets you apply suggestions without confirming each one"
            ),
            changed = listOf(
                "A plant's watering actions are now just \"Water\" and \"Reschedule watering\", which ask why when " +
                    "off schedule",
                "\"Reschedule watering\" now offers today, +1, +2 or +3 days, or a custom date",
                "The fertilizing interval slider now goes up to 180 days, for slow-release fertilizers"
            )
        ),
        ReleaseNotes(
            versionCode = 360,
            versionName = "0.23.0",
            added = listOf(
                "Report a plant's pest or health issue, track how long it lasts, and spot it by its purple badge",
                "Reporting an issue can set up a linked treatment reminder, and resolving it can include a note"
            )
        ),
        ReleaseNotes(
            versionCode = 350,
            versionName = "0.22.0",
            added = listOf(
                "Custom reminders: add recurring free-text reminders to a plant, like applying neem oil every 7 days",
                "Overdue and due-today custom reminders now join the daily care notification"
            ),
            fixed = listOf(
                "Watering or fertilizing a plant twice in one day is no longer possible, even when caring for " +
                    "several at once",
                "In the quick water sheet, tapping the selected soil-feedback option deselects it again"
            )
        ),
        ReleaseNotes(
            versionCode = 340,
            versionName = "0.21.0",
            added = listOf(
                "Set a repotting interval in months for a plant and get reminded when a repot is due",
                "A new \"Notify for fertilizing\" setting lets you skip notifications for plants that only need " +
                    "fertilizing"
            ),
            fixed = listOf(
                "Watering chart labels are no longer white-on-white when the app is set to \"Light\" on a dark device",
                "Settings messages no longer cut each other off when several appear in quick succession"
            )
        )
    ) + legacyReleaseNotes
}
