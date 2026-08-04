package com.yapt.planttracker.domain.featureflag

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Observable, DataStore-backed storage for feature flag values. A [YaptApplication] lazy
 * singleton, mirroring the repositories (technical ADR-0001, manual DI — no Hilt). Each flag's
 * value lives under its own dynamically-derived key so flags never need a schema/migration to
 * add or remove. Deliberately excluded from backup/restore (product ADR-0022) — flags are
 * device-local, transient experiment state.
 *
 * @param flags the flag list this instance manages, injectable so a Compose test can supply a
 *   test-only registry entry (see #521 AC10/AC33) — production code takes the default,
 *   [FeatureFlagRegistry.all], which ships empty.
 */
class FeatureFlags(
    private val dataStore: DataStore<Preferences>,
    val flags: List<FeatureFlag> = FeatureFlagRegistry.all
) {

    init {
        val duplicateKeys = flags.groupBy { it.key }.filterValues { it.size > 1 }.keys
        require(duplicateKeys.isEmpty()) {
            "Duplicate FeatureFlag key(s) found: $duplicateKeys — each flag must have a unique " +
                "key, since two flags sharing a key would collide on the same DataStore boolean."
        }
    }

    fun isEnabled(flag: FeatureFlag): Flow<Boolean> =
        dataStore.data.map { prefs -> prefs[preferenceKeyFor(flag)] ?: flag.default }

    suspend fun setEnabled(flag: FeatureFlag, enabled: Boolean) {
        dataStore.edit { prefs -> prefs[preferenceKeyFor(flag)] = enabled }
    }

    /** Resets every flag in [flags] back to its registry default. See product ADR-0022. */
    suspend fun resetAll() {
        dataStore.edit { prefs ->
            for (flag in flags) {
                prefs[preferenceKeyFor(flag)] = flag.default
            }
        }
    }

    companion object {
        private const val KEY_PREFIX = "feature_flag_"

        fun preferenceKeyFor(flag: FeatureFlag): Preferences.Key<Boolean> =
            booleanPreferencesKey(KEY_PREFIX + flag.key)
    }
}
