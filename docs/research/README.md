# Research notes

Background investigations that informed a decision but are not themselves decisions.
Accepted decisions live in [`docs/decisions/`](../decisions/) as ADRs; these notes record the
exploration behind them — the options considered, why the rejected ones were rejected, and the
numbers the choice rested on.

Notes are written as standalone HTML (open the file directly in a browser — no build step).
GitHub shows HTML as source rather than rendering it, so each note is listed here with a summary.

| Note | Subject |
|---|---|
| [`watering-schedule-algorithms.html`](watering-schedule-algorithms.html) | How to derive an adaptive watering schedule: how gardeners actually decide, the candidate algorithm families, and a comparison against the five storage-shaped approaches proposed in #285. Led to closing #285 and re-scoping it into #568–#572, and to technical ADR-0021 and product ADR-0025. |
