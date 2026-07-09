---
name: run-yapt
description: Build, install, launch, and drive the YAPT Android app on an emulator via adb. Use when asked to run YAPT, start the app, take a screenshot of a screen, tap through a flow, or verify a UI change actually works on-device.
---

YAPT is an Android app (Kotlin + Jetpack Compose, Gradle build). It has no
headless/CLI mode — driving it means running an Android emulator and sending
it `adb` input events. Drive it via
`.claude/skills/run-yapt/driver.sh`, which wraps `gradlew`, `adb`, and
`uiautomator` into single commands (build, install, launch, screenshot,
tap-by-text, tap-by-coordinate, back, logcat).

All paths below are relative to the repo root (`YAPT-Yet-Another-Plant-Tracker/`).

## Prerequisites

Android SDK with an emulator image and at least one AVD already created —
this machine already has both (`~/Library/Android/sdk`, AVDs `Pixel_10` and
`Pixel_10_Pro_XL`). `adb` must be on `PATH`. JDK 17 is required for Gradle
(already present as the default `java`).

If starting from a machine with no AVD yet, create one with Android Studio's
Device Manager or `avdmanager` — out of scope here since one already exists.

## Build

```bash
./gradlew assembleDebug --console=plain
```

Produces `app/build/outputs/apk/debug/app-debug.apk`. On an up-to-date tree
this is fast (~4s, mostly cached); a clean build takes longer.

## Run (agent path)

Use the driver for every step — it auto-detects a running emulator/device
and only boots a fresh one if none is attached (checked via `adb devices`),
so it never disturbs a device you already have open.

```bash
.claude/skills/run-yapt/driver.sh build              # ./gradlew assembleDebug
.claude/skills/run-yapt/driver.sh launch              # ensure emulator + install if missing + am start
.claude/skills/run-yapt/driver.sh screenshot <name>   # -> /tmp/yapt-shots/<name>.png (prints path)
.claude/skills/run-yapt/driver.sh tap-text "Aloe Vera 2"   # uiautomator dump, tap first match's center
.claude/skills/run-yapt/driver.sh tap <x> <y>         # raw coordinate tap (from a screenshot you inspected)
.claude/skills/run-yapt/driver.sh text "hello"        # types text into a focused field
.claude/skills/run-yapt/driver.sh back                # KEYCODE_BACK — note: from the app's root screen this exits to the launcher, not a sub-screen
.claude/skills/run-yapt/driver.sh dump                # pulls uiautomator XML to /tmp/yapt-window-dump.xml, prints path
.claude/skills/run-yapt/driver.sh logcat              # dumps current logcat filtered to the app's pid
.claude/skills/run-yapt/driver.sh stop                # am force-stop
.claude/skills/run-yapt/driver.sh test                # ./gradlew testDebugUnitTest
```

Typical flow for verifying a UI change:

```bash
.claude/skills/run-yapt/driver.sh build
.claude/skills/run-yapt/driver.sh launch
.claude/skills/run-yapt/driver.sh screenshot before
# ... inspect the screenshot, then drive the flow you care about ...
.claude/skills/run-yapt/driver.sh tap-text "Add Plant"
.claude/skills/run-yapt/driver.sh screenshot after
```

Then actually view the PNGs (e.g. with the Read tool) — don't assume from
exit codes.

`tap-text` matches any element's `text` or `content-desc` attribute by
substring, case-sensitively, and taps the center of the first match in
document order. If nothing matches it exits 1 with a message rather than
tapping the wrong thing.

`ANDROID_SERIAL` can be set to target a specific device when more than one
is attached; otherwise the driver uses the first device adb reports as
`device` (not `offline`/`unauthorized`).

## Run (human path)

Open the project in Android Studio and click Run, or:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.yapt.planttracker/.MainActivity
```

Requires a running emulator or connected device; `adb devices` must show it
before either command works.

## Test

```bash
./gradlew testDebugUnitTest --console=plain
```

JVM unit tests only (ViewModels, `CareSchedule`, `DateUtils`, backup
serialization, etc.) — no emulator needed. Full instrumented tests
(`connectedDebugAndroidTest`) need the emulator and take much longer; run
them only if the change touches Room migrations, Compose screen tests, or
other `androidTest` code.

## Gotchas

- **The "Add Plant" extended FAB (and possibly other FABs) exposes no
  `text` or `content-desc` to the accessibility tree at all** — it's
  entirely absent from `uiautomator dump` output even though it's clearly
  visible on screen (confirmed by dumping the plant list and grepping for
  "Add"/"Plant": zero matches, vs. `"Quick water"`, `"Sort plants"`, etc.
  which all show up fine). `tap-text "Add Plant"` will fail with "No
  element matching" for this button. Use `screenshot` + eyeball the pixel
  center + `tap <x> <y>` instead for this specific control.
- **`back` from the app's home screen exits to the Android launcher**, it
  does not just pop a Compose nav-graph screen if there's nothing left on
  the back stack. If you need to return to a specific in-app screen, use
  `tap-text` on that screen's back arrow (content-desc `"cd_back"`... in
  practice easier to just re-navigate) rather than blind `back` presses.
- **`launch` on an already-open app does not reset navigation state** — `am
  start` on a running task just brings the existing top-most screen
  forward (you'll see `Warning: Activity not started, intent has been
  delivered to currently running top-most instance.`). If you need a clean
  Home-screen state, `stop` first, then `launch`.
- **The photo-reminder dialog can intercept your next tap.** Opening a
  plant detail screen may show a one-time-per-session "Time for a photo!"
  `AlertDialog` (see CLAUDE.md's Photo reminder feature) if that plant's
  last photo is 30+ days old. A `tap-text` aimed at a stat chip or button
  underneath will hit the dialog's overlay instead. Screenshot first, or
  `tap-text "Dismiss"` defensively before continuing.
- **This machine's default emulator (`emulator-5554`) has real personal
  plant data on it** (actual plants like "Aloe Vera 1-4", "Avocado",
  "Blauer Tillandsie"), not a disposable fixture. Don't run destructive
  flows (Empty Graveyard, delete-forever, backup restore that wipes data)
  against it without the user's say-so. If you need a clean/disposable
  device, boot a second AVD on a separate port instead of reusing this one.
- **`nohup emulator ... &` from a non-interactive shell needs `disown`** or
  the harness's job control can hang the command that launched it — the
  driver's `ensure_emulator` already does this.
- **No `timeout` binary on this macOS box** (it's GNU coreutils, not
  installed) — the driver polls boot state with a manual loop-and-sleep
  counter instead of `timeout ... until ...`.

## Troubleshooting

- **`adb devices` lists a freshly-killed emulator as `offline` for a few
  seconds** after `adb -s <serial> emu kill` returns `OK`. The process is
  actually gone (verify with `ps aux | grep -i avd`); `adb kill-server` +
  `adb start-server` clears the stale entry immediately if you need it gone
  right away.
- **`Warning: Activity not started, intent has been delivered to currently
  running top-most instance.`** from `am start` — not an error, just means
  the app was already in the foreground on some other screen. Use `stop`
  first if you need a guaranteed fresh launch.
