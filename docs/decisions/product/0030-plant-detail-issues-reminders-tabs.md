# ADR-0030: Custom Reminders and Active Issues become their own Plant Detail tabs, with a collapsible tab row

**Status**: accepted

**Date**: 2026-08-28

## Context

Issue #590. `PlantDetailTab` (#436, technical ADR-0018) gave Plant Detail four per-action tabs —
Water · Fertilize · Repot · Photo — behind `FeatureFlagRegistry.PLANT_DETAIL_TABS`. Two other
sections, `CustomRemindersCard` (technical ADR-0019, #232) and `PlantIssuesCard` ("Active issues",
technical ADR-0020, #564), stayed always-visible cards rendered *outside* the tab strip in both the
classic and tabs layouts.

Folding those two cards into the tab strip grows it from 4 tabs to 6, which does not fit comfortably
in one row at each tab's current fixed width. The question was how to add the two tabs without either
shrinking every tab to fit six across, or reintroducing horizontal scrolling (`ScrollableTabRow`),
both of which would change the look of the four tabs everyone already has muscle memory for.

Alternatives considered:

- **Shrink every tab to fit 6 in one row.** Keeps a single row but makes every tab (including the
  four existing ones) narrower and more cramped — a visual regression for the common case to
  accommodate a less-common one.
- **`ScrollableTabRow`.** Standard Material pattern for many tabs, but hides the two new tabs behind a
  horizontal swipe with no visual hint of their existence, and changes the existing four tabs' behavior
  (they stop being a fixed, always-fully-visible row).
- **`FlowRow` of individually-sized `Tab`s that wraps to a second row, with a collapse/expand toggle
  defaulting to collapsed.** Every tab — new or old — keeps the exact same fixed width
  (`Modifier.fillMaxWidth(0.25f)`) it has today. Collapsed, the strip is pixel-identical to the current
  4-tab row. Expanding reveals the 2 new tabs wrapped onto a second row at that same width, and an
  attention badge on the toggle signals when something worth expanding for is hidden.

## Decision

`PlantDetailTab` gains two entries, in this order: `WATER, FERTILIZE, REPOT, PHOTO,
CUSTOM_REMINDERS, ISSUES`. `CustomRemindersCard`/`PlantIssuesCard` move from always-visible
`LazyColumn` items into the `when (selectedTab)` block, rendered only when `CUSTOM_REMINDERS`/`ISSUES`
is selected — same composables, same params, same add/edit/delete/mark-done/report/resolve behavior,
just a different slot.

This only applies to the **tabs layout** (`PLANT_DETAIL_TABS` on). The **classic (flag-off) layout has
no tab strip at all** and is unchanged, byte-for-byte: `CustomRemindersCard`/`PlantIssuesCard` stay
always-visible there, in their existing position after the watering-due actions row / chart / photo
gallery. No new flag — this is additive UI inside the strip `PLANT_DETAIL_TABS` already gates.

**Tab row collapse/expand** (`PlantDetailTabStrip` in `PlantDetailScreen.kt`):
- A `FlowRow` (not `TabRow`/`PrimaryTabRow`) of standalone Material3 `Tab` composables, each
  `Modifier.fillMaxWidth(0.25f)`. Collapsed (default) shows only the first four entries
  (`PlantDetailTab.entries.take(4)`) — today's exact look, filling exactly one row. Expanded shows all
  six; the extra two wrap naturally onto a second row at the same per-tab width — no shrinking, no
  horizontal scrolling.
- Toggle state is `var isTabRowExpanded by rememberSaveable { mutableStateOf(false) }` — screen/session
  local, **not** a `DataStore` setting, mirroring this screen's existing `selectedTab` and
  care-history-expand ephemeral-state conventions. It resets to collapsed on every fresh visit.
- The toggle control reuses the exact chevron-rotate pattern the care-history `AssistChip` already uses
  in this file (`animateFloatAsState` rotating `Icons.Filled.ExpandMore` 180°) rather than new
  iconography, with a `contentDescription` that flips between "Show all tabs"/"Show fewer tabs"
  (`R.string.plant_detail_tabs_expand_cd`/`plant_detail_tabs_collapse_cd`).
- **Attention badge**: a small `Badge` on the toggle, shown only when **collapsed** *and*
  (`activeIssues.isNotEmpty()` or any `CustomReminderStatus.isOverdue == true`) — both signals already
  collected in `PlantDetailScreen.kt` for the (now tab-gated) cards, reused as-is, no new queries. Gated
  on overdue (not due-soon) so the badge means "needs attention now." Hidden once expanded, since
  everything is already visible then.
- **Orphaned selection**: if `selectedTab` is `CUSTOM_REMINDERS` or `ISSUES` at the moment the row
  collapses, it resets to `WATER` so nothing stays selected-but-hidden behind the collapsed row.

## Consequences

- The tab strip's visual footprint stays identical to today for anyone who never expands it — this is
  a purely additive change from that vantage point.
- `CustomRemindersCard`/`PlantIssuesCard` are one tap (or two, if the row is collapsed) further away in
  the tabs layout than they were as always-visible cards. The attention badge is the mitigation: a user
  who never has an overdue reminder or open issue never needs to think about the second row at all.
- This does **not** supersede technical ADR-0019 or ADR-0020 — those describe the child-table/repository
  architecture behind custom reminders and plant issues, which is untouched. This ADR is purely about
  *where* their cards render, the same kind of UI-placement decision as product ADR-0023.
- Per ADR-0022's flag-lifecycle rule, when `PLANT_DETAIL_TABS` eventually graduates, the classic-layout
  branch (and its now-duplicated always-visible `CustomRemindersCard`/`PlantIssuesCard` rendering) is
  deleted in that same graduating PR, leaving the tab-strip version as the only layout.
