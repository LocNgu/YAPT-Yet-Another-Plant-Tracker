package com.yapt.planttracker.ui.screens.whatsnew

data class ReleaseNotes(
    val versionCode: Int,
    val versionName: String,
    val added: List<String> = emptyList(),
    val fixed: List<String> = emptyList(),
    val changed: List<String> = emptyList()
)

object WhatsNewContent {
    // Implementer appends here per PR (dev workflow step 5) — mirrors CHANGELOG's [Unreleased].
    val unreleased: ReleaseNotes = ReleaseNotes(
        versionCode = 0,
        versionName = "Unreleased",
        fixed = listOf(
            "Fertilizing season chips now clearly show which seasons are selected — a checkmark and green " +
                "fill instead of a muted grey that looked unselected. The last remaining active season stays " +
                "tappable instead of greying out unexplained; tapping it now shows a message explaining that " +
                "at least one season must stay active"
        )
    )

    // Released entries only, newest first — promoted from `unreleased` at release-cut. 0.20.1 and older
    // live in `legacyReleaseNotes` (WhatsNewLegacyReleases.kt), appended after these (#813).
    val all: List<ReleaseNotes> = listOf(
        ReleaseNotes(
            versionCode = 480,
            versionName = "0.32.0",
            added = listOf(
                "Dormant plants can now keep watering fully paused or use a simple 1–12 week " +
                    "watering schedule. Dormant watering appears in reminders and due dates while " +
                    "fertilizing stays paused",
                "Choose which seasons a plant fertilizes in — Spring, Summer, Autumn, Winter, any combination — " +
                    "at its one existing interval. An inactive season is never due or overdue, and the plant " +
                    "becomes due again exactly on the first day of its next active season. Seasons follow your " +
                    "device's hemisphere and default to every season, so existing plants are unchanged; a " +
                    "configured dormancy window pauses fertilizing reminders on top of this",
                "Watering, fertilizing, repotting, and dormant watering interval sliders now have −/+ buttons " +
                    "for exact one-step nudges — a day, week, or month depending on the setting — with a " +
                    "light haptic tick while dragging"
            ),
            changed = listOf(
                "A plant's dormancy window now pauses its fertilizing reminders too, not just watering. " +
                    "While dormant it's left out of the daily fertilizing reminder, grouped under Dormant when " +
                    "sorting by fertilizing, shows Dormant on its plant card instead of a countdown, and its " +
                    "fertilizing dates are left off the Calendar; fertilizing picks back up from its normal " +
                    "schedule afterwards"
            )
        ),
        ReleaseNotes(
            versionCode = 470,
            versionName = "0.31.0",
            added = listOf(
                "You can now give a plant a dormancy window — the months it rests, set on Add/Edit Plant or " +
                    "the Water tab's settings. Watering reminders pause during those months, and a watering " +
                    "that spans the rest period no longer throws off the plant's learned watering rhythm",
                "Plants inside a dormancy window now show as Dormant on the Plant List and Calendar. " +
                    "Their watering schedule is suspended, and Why this date? explains that while keeping " +
                    "their recent watering adjustments visible"
            ),
            changed = listOf(
                "Watering logs now use the same adaptive schedule logic whether you use a quick action or " +
                    "the full Add Care Log form; this keeps their existing behavior consistent",
                "Plant Detail has a new look: it's now always the tabbed Water / Fertilize / Repot / Photo / " +
                    "Custom Reminders / Issues layout, instead of one long scrolling page. The old watering and " +
                    "fertilizing quick-tap chips are gone, replaced by always-visible Water/Fertilize buttons on " +
                    "their own tabs — Water still works even for a plant with no set schedule. Custom Reminders " +
                    "and Issues now live behind their own tabs too; tap the expand arrow (it shows a dot when " +
                    "something there needs attention) to see all six tabs at once",
                "Rescheduling a watering is now a single tap on the date you want, with no \"why\" question in " +
                    "the way — it just moves the due date. The reminder notification's \"Still moist\" button is " +
                    "gone for the same reason (Watered and Not now are still there); watering itself still asks " +
                    "why when it's off schedule, and that's still what teaches YAPT your plant's rhythm",
                "New and existing photos taken inside YAPT now use substantially less app storage. Backup export " +
                    "can also optimize its copies of photos—including gallery photos—without changing your originals"
            ),
            fixed = listOf(
                "A late watering marked \"Soil was still moist\" in Add Care Log can no longer shorten " +
                    "a plant's watering interval when its schedule first learns from history or learns again after a reset",
                "Reschedule watering now shows the date each +N-day choice will set, so you " +
                    "can see exactly when the next reminder is due",
                "Retyping a watering suggestion after logging an earlier watering now uses today's season " +
                    "consistently when saving the new interval",
                "The suggested-interval dialog no longer pops up just because the season moved on since you " +
                    "last touched the interval — it now only appears when watering actually taught YAPT " +
                    "something, so it's no longer wrongly blamed on whichever watering you just logged",
                "Adaptive watering now keeps small, sub-day schedule refinements and no longer lets seasonal " +
                    "rounding nudge the underlying interval when a suggested interval is applied — whether " +
                    "you accept it yourself or YAPT applies it for you",
                "Fixed a bug where tapping the reminder notification's \"Not now\" button could take several " +
                    "taps to actually clear an overdue plant, instead of always pushing the due date to at " +
                    "least tomorrow on the first tap",
                "Rescheduling watering to a custom date on or before the plant's current watering due date no " +
                    "longer silently does nothing — that date is no longer offered in the picker",
                "Rescheduling watering's \"Today\" option is no longer greyed out in cases where tapping it " +
                    "would actually pull the due date in sooner"
            )
        ),
        ReleaseNotes(
            versionCode = 460,
            versionName = "0.30.0",
            changed = listOf(
                "The \"Log watering\" date picker now slides up as a bottom sheet instead of popping up as a " +
                    "centered dialog, matching the style of the other prompts on Plant Detail",
                "The Repot and Photo tab quick actions on Plant Detail now ask for a date before logging, " +
                    "instead of logging immediately. Add photo now opens a single sheet with a date field and " +
                    "both Take photo and Choose from gallery, right on Plant Detail, rather than opening the " +
                    "full Add Care Log screen (which stays available with its notes field via the + button)"
            ),
            fixed = listOf(
                "Fixed a bug where turning on the always-available seasonal watering setting could silently " +
                    "revert a plant's watering interval to an old, stale value. A one-time fix now runs " +
                    "automatically to re-sync affected plants' intervals",
                "Dismissing a suggested watering interval from Calendar or Plant List now shows up in the " +
                    "\"Why this date?\" sheet's \"Recent adjustments\" list, matching Plant Detail"
            )
        ),
        ReleaseNotes(
            versionCode = 450,
            versionName = "0.29.0",
            added = listOf(
                "After you water, YAPT can now remind you 30 minutes after the end of your watering round to " +
                    "check plant saucers and pour away standing water. It appears in the app while YAPT is open " +
                    "or as a notification in the background, and can be turned off in Settings → Reminders",
                "The Repot and Photo tabs on Plant Detail now have their own quick actions — Repot logs " +
                    "immediately, while Add photo opens the existing log screen with Photo already selected"
            ),
            changed = listOf(
                "Seasonal watering (stretching intervals in winter, compressing them in summer) and its amplitude " +
                    "picker in Settings are now always on — no more developer-mode flag to turn it on. Existing " +
                    "unpinned plants without an explicit amplitude choice now default to Standard seasonal " +
                    "adjustment; use Settings, or a plant's own \"Pin interval\" switch, to opt back out",
                "The \"Check {plant}\" watering reminder (with Watered, Still moist, and Not now actions) is now " +
                    "always on — no more developer-mode flag to turn it on"
            ),
            fixed = listOf(
                "Fixed three edge cases when backdating a \"Log watering\" entry to before an already-existing " +
                    "later watering: backfilling an old watering no longer discards an unrelated active " +
                    "reschedule, the on/off-schedule prompt now compares against the backdated date's own " +
                    "prior watering instead of your most recent one, and a bootstrap triggered by a backdated " +
                    "entry no longer shows a briefly stale \"currently every N days\" figure"
            )
        ),
        ReleaseNotes(
            versionCode = 440,
            versionName = "0.28.0",
            changed = listOf(
                "Tapping the Water button (or its liquid-fertilizer counterpart) on Plant Detail now always opens " +
                    "a \"Log watering\" date picker first, pre-selected to today — confirm it as-is for the old " +
                    "instant-log behavior, or pick an earlier date to backfill a watering you forgot to log",
                "The confidence-weighted adaptive watering model (interval suggestions that adjust based on your " +
                    "watering feedback and get calmer once the schedule proves itself) is now always on — no more " +
                    "developer-mode flag to turn it on"
            ),
            fixed = listOf(
                "With seasonal watering on, a plant's \"currently every N days\" figure could briefly show a " +
                    "stale number right after adaptive watering cold-started its estimate from that plant's " +
                    "own watering history — it's now converted the same way a suggested-interval apply already was"
            )
        ),
        ReleaseNotes(
            versionCode = 430,
            versionName = "0.27.1",
            fixed = listOf(
                "The confusing \"It was dry by then\" answer on the late-watering prompt is now \"Soil was still " +
                    "moist\" — a late watering can no longer shorten your watering interval, only hold it steady " +
                    "or lengthen it"
            )
        ),
        ReleaseNotes(
            versionCode = 420,
            versionName = "0.27.0",
            added = listOf(
                "Plant Detail now shows a \"Rescheduled +N days\" chip when a reschedule is currently pushing " +
                    "a plant's watering due date out — tap it to revert instantly (with an Undo Snackbar). The " +
                    "\"Why this date?\" sheet shows a matching read-only row"
            ),
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
        )
    ) + legacyReleaseNotes
}
