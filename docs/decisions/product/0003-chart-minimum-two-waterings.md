# Product ADR-0003: Watering history chart requires at least two watering logs

**Status**: accepted

**Date**: 2024-01-01

## Context

The watering history chart shows average days between waterings per calendar month. A user with zero or one watering log in the selected time range has no intervals to plot — an interval is the gap between two events. The chart needs a minimum amount of data to be meaningful.

Options considered:
- **Show chart with zero data**: blank chart, no signal to the user about why it's empty.
- **Show chart with one log**: one data point with no interval to compute — the chart would show nothing or a degenerate single point.
- **Show a message instead of the chart**: explain why the chart isn't available yet.

## Decision

The chart is hidden and replaced with an explanatory message when fewer than 2 watering logs exist in the selected time range. The message reads something like "Log at least 2 waterings to see your watering history."

The threshold is 2 because that is the minimum needed to compute a single interval (the gap between log 1 and log 2). With only 1 log, the chart has no data to display.

See `WateringHistoryChart.kt`, the empty state composable (lines 107–113) and the data check (line 285).

## Consequences

- New plants with only one watering recorded will see the message until a second watering is logged.
- The message gives the user clear guidance on what action to take, rather than a blank chart with no explanation.
- This threshold applies per selected time range — if the user switches to "1 month" and has fewer than 2 waterings in that month, the message shows even if the full history has many more. This is correct: the chart shows the selected range, not all-time data.
