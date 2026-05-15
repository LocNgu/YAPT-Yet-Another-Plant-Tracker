# YAPT – Yet Another Plant Tracker

An offline-first Android app for tracking your houseplants and their care history.

## Features

- **Plant library** — Add plants with a photo, name, species, and room/location
- **Care logging** — Log watering, fertilizing, pruning, misting, repotting, notes, and photos
- **Adaptive watering intervals** — After each watering, mark it as *too soon*, *just right*, or *too late*. YAPT suggests an updated interval based on your actual observed rhythm
- **Care history timeline** — Full chronological log per plant with photos
- **Care reminders** — Daily local notifications for overdue or due-today plants
- **Room grouping** — Assign plants to rooms and filter the home screen by room
- **Countdown labels** — Each plant card shows "In X days", "Due today", or "Overdue by X days" for watering and fertilizing, colour-coded green/orange/red
- **Quick log buttons** — One-tap water and fertilize buttons on each plant card; no need to open the detail screen
- **Sort controls** — Sort the plant list by Alphabetical, Watering due, Fertilizing due, or Recently added; sort direction toggleable; choice persists across restarts
- **Watering history chart** — Line chart on the plant detail screen showing average days between waterings per calendar month; selectable time ranges (1M / 3M / 6M / 12M / All)
- **Stats** — Next-due countdown and last-care date for watering and fertilizing per plant
- **Photo gallery** — Scrollable thumbnail gallery of all care photos per plant
- **Backup & restore** — Export and import a `.yapt` ZIP file via the system file picker, with optional photo inclusion
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

## CI/CD

GitHub Actions builds a debug APK on every push to `main`, `develop`, and `claude/**` branches. A release APK is built automatically when code lands on `main`. Artifacts are available for download from the Actions tab.

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
