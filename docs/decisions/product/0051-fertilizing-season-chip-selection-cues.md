# Product ADR-0051: Fertilizing season chip selection cues

**Status**: accepted

**Date**: 2026-09-25

## Context

#813: `FertilizingSeasonsSelector`'s (product ADR-0049) season chips read backwards. A new plant
starts with all four seasons selected, but a plain `FilterChip` with no `leadingIcon` and no custom
`colors` gets Material 3's defaults on top of this app's palette — the selected fill
(`secondaryContainer`, a dusty taupe in light theme and unset, falling back to M3's grey-purple
baseline, in dark theme) reads as *off*, while the unselected state's `outline`/`onSurfaceVariant`
border and label read distinctly *green*. People who read green as "on" tapped chips to turn seasons
on, and each tap actually turned one off. After three taps only one season was left selected, and
that chip rendered disabled (ADR-0049's "last remaining chip can't be deselected" rule) with no
explanation for why it looked muted.

ADR-0049 recorded two clauses that this issue's root cause sits under: the last-remaining-chip
enforcement is "a disabled chip, structurally unable to fire a tap," and the current-season marker
is "its own visible label" (the `%1$s · Current season` suffix). Both needed to change to fix the
readability problem without a wider `Theme.kt`/app-wide `FilterChip` palette change, which is
deliberately out of scope here (filed as a separate follow-up, #814).

## Decision

**Selected chips read as on.** A selected chip gets a `primaryContainer`/`onPrimaryContainer` fill
(`FilterChipDefaults.filterChipColors(selectedContainerColor = primaryContainer, selectedLabelColor =
onPrimaryContainer)`) — green in both themes, matching this app's "selected/active/on" convention
elsewhere. No checkmark `leadingIcon`: on device, a checkmark on every chip of a default all-selected
row read as too much. The non-color cue is the outline, which an unselected `FilterChip` has and a
selected one drops; TalkBack gets the selected state from the chip's own semantics. This is scoped to
`FertilizingSeasonsSelector` only, not `Theme.kt`'s `secondaryContainer` role or any other
`FilterChip` in the app (`ReasonBottomSheets.kt`, `WateringHistoryChart.kt`, `PlantListScreen.kt`,
`AddCareLogScreen.kt`, `CareTypeChip.kt` are unaffected and keep the Material 3 default palette,
tracked separately in #814).

**The last remaining selected chip stays enabled and selected — amending ADR-0049's disabled-chip
clause.** A disabled control gives no on-screen explanation for *why* it's disabled beyond its own
muted rendering, which is exactly the "nothing on screen explains why" complaint in #813. Tapping it
now leaves the selection unchanged (it never calls `onToggle`) but runs a short decorative wiggle
plus a `HapticFeedbackType.Reject` haptic buzz, and shows a snackbar: "At least one season must stay
active. To stop fertilizing, turn off the fertilizing reminder." — naming the real on/off control
(`fertilizing_reminder_label`) shown on both surfaces that host this selector. The snackbar is the
accessible cue (works with TalkBack and "Remove animations"); the wiggle is decorative only and is
never required for correctness — the callback that shows the snackbar fires regardless of whether
the animation actually runs. A burst of repeated taps on the locked chip wiggles every time but never
queues repeat copies of the same snackbar text while it's already showing (a small shared
`SnackbarHostState.showSnackbarOnce()` helper, used by both call sites, keeps this rule in one
place). On Plant Detail, the ViewModel's own empty-set refusal (a pre-existing race from #804 where
two fast taps on the last two selected chips can both pass the composable's "not the last one" check
before either write lands) is no longer a silent no-op either — it emits a
`PlantDetailViewModel.Event` that shows the identical snackbar text, without the wiggle, since it
fires asynchronously after the tap that lost the race rather than from the tap itself. Add/Edit
Plant's toggle is synchronous local form state and cannot hit this race, so it has no equivalent
event.

**The current-season marker moves out of the visible label — amending ADR-0049's visible-label
clause.** The chip's visible text is now always the plain season name (no `%1$s · Current season`
suffix). The current season instead gets a small decorative
trailing dot (`contentDescription = null`), and the "current season" wording moves into the chip's
`stateDescription` semantics, so TalkBack still announces it — reusing the same string resource
(`fertilizing_current_season`), reworded from a `%1$s · Current season` template to a plain "Current
season" state description since the season name itself is no longer part of that string.

## Consequences

- With default settings (every season selected), all four chips now read unambiguously as on —
  green fill with no outline — in both light and dark theme; an unselected chip is outlined with no
  fill. This
  directly fixes the "tapped to turn on, actually turned off" confusion #813 reports.
- The last-chip lock is now discoverable (snackbar text) rather than a silently muted control, at
  the cost of one more concept (a decorative wiggle plus a shared snackbar helper) than the disabled
  chip it replaces.
- The app-wide `FilterChip` palette inconsistency `ReasonBottomSheets.kt`/`WateringHistoryChart.kt`/
  `PlantListScreen.kt`/`AddCareLogScreen.kt`/`CareTypeChip.kt` share is deliberately not addressed
  here — see #814.
- Compose UI tests for this component assert `assertIsSelected()`/`assertIsNotSelected()`,
  `assertIsEnabled()`, and the `stateDescription` semantics — never colors or tree structure (#420),
  so a future palette tweak inside this same component can't silently break these tests' intent.
