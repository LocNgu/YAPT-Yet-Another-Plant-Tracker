# ADR-0018: Plant Detail tab strip lives inside the Box overlay, not a Scaffold/TabRow header

**Status**: accepted

**Date**: 2026-07-31

## Context

Issue #436 (Option 2, the deferred follow-up to #434) restructures Plant Detail into per-action tabs (Water · Fertilize · Repot · Photo) with deeper insights and inline settings. Tabs are conventionally hosted in a `Scaffold` with a `TabRow` pinned under a `TopAppBar`.

That directly conflicts with **technical ADR-0005**, which deliberately avoids `Scaffold`/`TopAppBar` on Plant Detail so the 280 dp hero photo can bleed edge-to-edge behind the status bar. A pinned `TabRow` under a top app bar would reintroduce exactly the reserved-space / inset-fighting problems ADR-0005 was written to avoid.

Alternatives considered:

- **Adopt a `Scaffold` with a transparent `TopAppBar` + pinned `TabRow`.** Restores the conventional tab layout but sacrifices the full-bleed hero and reopens the API-level-dependent inset handling ADR-0005 rejected. Largest change; highest regression risk.
- **`Scaffold` with a collapsing-toolbar (hero collapses into the app bar).** Heavy dependency/complexity for a small offline app; still fights the "hero bleeds behind the status bar" intent.
- **Keep ADR-0005's root `Surface` + `Box` + `LazyColumn`, and place the tab strip *inside* the scrolling content, below the hero + name/species header.** The tab strip scrolls with the content rather than pinning under a system bar. No `Scaffold`, no `TopAppBar`; the hero, overlaid back/edit buttons, and bottom FAB/Snackbar are untouched.

## Decision

Plant Detail keeps the ADR-0005 pattern: root `Surface(colorScheme.background)` → `Box` → `LazyColumn`, with overlaid back/edit buttons and an anchored FAB/`SnackbarHost`. A Material 3 `PrimaryTabRow` (Water · Fertilize · Repot · Photo) is added as a `LazyColumn` item **below the hero and the name/species header**, and the selected tab's content renders as the following items. The `#434` quick-log `StatsRow` stays above the tab strip as an always-visible summary; the unified care-history list (all care types, incl. Prune/Note) stays below the tab content.

This **supersedes ADR-0005** — the "no Scaffold, Box overlay, manual insets" decision is retained and extended, not reversed. The tab strip is content inside the Box, so it inherits the same manual-inset model.

See `PlantDetailScreen.kt`.

## Consequences

- The hero photo still bleeds edge-to-edge behind the status bar; ADR-0005's manual system-bar inset handling is unchanged.
- The tab strip is not pinned — it scrolls off-screen with the hero. Accepted: Plant Detail is a browse/scroll surface, not a workspace that needs a persistent tab bar, and pinning would require the `Scaffold` layout ADR-0005 rejects.
- Any future full-bleed-hero screen that also needs tabs should follow this pattern (tabs as scrolling content in the Box) rather than reintroducing a `Scaffold`.
- Per-tab inline settings and richer insights (issue #436 sub-tasks 2–3) render as additional items under the selected tab within this same structure; they do not require revisiting the scaffold decision.
- Amended by ADR-0022 (Edit button scroll fade): the overlaid Edit icon button is no longer persistently interactive throughout scrolling as described above — it now fades out (and stops being clickable) once the hero photo has scrolled substantially out of view. Back and the FAB are unaffected and remain exactly as described here.
