# Product ADR-0004: Default sort order and direction toggle rules for the plant list

**Status**: accepted

**Date**: 2024-01-01

## Context

The plant list supports multiple sort options. Several decisions needed to be made:

1. **What is the default sort?** The app has no concept of "importance" on first use, so a predictable, neutral default is needed.
2. **Which sorts are toggleable (ASC/DESC)?** Some sorts are inherently directional — reversing them produces a confusing or unhelpful result.
3. **What direction does each sort start in?** The first tap on a sort option should give the most useful ordering.
4. **Does the sort persist?** Users who prefer a particular sort would be frustrated if it reset every time.

## Decision

**Default sort**: Alphabetical A→Z. This is the most predictable and neutral entry point for new users — no assumptions about which plants are most important.

**Direction toggles**:
- **Alphabetical**: toggleable (A→Z / Z→A). Both directions are useful.
- **Watering due** and **Fertilizing due**: toggleable. Most-urgent-first (DESC) is the default; least-urgent-first (ASC) is the toggle.
- **Recently added**: not toggleable — always newest-first. Reversing to oldest-first (oldest plants first) has no practical use case.
- **Both due** (filter showing plants needing both watering and fertilizing): not toggleable — always sorted by watering urgency ascending. This is a task-focused filter where the user wants to act; the most urgent plant should always be first.

**Default direction on first selection**:
- Alphabetical → ASC (A→Z)
- Watering due, Fertilizing due → DESC (most urgent first)
- Recently added, Both due → DESC

**Persistence**: the active sort option and direction are saved to DataStore and restored on next app launch. The user's sorting preference is part of their workflow and should not reset.

See `PlantListViewModel.kt`, lines 30 and 102–126.

## Consequences

- Users who want to see their most urgent plants first must switch sort from the default, but this is a one-time action since the sort persists.
- BOTH_DUE's fixed ordering (no toggle) means it always surfaces the most pressing watering need first. If a user wants to prioritize fertilizing urgency within this filter, there is no way to do that today.
- Sort persistence means users who change the sort for a temporary task (e.g., "Recently added" to check a new plant) will see that sort next time they open the app. They must manually switch back.
