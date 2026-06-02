# Technical ADR-0014: BackupManager streams photos to temp files; export buffers via temp file

**Status**: accepted
**Date**: 2026-06-02

## Context

Two separate bugs affected backup reliability when photos were involved:

**Restore OOM (issue #193).** The original restore code read each photo entry in the ZIP into a `ByteArray` in memory before writing it to disk. For backups with many or large photos, this caused `OutOfMemoryError` on devices with limited heap.

**Export 0 KB to cloud SAF URIs (issue #144).** Android's Storage Access Framework (SAF) providers for cloud destinations (Google Drive, Dropbox, etc.) require a single, complete, seekable write. The original code wrote the ZIP entry-by-entry directly to the destination URI's `OutputStream`. Cloud providers typically buffer the write and only commit when the stream is closed — but some providers (Google Drive in particular) expect the content length upfront and produce a broken 0 KB file when fed an incremental stream.

## Decision

**Restore**: Each photo entry in the ZIP is streamed to a temporary `File` in `context.cacheDir` during traversal. The temp file path is inserted into a cleanup `Map<String, File>` **before** the `copyTo` call, so the `finally` block can always reach and delete it regardless of whether the copy succeeds or throws. After the copy, the temp file is moved to its final destination and deleted.

**Export**: The full ZIP is assembled into a temp file in `context.cacheDir` first (`File.createTempFile`). Once assembly is complete, the temp file is streamed to the SAF destination URI in a single buffered copy (`inputStream.copyTo(outputStream)`). The temp file is deleted in a `finally` block whether the copy succeeds or fails.

Additionally, the photo input-stream opener was extended to handle three URI schemes that can appear for restored photos:
- `content://` URIs: opened via `ContentResolver.openInputStream()`
- `file://` URIs: opened via `File(uri.path).inputStream()`
- Bare absolute paths (no scheme): opened via `File(path).inputStream()`

## Consequences

- Restoring a large backup no longer risks OOM: peak memory per photo is bounded by the stream buffer size (typically 8 KB), not the total photo size.
- Exporting to cloud SAF destinations produces a valid ZIP file because the provider receives a single complete stream.
- Temp files in `cacheDir` are always cleaned up — even on partial failures — because the map-entry-before-copy pattern ensures the `finally` block can always find them.
- A new `BackupManagerTest` case verifies the temp file is deleted on export failure.
- The `cacheDir` is on internal storage, so there is no significant performance difference versus writing directly to a local SAF URI.
