---
description: Plant List search and multi-select
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

## Hidden plants (#915, product ADR-0066)
- `getAllPlants()`/`getAllRooms()` exclude hidden plants, so the list, search, sort, room chips and multi-select never see them. When no plant is visible but `hiddenCount > 0`, the empty state reads `no_plants_all_hidden` (points to Settings → Hidden plants). "Hidden" elsewhere in this file means scrolled or filtered out, not this state. Details: `rules/hidden-plants.md`.

## Multi-select is sticky (#449, product ADR-0063)
- **`selectedPlantIds` is never reconciled against what is visible.** Room chips (which stay enabled while selecting), sort filters, search and day changes never add to or prune it. No auto-pruning anywhere.
- **Room-chip dots:** in selection mode, a room chip (Unassigned included, never "All") whose room holds ≥ 1 selected plant shows a primary-color dot and a `stateDescription` ("Contains selected plants"), via `RoomFilterChip`. The bar stays a plain `N selected`.
  - `roomsWithSelection` (StateFlow<Set<String>>) joins `selectedPlantIds` with the full active plant list (`allPlants`), **not** `plantsWithStatus`, so hidden rooms still get a dot. Keys match the chips: the room name, or `UNASSIGNED_ROOM` for `room == null`. No per-plant Room queries.
- **`selectAll()` is a union** with the visible plants. **`bulkLog()` resolves plants from `plantRepository.getAllPlants()`**, not `plantsWithStatus`, so hidden plants are logged. Ids that no longer resolve to an active plant are skipped silently. `bulkArchive()` and its dialog text are unchanged.
