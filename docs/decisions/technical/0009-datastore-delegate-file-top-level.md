# ADR-0009: DataStore delegate declared at file top-level, not inside Application class

**Status**: accepted

**Date**: 2024-01-01

## Context

The AndroidX DataStore API uses a Kotlin property delegate to create a singleton `DataStore` instance tied to a `Context`:

```kotlin
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")
```

The natural instinct is to declare this inside `YaptApplication` as a class property:

```kotlin
class YaptApplication : Application() {
    val settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings") // WRONG
}
```

This compiles but causes a runtime crash or creates multiple `DataStore` instances. The AndroidX DataStore documentation explicitly requires this delegate to be declared at **file top-level** (outside any class) because `preferencesDataStore` uses the delegate's `thisRef` to identify the owning class, and the singleton guarantee relies on it being a top-level extension property on `Context`.

## Decision

`val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")` is declared at the top level of `YaptApplication.kt`, outside the `YaptApplication` class body.

Any component that needs DataStore accesses it via `context.settingsDataStore`.

See `YaptApplication.kt`, line 13.

## Consequences

- Only one `DataStore` instance is created for the lifetime of the application, as required.
- The extension function is accessible from any code with a `Context` reference.
- This is an API constraint, not a design choice — moving the declaration inside a class will appear to work in some cases but is unsupported and will cause issues. Do not refactor this without verifying against the current AndroidX DataStore documentation.
