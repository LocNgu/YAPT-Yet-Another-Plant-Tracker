package com.yapt.planttracker.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.yapt.planttracker.domain.repotting.RepottingOverviewThreshold
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The Repotting overview's remembered chip (#525, product ADR-0059). A view preference like
 * `SORT_OPTION`, so it is deliberately not part of `.yapt` backups.
 */
class RepottingOverviewPreferences(private val dataStore: DataStore<Preferences>) {

    val threshold: Flow<RepottingOverviewThreshold> = dataStore.data
        .map { RepottingOverviewThreshold.fromStoredName(it[SettingsKeys.REPOTTING_OVERVIEW_THRESHOLD]) }
        .distinctUntilChanged()

    suspend fun setThreshold(threshold: RepottingOverviewThreshold) {
        dataStore.edit { it[SettingsKeys.REPOTTING_OVERVIEW_THRESHOLD] = threshold.name }
    }
}
