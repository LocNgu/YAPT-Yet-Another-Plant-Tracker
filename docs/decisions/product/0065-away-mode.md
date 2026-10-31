# Product ADR-0065: Away mode — trips pause reminders, stay out of learning, and feed a plant-sitter list

**Status**: accepted — Before you leave availability and listing rules amended by [ADR-0076](0076-away-mode-lines-up-waterings-around-a-trip.md)

**Date**: 2026-10-15

## Context

The owner travels (#810). While nobody is home nobody waters, so daily reminders are noise, and the long gaps
between waterings would teach the adaptive model that a plant needs water far less often than it does. A plant
sitter, in turn, needs to know which of up to 72 plants to water, and names alone don't say, because several
plants of one species often share a room.

Dormancy (product ADR-0044/0046/0047) cannot express this. It is per plant and month-granular, and it describes the
plant (it rests). A trip describes the owner: nobody is there to water.

This ADR records the decision for the whole feature, as product ADR-0044 did for dormancy (#895). It lands in five
dependency-ordered slices (#896-#900). The first, #896, ships storage, date logic and backup only, with no
user-visible or behavioural change.

Alternatives rejected:

- **Shift every due date past the trip.** It means one plant-row write per plant (the #808/#872 class of bug), a
  cancelled trip could not undo it cleanly, and the changes would show up as reschedules.
- **A per-plant "away" flag.** Trips are global; a per-plant flag is dormancy under another name.
- **Cancel the daily reminder work for the trip.** See technical ADR-0038: a stateless gate recovers by itself.

## Decision

### Trips

- A trip is an inclusive range of local calendar days. Its first day may already be past while the trip is ongoing,
  but its last day can't be. At most 90 days, and one current or upcoming trip at a time. A trip that has already
  ended can't be entered after the fact.
- The owner plans a trip in Settings, under **Away mode**, which is always visible: away days matter for learning
  even with notifications off. They can edit its dates, cancel it while it is upcoming, or end it early with **I'm
  back**: the last day becomes yesterday, or the trip is removed when that falls before its first day.
- When an edit or **I'm back** leaves sitter visits outside the trip, they are deleted and the UI says how many.
  WATER logs those visits already wrote stay.

### Reminders and due dates

- No daily care notifications on away days, per plant or combined. The worker still clears the previous day's
  notifications, then posts nothing. Reminders resume on their own the day after the last away day. Any change to
  whether today is an away day refreshes the notification shade at once.
- **Due dates are untouched.** Away mode writes nothing to a plant row: no overrides, no interval changes. After the
  trip anything that came due shows as due or overdue as usual; watering reminders are already "Check {plant}"
  check-ins (product ADR-0027). Care keeps listing tasks during the trip so the queue stays truthful.
- The post-watering ("drain the saucer") reminder is unchanged for the owner's own waterings. Logs written for
  sitter visits never schedule it, because the owner isn't there. This narrows the "every WATER insertion path"
  coverage that product ADR-0036 retains from product ADR-0035.

### Learning

- A WATER observation whose gap overlaps an away day is excluded the way a dormancy-spanning one is: the base does
  not move, the confidence transition is suppressed, feedback is stripped, and the history bootstrap skips the
  gap. "Why this date?" shows an `AWAY_EXCLUDED` row. When a gap spans both a trip and dormancy, dormancy wins the
  label.
- There is no reason prompt for such a watering on any quick-water surface, including a backdated date on Plant
  Detail.
- There is **no exit decrement**: the plant didn't change, the owner was just away. This covers every log written
  inside a trip, including sitter visits.

### Care banner

Shown while a trip is active and for a week before it starts. While away it reads "Away until Oct 21 · reminders
paused"; while upcoming it says when reminders will pause. Actions: **I'm back** and **Plan a sitter visit** while
away, **Before you leave** on the last day at home.

### Plant-sitter flow (revision 3)

Visit dates cannot be fixed before the trip, because a sitter usually offers a visit a day or so ahead, and plants
will not get their ideal schedule while the owner is away. The plan shows that compromise instead of hiding it.
Whenever the plan has to guess it **leans dry**: better dry than root rot from over-watering.

1. **Rhythm (trip setup).** "How often should a sitter come?" offers every 4 / 5 / 7 / 10 / 14 days. Each option
   shows how many plants would go **longer than usual** (at least 1.5x their usual interval, assuming everything
   due is watered on the last day at home). YAPT recommends the longest rhythm with nothing flagged, otherwise the
   one with the fewest, and offers copyable text. The chosen rhythm is saved on the trip.
2. **Before you leave.** On the last day at home, with the first visit estimated from the rhythm: plants due
   strictly before halfway to the first visit are listed under **Water today** (ticked); plants due from halfway
   up to the first visit under **Check the soil** (unticked). One tap logs the ticked plants in bulk with no "Why
   now?" prompt, and the logs stay out of learning.
3. **Planning a visit, one at a time while away.** The owner picks **Today**, **Tomorrow** or a date inside the trip
   (not in the past), plus "next visit after that, roughly in N days" (default: the rhythm). At a visit on day *v*
   with the next visit about *g* days later, a plant is listed if it is due by *v* or due strictly before
   *v + g/2*; ties go to the next visit. Earlier visits count as waterings: a confirmed visit counts its ticked
   plants, an unconfirmed one its whole list, one that didn't happen nothing. The plan warns about plants **missed
   last time**, plants going **longer than usual**, listed plants with **no photo**, and look-alikes (same name and
   room, no photo). A visit is saved with its plant list when the owner shares it; re-planning the same day
   replaces the list.
4. **What the sitter gets.** A PDF for one visit: that day's plants grouped by room, each with cover photo, name,
   species (when it differs), a tick box and a **Missed last time** tag where it applies. A plant without a photo
   gets a "No photo" box with its name. A last page, **Please don't water these**, shows resting plants (dormant or
   suspended) with photos. **Share as text** stays as a fallback. There is no "no watering needed" list: at this
   scale it is noise. The list is watering only; archived plants and plants without a watering schedule never
   appear. It is shared through the Android share sheet; YAPT stays offline.
5. **Confirming visits.** Before planning the next visit the owner confirms the last one: a checklist with every
   plant ticked, where they untick what the sitter skipped, or choose **The visit didn't happen**. Confirming
   writes WATER logs dated on the visit day for the ticked plants, with no reason prompt and no post-watering
   reminder; unticked plants are tagged **Missed last time** on the next list. Skipping the confirm step plans the
   next list as if the whole previous list was watered; the visit stays unconfirmed and writes no logs. After the
   trip, Care offers every unconfirmed visit, oldest first, for two weeks or until each is answered; Settings lists
   the trip's visits and can confirm a planned one at any time.

### Invariants

- Away mode never writes a plant row. Planning, editing, cancelling or ending a trip leaves due dates, overrides,
  intervals and confidence untouched.
- Every log Away mode writes goes through `QuickLogUseCase`, with the same-day duplicate guard.
- Every due date in the sitter flow comes from `TodayQueueAggregator`; planned waterings are simulated as synthetic
  WATER logs. No new due rules are introduced.

## Consequences

- Reminders pause and learning is protected without touching a single plant, so cancelling or shortening a trip
  needs no undo.
- A plant can come due during a trip and sit overdue in Care until the owner is back; that is deliberate, and the
  sitter list is how it is addressed in the meantime.
- Gaps that merely touch a trip's first or last day are excluded, even though the plant was only partly unattended.
  Excluding too much is the safe side for an adaptive model.
- Per-plant or partial trips, recurring trips, entering a trip after the fact, logging an unplanned visit, and
  reminders sent to the sitter are out of scope.
- Backup schema v22 and DB version 18 carry the trips, visits and visit plants, so slices 2-5 need no further
  migration (technical ADR-0038).
