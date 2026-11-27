# Product ADR-0088: A home-screen widget for Care's watering queue

**Status**: accepted

**Date**: 2026-11-21

## Context

Issue #923 (from #810, phase 5): most YAPT visits answer "what needs watering now?" and log one or two things.
A home-screen widget can answer that without opening the app. #810 requires the widget to sit on top of the
Care queue (`TodayQueueAggregator` via `TodayCareRepository`, product ADR-0054, product ADR-0069) rather than
build a second way of choosing tasks.

Alternatives considered in the spec round on #923:
- **Every Care section** (treatments, fertilizing, reminders, repotting, photos). Rejected: those kinds have
  their own prompts and pickers that don't fit a widget, and a widget listing tasks it can't act on is noise.
- **A wider horizon** (a few days ahead). Rejected: the count must match Care, and Care's default path shows
  watering through tomorrow only.
- **A count-only privacy mode.** Rejected for now: plant names already appear in the per-plant reminder
  notifications, so the widget shows nothing new.
- **A separate set of due rules or Room queries for the widget.** Rejected: two implementations would drift.

## Decision

- **Scope: watering only.** The widget shows the WATER task and the combined water-and-fertilize task from
  Care's **default** queue path (`TodayCareRepository.observeQueue()` with no caller horizon). Every other kind
  stays in the app.
- **Count = Overdue + Today**, as distinct plants, the same numbers as Care's Watering · Overdue and
  Watering · Today headers. Tomorrow's watering is listed below under "Tomorrow" and not counted. Rows are
  grouped Overdue / Today / Tomorrow in Care's order. Groups and counts come from `careTypeSections()`; the
  widget does no date math. There is no overdue colouring, the same as Care's tiles (#842).
- **States:** no visible plants shows a first-use message; plants but nothing due shows "Nothing to water
  today", still listing tomorrow's rows if any.
- **Taps:** the header and the message states open Care; a row opens that plant's Plant Detail on the same
  tab Care's tile opens (`TodayCareKind.plantDetailTab()`: Water, or Fertilize for the combined task). Neither
  assumes Plants is on the back stack (#836).
- **Plant names are shown**, like the reminder notifications. There is no extra setting.
- **Away mode:** the same tasks as Care, with no trip banner (product ADR-0065).
- **Size:** resizable from a 2×2 minimum (count plus about one row); the list scrolls and fills the height.
- **Palette:** YAPT's own light and dark Material colour schemes, never wallpaper-derived dynamic colour and
  never due-status or issue colours.
- **Quick actions** (later slice of #923, recorded here so they are decided once):
  - **Watered** logs through `QuickLogUseCase`, including the same-day duplicate rejection and the
    post-watering reminder. It logs in place only when Care's watering gate (`WateringTaskAction.skipsReasonPrompt` /
    `wateringGateFacts()`) would skip the reason prompt; otherwise it opens the app at that plant's prompt
    (product ADR-0030). The combined task logs both types through Care's paired-insert path.
  - **Not now** has the reminder notification's semantics: override +1 day, never a learning signal
    (product ADR-0007).
  - No bulk completion (#842).
- **Freshness** (later slice of #923): one `WidgetUpdater` hook owned by `YaptApplication`, triggered by care,
  plant and restore writes, at local midnight, and on boot and app update. Until then the system's 30-minute
  update period is the fallback.

## Consequences

- The widget can never disagree with Care's watering sections, because it maps the same snapshot through the
  same grouping. A change to Care's watering buckets changes the widget too.
- Non-watering care is never visible on the home screen. Widening the scope needs a new decision.
- Adds the Jetpack Glance dependency (`glance-appwidget`, `glance-material3`), pinned inline in
  `app/build.gradle.kts`.
- Follow-up work on #923: the refresh hook and the two quick actions.
