# Product ADR-0087: Setting help is an (i) icon that opens a rich tooltip

**Status**: accepted

**Date**: 2026-11-18

## Context

Issue #1039: the Add Plant wizard (product ADR-0086) hides rare settings under More options, but a first-time
user still cannot tell what Seasonal strength, Pin, Dormancy window, Repotting reminder or Prefer repotting in
do. Alternatives considered: always-visible helper text (clutters a form deliberately kept light), a bottom
sheet per setting (heavy for one or two sentences and hides the setting being explained), and a separate help
screen (far from the control).

## Decision

- A reusable `InfoTooltip` (`ui/components/`): an (i) `IconButton` (48 dp target, caller-supplied content
  description such as "About seasonal strength") that opens a Material 3 `TooltipBox` with a `RichTooltip` on tap.
  It is persistent: it closes on an outside tap or Back, and TalkBack reads its text.
- It sits beside a setting's label, outside any clickable or toggleable row, and never changes the setting.
- Used on the five settings above in the wizard and in Edit Plant (shared section composables). Shared
  components opt in (`showInfo`), so Plant Detail renders as before.
- Each wizard step also shows an always-visible plain-text intro under its title; this is not a second live region.
- Issue #1038 (help on Plant Detail and Settings) can reuse `InfoTooltip`.

## Consequences

Help is one tap away and costs no layout space. Tooltips are not restored after process death. New help text
goes in `strings.xml` with an "About ..." content description.
