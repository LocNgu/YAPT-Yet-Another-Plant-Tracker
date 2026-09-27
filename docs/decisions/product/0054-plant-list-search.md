# Product ADR-0054: Plant list search — top-bar icon, name+species scope, accent-folded matching

**Status**: accepted

**Date**: 2026-09-27

## Context

The plant list could only be narrowed by room chips, the sort dropdown (including its "Water +
Fertilize due"/"Cared for today"/"Active issues" internal filters), and the "Water + Fertilize due"
filter itself. With a larger collection, finding one specific plant meant scrolling — there was no way
to search by name (#512).

Several shapes were considered for where the search field lives, what it matches against, and how it
interacts with the room filter/sort:

- A persistent, always-visible Material 3 `SearchBar` row above the list, vs. a top-bar icon that
  expands into an inline field in place.
- Matching plant name only, vs. also species, room, and/or notes.
- Plain substring matching, vs. diacritic-insensitive folding, vs. prefix-only or per-word tokenized
  matching.
- Whether search composes with the existing room filter and sort, or bypasses/clears them.
- Whether the query/search-mode persists across navigation, survives process death, or is stored in
  `DataStore` like the sort option.
- Whether search is reachable at all while multi-select mode's contextual top bar is showing.

## Decision

**Placement:** a search icon in the normal top bar (`My plants` title, Search / Sort / Settings
actions) expands the bar in place into `[back arrow] [field] [clear ✕] [Sort]`, hiding Settings — not
a persistent always-visible search row. The icon is shown only when the user has at least one active
plant (mirroring the room-chip row's own conditional visibility — both conditions are true under
exactly the same circumstance), but once search mode is opened it stays open even if that count later
drops to zero. A back arrow or the system Back button closes search mode and clears the query; the
clear (✕) button only empties the query and keeps search mode (and the keyboard) active. The IME's
"Search" action just hides the keyboard — filtering is already live as the user types, so there is
nothing left to "submit."

**Match scope and algorithm:** the trimmed query matches as a case-insensitive, accent-folded substring
of `Plant.name` **or** `Plant.species` (either field independently; a null species just can't match) —
not `room` or `notes`. Folding is NFD decomposition + stripping combining marks
(`java.text.Normalizer`, no new dependency) on both the query and the compared field before the
substring check, so "grun" matches "Grünlilie"; a character with no canonical decomposition (ß, ø, æ,
đ) is left exactly as-is, never expanded or otherwise special-cased. The whole trimmed query is treated
as one substring — no per-word tokenizing, no prefix-only restriction. A blank/whitespace-only query is
treated as no filter at all, same as an empty query.

**Composition with existing controls:** search narrows whatever the active room-filter-chip selection
and sort option already produce, including `BOTH_DUE`/`CARED_FOR_TODAY`/`ACTIVE_ISSUES`'s own internal
filtering — it is one more `AND`ed predicate in `PlantListViewModel.plantsWithStatus`'s `combine`, not a
mode that bypasses or clears the room chips/sort. The room-chip row itself stays visible and usable
while search mode is open; only the top bar's own row swaps. Product ADR-0018's date-group dividers
keep applying to search results — there is no flat-rendering special case for a searched list. Select-
all while searching selects only the currently-matching plants, for free, since it already reads the
same filtered `plantsWithStatus`.

**Empty state:** a dedicated "No plants match "%1$s"" message takes priority over every other empty
state (sort-based or room-based) whenever the query is non-blank and nothing matches.

**Persistence:** the query and whether search mode is open are in-memory `PlantListViewModel` state,
the same lifetime as the existing `selectedRoom` field — they survive navigating to Plant Detail and
back (same ViewModel instance across that back-stack entry), but reset on process death. There is no
`DataStore` key for either, unlike the sort option; a search is a momentary, in-the-moment lookup, not
a standing preference. Auto-focus/keyboard is armed only by a genuine tap of the search icon, not by
returning to an already-open search — the field does not silently steal focus/keyboard on an unrelated
navigation-back.

**Selection mode:** entering multi-select (long-press a card) replaces the search bar with the
contextual selection bar exactly as it already replaces the normal bar — the query stays applied (the
list stays filtered) and search mode is restored automatically once selection ends, since neither is
touched while selecting. System Back exits selection mode first when both are active; it does not also
close search.

## Consequences

- Room and notes are not searchable — a plant's location or free-text notes can't be used to find it
  this way. The card already shows species directly under the name, so every match this feature does
  produce is visibly self-explaining; room/notes search was explicitly deferred rather than ruled out
  forever.
- No tokenized/per-word matching — a query like "green fig" only matches a name/species containing that
  exact substring, not a plant matching "green" and "fig" as separate words in either order. This keeps
  the matching function trivial and predictable; multi-word search can be revisited if it's requested.
- No search-query persistence of any kind (no `DataStore`, no `SavedStateHandle` across process death)
  keeps this feature's state model as simple as possible and avoids a new settings surface for something
  inherently transient — the trade-off is that a killed-and-relaunched app always starts from a clean,
  unfiltered list even mid-search.
- The top-bar-icon shape (vs. a persistent `SearchBar`) keeps the normal list view exactly as it looks
  today for every user who never searches, at the cost of one extra tap to start a search compared to a
  bar that's always present.
