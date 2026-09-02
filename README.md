# YAPT – Yet Another Plant Tracker

An offline-first Android app for tracking your houseplants and their care history.

## Features

- **Plant library** — Add plants with a photo, name, species, and room/location
- **Care logging** — Log watering, fertilizing, pruning, misting, repotting, notes, and photos
- **Adaptive watering intervals** — After each watering, mark it as *too soon*, *just right*, or *too late*. YAPT suggests an updated interval based on your actual observed rhythm
- **Care history timeline** — Full chronological log per plant with photos
- **Care reminders** — Daily local notifications for overdue or due-today plants; an optional "Combine reminders" toggle collapses them into a single daily digest ("3 plants need care") instead of one notification per plant. A "Notify for fertilizing" toggle suppresses reminders for plants whose only due care is fertilizing
- **Repotting reminder** — Give a plant its own repotting interval in months (3–36); a due or overdue repotting joins the daily care notification, and logging a Repot resets the schedule
- **Custom reminders** — Add any number of free-text recurring reminders to a plant (e.g. "apply neem oil every 7 days") for care no built-in type covers; mark one done to write a journal entry and start its next interval. Overdue or due-today custom reminders join the daily care notification
- **Plant issues** — Report an ongoing pest/disease/health problem on a plant from a dedicated "Active issues" card; tracks how long each issue has been ongoing and shows a purple badge on the plant list card. Optionally set up a linked treatment reminder in the same step; mark an issue resolved with an optional note
- **Adaptive watering resets on repot/room change** — Logging a Repot or moving a plant to a different room resets the adaptive model's learned confidence (a repot also freezes live learning for 4 weeks while the plant re-establishes); a plant with existing watering history but no learned confidence yet can also cold-start its estimate from that history instead of starting from scratch. Both show up in the "Why this date?" sheet's Recent adjustments
- **New-plant scheduling** — A plant with a watering interval but no waterings yet is due today from the start; a never-fertilized plant gets a 30-day grace period after being added before fertilizing comes due
- **Calendar view** — A bottom-nav Calendar tab shows a month view with a count badge on every day that has plants due (overdue plants roll onto today); tap a day to see the plants and quick-log water/fertilize straight from the list
- **Photo reminder** — Optional Settings toggle prompts you to photograph a plant you haven't pictured in 30 days; a one-tap "Take photo" button opens the in-app camera and saves straight to the plant's gallery
- **Room grouping** — Assign plants to rooms and filter the home screen by room; an "Unassigned" chip filters to plants with no room yet
- **Location suggestion chips** — Previously used room names appear as tappable chips on the Add/Edit Plant screen; tap to fill the field instantly
- **Countdown labels** — Each plant card shows "In X days", "Due today", or "Overdue by X days" for watering and fertilizing, colour-coded green/orange/red
- **Quick log buttons** — One-tap water and fertilize buttons on each plant card; no need to open the detail screen. On the plant detail screen, the Watering and Fertilizing stat chips are also tappable to log care in place
- **Bulk actions** — Tap and hold a plant to enter multi-select mode, then apply a care action (Water, Fertilize, Prune, Mist, Repot) or move to the Graveyard for every selected plant at once from a bottom action sheet
- **Liquid fertilizer mode** — Mark a plant as using liquid fertilizer; fertilize logs automatically create a paired watering log at the same time
- **Watering actions with off-schedule reasons** — Plant Detail's **Water** and **Reschedule watering** buttons are always available. Watering off schedule asks a reason worded for the direction it was missed: early asks "Why now?" — "The plant needed it" / "Just my schedule"; late asks "Why was it late?" — "It was dry by then" / "Forgot, or no time". Rescheduling asks "Soil still moist" or "I can't right now" — only some answers feed the adaptive model, declining records nothing. Reschedule offers Today / +1 / +2 / +3 days / a custom date
- **Watering transparency sheet** — Tap "Why this date?" on the Water tab to see how the next watering date was derived (base interval, seasonal adjustment, learned confidence) plus a log of recent automatic adjustments; an "Ask before changing intervals" setting controls whether applying a suggested interval still asks for confirmation or applies silently with an undo option
- **Sort controls** — Sort the plant list by Alphabetical, Watering due, Fertilizing due, or Recently added; sort direction toggleable; choice persists across restarts. An "Active issues" option filters the list to just plants with an ongoing pest/health issue
- **Cared for today** — A sort-dropdown entry that filters the list to just the plants you've logged any care for today, ordered by most-recent care first (toggle for earliest-first)
- **Date-group dividers** — When sorted by Watering due, Fertilizing due, or Both due, the plant list groups cards under Overdue / Today / Tomorrow / dated / Later / Not scheduled headers
- **Keep screen on** — Optional toggle in Settings keeps the display awake while you tend your plants
- **Watering history chart** — Line chart on the plant detail screen with a water-drop icon for each individual watering, connected at day-level precision; selectable time ranges (1M / 3M / 6M / 12M / All); care event markers show which care types were logged, and tapping a marker reveals the care type and date(s)
- **Stats** — Next-due countdown and last-care date for watering and fertilizing per plant
- **Photo gallery** — Per-plant gallery of all plant and care-log photos, sorted by date; tap any photo to open a full-screen swipe viewer that shows each photo's date; add photos via the gallery picker or by taking a new photo with the in-app camera; long-press or use the trash icon in the viewer to delete individual photos
- **Plant Graveyard** — Deleted plants move to an archive in Settings; restore them or remove them permanently
- **Backup & restore** — Export and import a `.yapt` ZIP file via the system file picker, with optional photo inclusion
- **Developer mode** — Tap the Settings → About version row five times to unlock a hidden Developer section: build info, a feature-flag registry for opt-in experimental features (tabbed Plant Detail layout, adaptive watering, seasonal watering, Check reminders), and debug actions (reset the What's New sheet, run the reminder check now, seed/remove demo plants). Off by default and excluded from backup
- **Offline-first** — No account, no cloud, no network calls. All data stays on device

## Screenshots

> _Coming soon_

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Kotlin |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Repository |
| Database | Room (SQLite) |
| DI | Manual (Application-level singletons) |
| Navigation | Compose Navigation |
| Images | Android PhotoPicker API |
| Reminders | WorkManager + NotificationManager |
| Preferences | DataStore |

## Getting Started

### Prerequisites

- Android Studio Ladybug (2024.2) or newer
- Android SDK 35
- JDK 17

### Build

```bash
git clone https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker.git
cd YAPT-Yet-Another-Plant-Tracker
./gradlew assembleDebug
```

The debug APK will be at `app/build/outputs/apk/debug/app-debug.apk`.

### Install

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Or open the project in Android Studio and run it directly on a device or emulator (API 26+).

### Debug keystore

The debug keystore is not committed to the repo. Without it, Android Studio falls back to its own default keystore at `~/.android/debug.keystore`, which works fine for local development.

If you need to install CI-built APKs over locally-built ones (or vice versa) without uninstalling first, you need the exact same keystore that CI uses. Decode it from the `DEBUG_KEYSTORE_BASE64` secret:

```bash
echo "$DEBUG_KEYSTORE_BASE64" | base64 --decode > app/debug.keystore
```

Alternatively, generate a local-only debug keystore (different identity from CI — in-place upgrades between local and CI builds will require an uninstall):

```bash
keytool -genkey -v \
  -keystore app/debug.keystore \
  -alias androiddebugkey \
  -keyalg RSA -keysize 2048 \
  -validity 10000 \
  -storepass android -keypass android \
  -dname "CN=Android Debug, O=Android, C=US"
```

## CI/CD

GitHub Actions builds a debug APK on every push to `main`, `develop`, and `claude/**` branches. A release APK is built automatically when code lands on `main` and published to the [Releases page](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/releases). Debug build artifacts are also available on the Actions tab.

[![Android CI/CD](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/actions/workflows/android.yml/badge.svg)](https://github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker/actions/workflows/android.yml)

## Project Structure

```
app/src/main/kotlin/com/yapt/planttracker/
├── data/
│   ├── db/          # Room DAOs and Database
│   ├── entity/      # Room entities (PlantEntity, CareLogEntity)
│   └── repository/  # PlantRepository, CareLogRepository
├── domain/
│   ├── model/       # Plant, CareLog, CareType, WateringFeedback
│   └── schedule/    # CareSchedule (status + adaptive interval logic)
├── notification/    # NotificationHelper
├── ui/
│   ├── components/  # PlantCard, CareLogItem, PhotoGallery, StatsRow, …
│   ├── navigation/  # NavGraph, Screen
│   ├── screens/     # PlantList, AddEditPlant, PlantDetail, AddCareLog, Settings
│   └── theme/       # Color, Theme, Type
├── util/            # DateUtils, ImageUtils
└── worker/          # ReminderWorker, ReminderScheduler, BootReceiver
```

## License

Copyright © 2026 LocNgu. All rights reserved.

The source code is publicly available for viewing and reference only.
Redistribution, modification, and commercial use are prohibited without
explicit written permission from the author.
