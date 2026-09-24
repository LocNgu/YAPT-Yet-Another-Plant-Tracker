# Product ADR-0048: Slider + flanking stepper buttons on every integer interval slider

**Status**: accepted — Plant Detail inline card tap-commit clause amended by [ADR-0050](0050-coalesce-stepper-tap-bursts-on-plant-detail.md)

**Date**: 2026-09-23

## Context

Issue #531 (a follow-up to #436/PR #507) reported that the watering and fertilizing interval sliders
are hard to land on an exact day: the watering slider spans 1–60 days (58 steps) and, by the time this
issue was revisited, fertilizing had grown to 1–180 days (178 steps, up from the 1–90 the issue
originally quoted) — on a phone-width track a single pixel of drag covers more than one day, so hitting
the intended value takes fiddling. By the time of implementation, three more integer sliders existed:
Add/Edit Plant's repotting interval (3–36 months, #232), Plant Detail's inline watering/fertilizing
cards (product ADR-0023, mirroring Add/Edit), and the dormant watering cadence slider (1–12 weeks,
product ADR-0046/ADR-0047) — all six needed the same fix for the app to stay internally consistent.

Alternatives considered:

- **Pure stepper (no slider), −/+ only.** Rejected — a 30→90 day change becomes a 60-tap job on the
  fertilizing range; coarse adjustment would take far longer than a drag.
- **Slider only (status quo).** Rejected — this is the precision problem the issue exists to fix.
- **A scroll-wheel/picker-style control.** Rejected — no Material 3 component of this kind exists;
  building or adopting one would pull in a third-party dependency, which this app avoids (manual DI,
  no new libraries without a clear need).
- **A fine-scrub or magnifier gesture on the slider itself** (the way some iOS-style sliders let a
  vertical drag increase precision). Rejected — Material 3's `Slider` has no such built-in mode, and
  building a custom one is a much larger surface than this low-risk control change warrants.
- **Slider plus flanking −/+ `IconButton`s** (chosen). Keeps the slider's coarse-grained drag for large
  jumps and adds an exact ±1 nudge for the last mile — the standard Material/Android pattern for this
  exact problem, and it needs no new dependency. There was already an in-app precedent for this shape:
  the now-removed "Skip watering" stepper dialog used `IconButton(Icons.Filled.Remove)` / value / 
  `IconButton(Icons.Filled.Add)`.

## Decision

A single shared composable, `SteppedSlider` (`ui/components/SteppedSlider.kt`), wraps a Material 3
`Slider` in a `Row` with a leading "decrease" and trailing "increase" `IconButton`
(`Icons.Filled.Remove`/`Icons.Filled.Add`). It replaces every raw `Slider(` in the app across all six
integer interval controls:

- Add/Edit Plant: watering (1–60 d), fertilizing (1–180 d), repotting (3–36 mo).
- Plant Detail inline cards (`InlineIntervalSetting`, product ADR-0023): watering (1–60 d), fertilizing
  (1–180 d).
- `DormancyWindowSetting`'s dormant watering cadence (1–12 wk, product ADR-0046/ADR-0047).

The component is purely controlled: `value` always reflects what is displayed, and every change — drag
or button tap — reports through `onValueChange`, already coerced into `range`. `steps` is always
`range.last - range.first - 1`, computed once inside the component so callers can no longer disagree
with each other on the formula. Both buttons are disabled at their respective range bound and a tap can
never produce an out-of-range value.

**Persistence stays per-surface, unchanged from before this issue:**

- **Add/Edit Plant** — the VM field updates on every change (drag or tap); there is no separate commit
  step, since the screen persists everything together on Save. `onValueChangeFinished` is omitted.
- **Plant Detail inline cards and the dormancy cadence control** — a drag updates local UI state only
  and commits on release, exactly as the slider did before; a −/+ tap commits immediately, since a
  discrete tap is already a deliberate, complete change and there is no "release" event to wait for.
  Both flow through the same `onValueChangeFinished` hook: the component invokes it on slider release
  and, additionally, right after every button tap.

A light haptic tick (`HapticFeedbackType.SegmentTick`) fires when a *drag* moves the rounded value to a
new integer step, giving tactile feedback on a slider with no visible tick marks. It does not fire on a
button tap — `IconButton` already gives its own touch feedback, and a discrete, deliberate tap does not
need a second confirmation the way a continuous drag does.

Accessibility: both buttons take a caller-supplied, setting-specific content description (e.g.
"Decrease watering interval" vs. "Decrease dormant watering cadence" — never a generic "Decrease"/
"Increase" shared across settings). The component also accepts an optional `stateDescription`, applied
to the slider node, so a screen reader announces the current human-readable value (e.g. "Every 7 days")
after any change, not just its numeric position.

## Consequences

- No raw `Slider(` remains anywhere in `ui/` outside `SteppedSlider.kt` itself — a future integer
  interval control reaches for this component rather than reintroducing the precision problem.
- Product ADR-0023's description of the Plant Detail inline controls ("Slider" drags persist on
  release) still holds; its Status line is amended to point here since the control itself is a slider
  plus stepper now, not a bare slider — no change to that ADR's actual scheduling-settings-placement
  decision.
- The component adds one new dependency-free Compose composable, not a library — consistent with this
  app's manual-DI, no-new-dependency posture.
- A caller that wants immediate-commit dragging (not just taps) is not directly expressible with this
  component's two persistence modes; none of the six current sliders need that, so it is not exposed as
  a third mode. Revisit if a future slider needs it.
