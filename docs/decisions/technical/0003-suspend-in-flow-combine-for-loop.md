# ADR-0003: For-loop instead of .map{} for suspend calls inside Flow combine

**Status**: accepted

**Date**: 2024-01-01

## Context

`PlantListViewModel.plantsWithStatus` uses `combine(...)` to merge several flows. Inside the `combine` block, `buildStatus(plant)` must be called for each plant. `buildStatus` is a `suspend` function because it queries the database.

The idiomatic Kotlin approach would be:

```kotlin
val statusList = filtered.map { buildStatus(it) }
```

However, `List.map {}` accepts a non-suspend lambda. Calling a `suspend` function inside it is a compile error. The workaround alternatives are:

- **`for` loop with `mutableListOf`**: straightforward, works in a suspend context.
- **`coroutineScope { filtered.map { async { buildStatus(it) } }.awaitAll() }`**: parallel but more complex and harder to read.
- **Restructure so `buildStatus` is non-suspend**: would require moving the DB queries outside the combine, adding complexity to the flow graph.

## Decision

`buildStatus` is called inside a `for` loop that accumulates results into a `mutableListOf<PlantCareStatus>()`. This is the simplest correct solution.

```kotlin
val statusList = mutableListOf<PlantCareStatus>()
for (plant in filtered) {
    statusList.add(buildStatus(plant))
}
```

See `PlantListViewModel.kt`, the `plantsWithStatus` StateFlow definition.

## Consequences

- Correct: suspend calls work inside the loop.
- Sequential per-plant DB queries are acceptable at current scale. The plant list is small and each query is a single indexed lookup.
- Do not refactor this to `.map {}` without first wrapping it in `coroutineScope { ... }` or switching to a non-suspend `buildStatus`. The compiler will catch the error, but the intent is worth noting explicitly.
