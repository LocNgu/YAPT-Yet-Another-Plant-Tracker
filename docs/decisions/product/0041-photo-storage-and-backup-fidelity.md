# Product ADR-0041: Bound private camera captures and make lossy backup optimization explicit

**Status**: accepted

**Date**: 2026-09-15

## Context

YAPT stores photo URI references in Room. Gallery-picked images remain owned by the user's media library, but
in-app camera captures are permanent files under `filesDir/images`. Modern full-resolution captures commonly use
several megabytes each, so they can dominate both app storage and photo-inclusive `.yapt` exports. That directory
also participates in Android Auto Backup while the platform quota is only 25 MB, making unbounded captures likely
to crowd out the app's remaining backup data.

Issue #309 considered four strategies: resize/re-encode private captures, publish captures to MediaStore, remove
in-app capture, and periodically delete orphaned private files. MediaStore would mix YAPT photos into the user's
gallery and survive uninstall unexpectedly; removing capture would make routine logging a two-step flow. Orphan
cleanup is complementary rather than a size bound and is tracked separately by #736.

Photo-inclusive `.yapt` exports have a separate concern: they copy gallery-owned originals into the archive so a
restore is portable. Optimizing those copies can greatly reduce an export, but a backup is the app's primary user-
controlled safety net and lossy conversion must not happen without an explicit choice. PNG transparency must not
be discarded merely to obtain JPEG compression.

## Decision

New in-app camera captures are bounded to 1920 px on their longest side and encoded as JPEG at quality 80. Existing
private camera JPEGs receive the same treatment once after update; the migration skips already-bounded files so it
is safe to resume after process death. The stable FileProvider URI is committed before background optimization, so
cancellation leaves a valid full-size photo rather than an orphan.

The export dialog offers **Optimize photos for a smaller backup**, unchecked by default. When selected, YAPT
optimizes only temporary archive copies and never changes gallery originals. JPEG copies may be lossy; PNG copies
remain PNG to preserve transparency; unsupported formats are copied unchanged. Archive entries retain their
source extension whether optimization succeeds or falls back.

## Consequences

Private camera captures permanently lose resolution above 1920 px and JPEG detail beyond quality 80. This is
accepted because YAPT displays photos on phone-sized surfaces rather than serving as the original-photo archive.
Users can choose much smaller portable backups, but original-fidelity export remains the default. Optimization uses
one temporary photo at a time, limiting extra disk use to the current image plus the ZIP being assembled. Orphan
reconciliation remains out of scope here and is handled by #736.
