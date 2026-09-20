package com.yapt.planttracker

/**
 * The [YaptApplication] every Robolectric unit test runs against, registered module-wide in
 * `app/src/test/resources/robolectric.properties` (#757).
 *
 * Identical to the production application except that [launchAppStartWork] does nothing. Robolectric
 * builds a fresh application per test method, but
 * [com.yapt.planttracker.data.db.PlantDatabase.getInstance] and the [settingsDataStore] delegate are
 * process-wide singletons shared by every test in the JVM fork, so production's fire-and-forget
 * `Dispatchers.IO` launch ran concurrently with whatever test was executing and wrote to the same
 * database. When [com.yapt.planttracker.domain.usecase.SeasonalGraduationFixup]'s plant snapshot
 * happened to land after a test had inserted its fixture, the fixup recomputed that fixture's
 * `wateringBaseIntervalDays` (7.0 → 7.72 at amplitude 0.35) — the one column
 * `SkipWateringReceiverTest`'s product-ADR-0007 invariant guard asserts is never written. The window
 * opened at most once per fork (the fixup marks itself done after its first non-empty pass), which is
 * why the failure never reproduced on a re-run or in class isolation.
 *
 * Nothing is lost by skipping it here: no unit test exercises app start itself, and both halves of
 * that work have direct coverage — `SeasonalGraduationFixupTest` for the backfill, `SettingsViewModelTest`
 * for [writeDefaultReminderTimeIfAbsent]. A test that does want app-start behaviour should drive it
 * explicitly rather than race it.
 */
class TestYaptApplication : YaptApplication() {
    override fun launchAppStartWork() = Unit
}
