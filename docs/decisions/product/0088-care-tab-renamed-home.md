# Product ADR-0088: The Care tab is renamed Home; Plant Detail's Home tab becomes Overview

**Status**: accepted

**Date**: 2026-11-22

## Context

Issue #944. Product ADR-0054 renamed "Today" to "Care" because the queue spans Overdue plus upcoming days. In use,
the first tab is the app's landing screen, and "Care" with a checklist icon reads as one feature rather than the
place to start. Plant Detail already has a tab called "Home" (product ADR-0060), which would clash with a root
tab of the same name.

## Decision

- The first root tab's user-facing name is **Home**: tab label, top-bar title and copy that names the tab itself
  (e.g. the hide-plant hints). Descriptive uses ("care due", "care queue", care types, reminder notifications)
  keep "care".
- The tab icon is `Icons.Filled.Home` when selected and `Icons.Outlined.Home` when not, replacing the checklist.
- Plant Detail's "Home" tab is renamed **Overview** (label, content description, attention-badge description).
- Internal identifiers keep their names: `Screen.Today`, route `today`, `Today*` classes, string resource names,
  `PlantDetailTab.HOME`.
- Past What's New entries stay as written; only the `unreleased` bullet announces the rename.

## Consequences

The two product-level names no longer collide. Docs and tests use "Home" for the root tab and "Overview" for the
Plant Detail tab; code still says "Today" and `HOME`, which is deliberate.
