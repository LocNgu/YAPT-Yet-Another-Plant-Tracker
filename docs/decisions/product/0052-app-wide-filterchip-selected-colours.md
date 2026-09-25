# Product ADR-0052: App-wide FilterChip selected colours

**Status**: accepted

**Date**: 2026-09-26

## Context

#814, split out of #813 (product ADR-0051). ADR-0051 fixed the fertilizing season chips only, by giving
them a `primaryContainer` fill, and deliberately left the rest of the app's `FilterChip`s on Material 3's
default colours. Those defaults read badly on this palette:

- **Light theme:** a selected chip fills with `secondaryContainer` = `EarthBrownContainer` `#D7B8AE`, a
  dusty taupe that reads grey, while an unselected chip's label is green (`onSurfaceVariant` =
  `SageGreenDark`). "Off" looked more "on" than "on" did — the exact confusion #813 reported.
- **Dark theme:** `DarkColorScheme` never set `secondaryContainer`/`onSecondaryContainer`, so a selected
  chip fell back to Material's baseline grey-purple `#4A4458`, which is off-palette.

The affected chips were `ReasonBottomSheets.kt`, `WateringHistoryChart.kt`, `CareTypeChip.kt`,
`PlantListScreen.kt` (3) and `AddCareLogScreen.kt` (2).

Alternatives considered:

- **Keep `secondaryContainer` as the selected fill and fix only dark theme.** Rejected: it leaves light
  theme's grey-reading selected state, and the same colour role also backs non-chip surfaces
  (`PlantCard`, `CalendarDayBadges`), so retuning it to suit chips would move those too.
- **A full `YaptFilterChip` wrapper composable.** Rejected as heavier than needed: it would have to
  forward every `FilterChip` parameter (`leadingIcon`, `trailingIcon`, `enabled`, `border`, `modifier`, …)
  or be a lossy subset. The only thing that drifted was the colours.

## Decision

**Every `FilterChip` in the app uses one shared colour set, `yaptFilterChipColors()`**
(`ui/components/YaptFilterChipColors.kt`): a selected chip gets a `primaryContainer`/`onPrimaryContainer`
fill, green in both themes, exactly what ADR-0051 chose for the season chips. The season chips now call the
same function, so the seven call-site groups cannot drift apart. Unselected colours stay at Material's
defaults. Still no checkmark `leadingIcon`, for ADR-0051's reason; the outline an unselected chip has and
a selected one drops remains the non-color cue, and `CareTypeChip` keeps its care-type icon (tinted
`onPrimaryContainer` when selected).

**`DarkColorScheme` now sets `secondaryContainer = EarthBrownDark` and `onSecondaryContainer =
EarthBrownLight`**, so the remaining `secondaryContainer` consumers (`PlantCard`, `CalendarDayBadges`) get
an on-palette earth-brown in dark theme instead of the grey-purple fallback. Light theme's
`secondaryContainer` is unchanged.

## Consequences

- Selected chips read unambiguously as on in both themes, everywhere in the app.
- A new `FilterChip` must pass `colors = yaptFilterChipColors()`; a chip that omits it silently regresses
  to the Material defaults. There is no compile-time enforcement, only this ADR and review.
- Amends product ADR-0051's clause scoping the palette to `FertilizingSeasonsSelector` only.
- Compose UI tests keep asserting `assertIsSelected()`/`assertIsNotSelected()`, never colours (#420).
