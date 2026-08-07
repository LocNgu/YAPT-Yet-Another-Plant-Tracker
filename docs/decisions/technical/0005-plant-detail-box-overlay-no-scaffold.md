# ADR-0005: Box overlay pattern in PlantDetailScreen instead of Scaffold

**Status**: superseded by [ADR-0018](0018-plant-detail-tabs-in-box-overlay.md)

**Date**: 2024-01-01

## Context

`PlantDetailScreen` has a large hero photo (280 dp tall) that is meant to bleed behind the status bar with a gradient scrim, similar to a full-bleed magazine cover. Compose's `Scaffold` reserves space for the top app bar and doesn't support content extending behind the system bars in this way without significant workarounds.

Alternatives considered:
- **Scaffold with `TopAppBar`**: clean, conventional, but the top app bar sits below the status bar. The hero photo cannot bleed behind it without fighting Scaffold's padding system.
- **Scaffold with transparent TopAppBar + `WindowInsets` overrides**: possible but fragile — inset handling varies across API levels and the scrim must be managed separately.
- **Root `Surface` + `Box` overlay**: the `LazyColumn` scrolls freely; `IconButton`s, `FAB`, and `SnackbarHost` are positioned as overlay children of the `Box`. Full control over placement and system bar insets.

## Decision

`PlantDetailScreen` uses a root `Surface(color = colorScheme.background)` with a nested `Box`. The `LazyColumn` is a direct child of the `Box` and handles scroll. Back/edit buttons are overlaid with dark semi-transparent pill containers for legibility over the photo. The FAB and `SnackbarHost` are anchored to the bottom of the `Box` with `navigationBarsPadding()`.

The back/edit button tint is conditional on whether a cover photo exists:
- **Photo present**: white icons + semi-transparent black container (contrast over photo).
- **No photo**: theme `onSurface` icons + transparent container (theme colors are legible over the plain background).

See `PlantDetailScreen.kt`.

## Consequences

- The hero photo bleeds edge-to-edge behind the status bar as intended.
- All system bar insets must be managed manually (status bar height for the hero, navigation bar padding for the FAB). This is explicit rather than automatic.
- Any future screen that needs a similar full-bleed hero should use the same pattern rather than trying to achieve it through `Scaffold`.
