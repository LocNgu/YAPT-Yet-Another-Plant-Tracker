package com.yapt.planttracker

/**
 * The [YaptApplication] every Robolectric unit test runs against (#757).
 *
 * Identical to the production application except that [launchAppStartWork] does nothing. Robolectric
 * builds a fresh application per test method, but
 * [com.yapt.planttracker.data.db.PlantDatabase.getInstance] and the [settingsDataStore] delegate are
 * process-wide singletons shared by every test in the JVM fork, so production's fire-and-forget
 * `Dispatchers.IO` launch ran concurrently with whatever test was executing and wrote to the same
 * database. When [com.yapt.planttracker.domain.usecase.SeasonalGraduationFixup]'s plant snapshot
 * happened to land after a test had inserted its fixture, the fixup recomputed that fixture's
 * `wateringBaseIntervalDays` (7.0 → 7.72 at amplitude 0.35) — the one column
 * `SkipWateringReceiverTest`'s product ADR-0007 invariant guard asserts is never written. The window
 * opened at most once per fork (the fixup marks itself done after its first non-empty pass), which is
 * why the failure never reproduced on a re-run or in class isolation.
 *
 * **This class's name is load-bearing.** Robolectric's `AndroidTestEnvironment` resolves `Test` +
 * the manifest application's simple name, in the same package, *before* falling back to the manifest
 * class itself — so `TestYaptApplication` in `com.yapt.planttracker` is selected by that convention
 * alone, with or without `app/src/test/resources/robolectric.properties` (verified in
 * robolectric-4.16.1 bytecode, and by deleting the file and watching the tests still get this class).
 * The properties file is kept as an explicit, greppable registration that keeps working if this class
 * is ever renamed off the convention — not as the mechanism that makes it apply today. Renaming this
 * class therefore silently drops one of the two routes; keep the properties file in step if you do.
 *
 * Nothing is lost by skipping app-start work here: no unit test exercises app start, and both halves
 * have direct coverage — `SeasonalGraduationFixupTest` for the backfill, `SettingsViewModelTest` for
 * [writeDefaultReminderTimeIfAbsent]. A test that does want app-start behaviour should drive it
 * explicitly rather than race it.
 */
class TestYaptApplication : YaptApplication() {
    override fun launchAppStartWork() = Unit
}
