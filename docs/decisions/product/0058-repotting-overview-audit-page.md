# Product ADR-0058: Repotting overview — an audit page for plants not repotted in a long time

**Status**: accepted

**Date**: 2026-09-30

## Context

The repotting reminder (product ADR-0022) and the one-off repot plan (product ADR-0057) only cover plants
where the user set an interval or made a plan. Most plants have neither, so they never reach Care or the
daily notification, yet they still want an occasional repot. Issue #525 asks for a way to see which active
plants haven't been repotted in ages, including ones never repotted and ones with no reminder. Product
ADR-0057 already names "a Planned group on the repotting overview page (#525)" as a follow-up; this ADR
delivers it.

Alternatives considered:

- **A Plant List sort option.** Rejected. A sort reorders a list the user already scans for due care; this
  is a different question ("which plants have I neglected?") that needs a cut-off, not an order.
- **A reminder or a Care section.** Rejected. Care and the notification own "this plant is due"
  (product ADR-0054/0056); an age-based nudge for plants with no interval would add noise to a queue that
  is deliberately only about what the user asked to be reminded of.
- **Measure never-repotted plants from nothing (list them under every year chip).** Rejected. A plant
  added last week would read as neglected.
- **Skip the "never repotted" distinction and use only `createdAt`.** Rejected. A plant bought three years
  ago and never repotted is exactly the audit's target; "Never" must also list a plant added yesterday so
  the user can see every plant whose pot has never been touched.

## Decision

- **An audit page, not a sort or a reminder.** The Repotting overview is its own screen, reached from
  Settings. It never creates a Care task, a notification, or a sort option, and it does not flag plants
  whose reminder is overdue (Care already does). A plant with no `repottingIntervalDays` is listed like any
  other.
- **Anchor is `lastRepot ?: createdAt`** — the same anchor product ADR-0022 uses for the first due date.
  Year chips (1+, 2+, 3+ yr) match a plant when `anchor.toLocalDate().plusYears(N) <= today`: calendar
  years (technical ADR-0013), never `N * 365` days, so a Feb 29 anchor reaches its anniversary on Feb 28 of
  a non-leap year. A plant added less than N years ago and never repotted is therefore not listed.
- **"Never" is its own chip** beside the year chips: every active plant with no REPOT log, however
  recently added. The chip row is Never, 1+ yr, 2+ yr, 3+ yr; the default is 2+ yr.
- **Planned plants are excluded from the audit and the count.** A plant with `repotPlanSeasonStartAt`
  (product ADR-0057) has had its repot decided, so it appears only in a **Planned group** shown above the
  list under every chip, labelled with the plan's season and year, with an ended plan marked overdue. The
  season, year and in-season/ended state come from `SeasonalRepotting.resolvePlan()`, never from the raw
  timestamp. Archived plants never appear anywhere.
- **Order**: oldest anchor first, ties by name. Each item records whether the plant was never repotted so
  the screen can say "Never repotted · added Mar 2023" rather than "Last repotted Mar 2023".
- **One pure function** (`RepottingOverviewBuilder`, `domain/repotting/`) produces the Planned group, the
  threshold list and the count, so the page and Settings' count subtitle can't drift.
- **The remembered chip is a view preference** (`SettingsKeys.REPOTTING_OVERVIEW_THRESHOLD`, the enum name,
  read with `runCatching { valueOf }.getOrDefault(TWO_YEARS)`). Like `SORT_OPTION` it is deliberately not
  part of `.yapt` backups.
- **No schema change.** The last REPOT per plant is a reactive grouped query over `care_logs`, so the page
  updates when a repot is logged, edited or deleted.

## Consequences

- Plants with neither an interval nor a plan get a place to be noticed, without touching Care, the
  notification, or any due-date rule.
- A never-repotted plant's age is visible, but it is only judged against a year chip from the day it was
  added, so a new plant is never mislabelled as neglected.
- The threshold is restored per device and not carried by a backup or restore.
- Finer or custom thresholds, bulk actions, and promoting the entry point beyond Settings are out of scope.
- Delivered in two parts: data, domain and this ADR first (#864, no user-visible change); the screen,
  Settings entry and row menu second.
