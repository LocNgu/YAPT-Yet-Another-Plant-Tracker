# Product ADR-0088: Plant Detail shows the basics first; rarer settings sit in a collapsed section with a summary

**Status**: accepted

**Date**: 2026-11-24

## Context

The Water tab's interval card carried the interval, the seasonal hint, the confidence row, the seasonal strength / Pin control, the dormancy window and cadence, the seasonal curve chart and the "Why this date?" button. Most plants never change the last four, yet they dominated the card (#1038). The Add Plant wizard already hides the same settings under a collapsed "More options" section (product ADR-0086). The alternative of keeping everything in the card, or removing the settings from Plant Detail, was rejected: owners adjust them here after the plant exists.

## Decision

On Plant Detail, the main card keeps only the basics: the enable switch and interval slider, the seasonal hint, the confidence row and the "Why this date?" button (it explains the date shown, it is not a setting). Seasonal strength / Pin, the dormancy window and cadence, and the seasonal curve move into a **More options** section directly below the card, reusing the wizard's header and Expanded/Collapsed wording. Applied first to the Water tab; Edit Plant, Fertilize and Repot follow as separate PRs (#1042 to #1044).

Differences from product ADR-0086:
- The section **never auto-expands** on a non-default value. Instead the header shows a one-line summary of every non-default value, joined with " · " (for example "Dormant Nov–Feb · Custom strength"), so a configured value is never hidden. With everything at default the header is the title alone. The summary wraps and is never truncated.
- The expanded state is `rememberSaveable` and keyed on the selected tab: it survives rotation, is not stored per plant or in DataStore, and starts collapsed each time the tab opens.
- The header is one merged clickable element with an Expanded/Collapsed `stateDescription`; the summary is part of its announcement.

The seasonal strength and dormancy settings show their `InfoTooltip` (product ADR-0087) here. Visibility rules are unchanged: strength and the curve need the watering schedule on, dormancy shows either way. This is presentation only; no stored value or schedule logic changes.

## Consequences

- A plant with unusual settings shows its summary under the header instead of the controls; changing one takes an extra tap.
- Later surfaces (#1042 to #1044) follow the same basics-first rule and summary header.
- #1006 restyles the smaller card without having to decide where these settings live.
