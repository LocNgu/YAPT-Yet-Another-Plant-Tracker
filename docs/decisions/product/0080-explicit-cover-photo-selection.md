# Product ADR-0080: The user can hand-pick any gallery photo as the cover

**Status**: accepted

**Date**: 2026-11-04

**Amends**: [ADR-0015](0015-photo-carelog-updates-cover-photo.md) (adds explicit selection beside implicit promotion).

## Context

Issue #511. The cover was only ever set implicitly: ADR-0012 limited it to Add/Edit Plant and ADR-0015 added PHOTO
logs. Explicit user choice was option 3 in ADR-0012, deferred at the time.

## Decision

- Any photo in the plant's gallery can be set as the cover from `FullScreenPhotoViewer`, including legacy photos on
  WATER, FERTILIZE or REPOT logs. An explicit choice is not automatic promotion, so ADR-0012's editorial concern does
  not apply.
- A hand-picked cover is a one-time set, not a lock: the next photo added through any existing promotion site
  becomes the cover (newest wins). No new column and no Room migration.
- Deleting a hand-picked cover falls back exactly as in [ADR-0078](0078-cover-photo-falls-back-within-the-cover-eligible-pool.md);
  its pool and fallback are unchanged, so a legacy non-PHOTO photo is never promoted by a deletion.
- The current cover's thumbnail carries a badge; in the viewer the action is selected and disabled on the cover.

## Consequences

- No "pinned cover" lock or unpin UI. Supersedes none of ADR-0012/0015.
