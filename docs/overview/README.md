# Overview docs

Standalone reference pages for orienting in the codebase — not decisions (see
[`docs/decisions/`](../decisions/)) and not research (see [`docs/research/`](../research/)), but a map of
what exists and how it fits together, kept current as the app grows.

Pages are written as standalone HTML (open the file directly in a browser — no build step). GitHub shows
HTML as source rather than rendering it, so each page is listed here with a summary.

| Page | Subject |
|---|---|
| [`mental-map.html`](mental-map.html) | Architecture overview: the four layers (screens → ViewModels → domain logic → Room/DataStore), the screen inventory and nav graph, the six Room entities, cross-cutting systems (notifications, backup, feature flags, theme), and a "where do I…" file lookup. |
| [`watering-math.html`](watering-math.html) | Reference for the shipped adaptive-watering algorithm: the confidence-weighted update rule, the seasonal curve, cold-start bootstrapping, lifecycle resets, and a fully worked numeric example. |
