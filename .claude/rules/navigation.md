---
description: Root tabs, start destination, Settings tab, bottom-bar disabling during backup
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/ui/navigation/**/*"
  - "app/src/main/kotlin/com/yapt/planttracker/MainActivity.kt"
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/settings/SettingsScreen.kt"
  - "app/src/androidTest/**/navigation/**/*"
---

# Navigation and root tabs (product ADR-0054, product ADR-0058)

`TodayCareRepository` combines the live repositories/settings/day signal and delegates to the pure
`TodayQueueAggregator`; UI code never derives due tasks. The user-facing name of this feature is
**Care** (tab, top bar, copy, changelog); internal identifiers (`Screen.Today`, route `today`,
`TodayViewModel`/`TodayScreen`/`TodayCareRepository`/`TodayQueueAggregator`) deliberately keep "Today". Care is the start destination and first tab, with
root tabs ordered Care · Plants · Calendar · Settings (product ADR-0054, amending product ADR-0019; Settings added by #855, product ADR-0058 — a *named exception* to product ADR-0019 Q2, whose criteria still gate future tabs, because Care has no route to Settings and #852 would leave the bar with 2 items). Settings is a real root tab (no back arrow, same `navigateToRootTab()` save/restore block as the others, the Plants top-bar gear and its `onNavigateToSettings` plumbing are gone; Graveyard stays nested, Back returns to the Settings tab). `SettingsScreen.onBackupInProgressChanged` (`LaunchedEffect` + reset-on-dispose, the `onSelectionModeChanged` precedent) feeds a flag in `YaptNavGraph` that disables — not hides — every `YaptBottomNavigationBar` item while a backup/restore runs, so its progress dialog can't be abandoned; the screen keeps its own `BackHandler` too. Restore success is `NavController.showRestoredPlantList()` (reset to Care, then Plants with the message).
