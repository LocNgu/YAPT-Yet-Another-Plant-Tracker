# Product ADR-0078: The cover photo falls back within the cover-eligible pool

**Status**: accepted

**Date**: 2026-11-03

**Amends**: [ADR-0015](0015-photo-carelog-updates-cover-photo.md) (spells out what happens when a cover-eligible photo is deleted).

## Context

Issue #323. Deleting the cover photo used to fall back to the next `plant_photos` entry only. Two gaps followed: a
deleted `PHOTO`-log cover kept pointing at a photo no gallery entry owned, and a plant that only uses the Photo tab
(no `plant_photos` rows) lost its cover even when other Photo-tab photos remained. Allowing any gallery photo as the
fallback was rejected: photos on WATER, FERTILIZE, REPOT and PRUNE logs are editorially not cover material (product
ADR-0012, ADR-0015).

## Decision

- The cover-eligible pool is `plant_photos` plus photos on `CareType.PHOTO` care logs.
- When the deleted photo's URI equals the plant's current cover (read fresh inside `plantEditMutex`), the cover
  moves to the newest photo remaining in the pool, or to `null` when the pool is empty. The trigger is the URI, not
  the photo's source, so it also covers a legacy non-`PHOTO` log photo that happens to be the cover.
- This applies to gallery deletes of both sources and to deleting a whole care-log entry that carries the photo.
- Deleting a photo that is not the cover never changes the cover. Files are reclaimed by `OrphanPhotoSweeper`.

## Consequences

- #511 (explicit set-as-cover) builds on this and only records how a hand-picked cover interacts with implicit
  promotion.
- Non-`PHOTO` log photos remain visible in the gallery but are never promoted to cover by a deletion.
