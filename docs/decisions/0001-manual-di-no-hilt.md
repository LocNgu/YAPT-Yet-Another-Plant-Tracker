# ADR-0001: Manual dependency injection via Application singletons instead of Hilt

**Status**: accepted

**Date**: 2024-01-01

## Context

Android projects typically use Hilt for dependency injection. Hilt automates ViewModel injection, scoping, and module wiring, but requires annotation processing (kapt/ksp) and a non-trivial amount of boilerplate (`@HiltAndroidApp`, `@AndroidEntryPoint`, `@Module`, `@Provides`, etc.). For a single-developer, single-module app, this overhead adds complexity without proportional benefit.

Alternatives considered:
- **Hilt**: standard, well-documented, but adds annotation processing and ceremony.
- **Koin**: runtime DI, no annotation processing, but adds a dependency and an indirection layer.
- **Manual singletons**: explicit, zero extra dependencies, easy to trace.

## Decision

`YaptApplication` holds lazy properties for `PlantDatabase`, `PlantRepository`, and `CareLogRepository`. `NavGraph` receives the `Application` instance and passes repositories directly into each ViewModel's inner `Factory` class. Every ViewModel has a `Factory` that takes its dependencies as constructor parameters.

See `YaptApplication.kt` and each `ViewModel.Factory` inner class.

## Consequences

- Dependencies are explicit and traceable — no "magic" injection.
- Adding a new dependency to a ViewModel requires updating its `Factory` and the call site in `NavGraph`. This is acceptable for a small, single-module app.
- If the app grows to multiple modules or many ViewModels sharing the same graph of dependencies, migrating to Hilt becomes worthwhile. The existing pattern maps cleanly onto Hilt's `@Provides` / `@Binds` model.
- Test code must construct dependencies manually or mock them at the `Factory` call site, which is straightforward with MockK.
