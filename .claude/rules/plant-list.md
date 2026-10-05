---
description: Plant List search
paths:
  - "app/src/main/kotlin/com/yapt/planttracker/ui/screens/plantlist/**/*"
  - "app/src/test/**/plantlist/**/*"
  - "app/src/androidTest/**/plantlist/**/*"
---

# Plant List

## Search (#512, product ADR-0055)
- **UI:** a top-bar search icon expands the app bar in place (`[back] [field] [clear ✕] [Sort]`, Settings hidden). It is never a persistent row.
  - The icon shows only when `rooms.isNotEmpty() || hasUnassignedPlants` (the room-chip gate, i.e. ≥ 1 active plant). Once opened, search stays open.
  - Selection mode's contextual bar replaces the search bar (search stays applied underneath); Back exits selection first.
- **Matching** (`PlantSearch.kt`'s pure `matchesSearchQuery()`): case-insensitive, accent-folded (`Normalizer` NFD + strip combining marks) substring of the trimmed query against `name` **or** `species`, never room or notes.
- **The query never enters the DB-bound combine.** `plantsWithStatusBeforeSearch` does room filter → `buildStatus()` (per-plant Room queries) → `applySortOrder()`. `plantsWithStatus` is a cheap downstream `combine` that only `filter{}`s by the query, so typing never re-runs a Room query (that was a shipped bug).
  - The predicate is per-plant, so it commutes with room chips and sort filters (`BOTH_DUE`/`CARED_FOR_TODAY`/`ACTIVE_ISSUES`).
  - A non-blank query with no results shows its own empty state, ahead of the sort/room messages.
- **State:**
  - `searchQueryText`/`isSearchActive` are in-memory and ViewModel-scoped like `selectedRoom`: they survive Plant Detail → back and reset on process death. There is no DataStore key.
  - `searchQueryText` is a synchronous `var … by mutableStateOf("")` for the TextField. The same `setSearchQuery()` also updates the private `MutableStateFlow` the combine reads, so the two can't drift.
  - Auto-focus on a fresh icon tap uses a consumed-once `pendingSearchAutoFocus` field, not a `Flow` (a `SharedFlow` emission can race its `LaunchedEffect` collector).
