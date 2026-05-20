# ADR-0008: Custom ProGuard rules to preserve Room DAOs and WorkManager workers

**Status**: accepted

**Date**: 2024-01-01

## Context

Release builds have R8 minification (`isMinifyEnabled = true`) and resource shrinking (`isShrinkResources = true`) enabled. R8 removes and renames classes and methods that it believes are unreferenced. Two framework components are instantiated by name via reflection, making them invisible to static analysis:

- **Room** generates a concrete `RoomDatabase` subclass at compile time. Its DAO implementations are accessed via `abstract` methods on the database class. R8 sees no direct call to these methods from user code and may strip or rename them.
- **WorkManager** instantiates `Worker` and `CoroutineWorker` subclasses by their fully-qualified class name, stored as a string in the work request. R8 renaming breaks the lookup.

Without protection, the app compiles and minifies successfully but crashes at runtime when Room or WorkManager attempts to use these classes.

## Decision

`proguard-rules.pro` contains two rules:

```
-keepclassmembers class * extends androidx.room.RoomDatabase {
    abstract *;
}

-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
```

The first rule preserves abstract DAO accessor methods on all `RoomDatabase` subclasses. The second and third rules prevent R8 from renaming any `Worker` or `CoroutineWorker` subclass.

See `app/proguard-rules.pro`.

## Consequences

- Room and WorkManager work correctly in release/minified builds.
- These rules are intentionally narrow — they protect only what reflection requires, not broad packages.
- If new framework dependencies are added that use reflection for instantiation (e.g., a serialization library, a new WorkManager extension), similar keep rules will be needed. The symptom is always a runtime crash in release builds that doesn't appear in debug builds.
