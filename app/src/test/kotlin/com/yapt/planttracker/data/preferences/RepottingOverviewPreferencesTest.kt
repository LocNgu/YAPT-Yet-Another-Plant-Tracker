package com.yapt.planttracker.data.preferences

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import com.yapt.planttracker.domain.repotting.RepottingOverviewThreshold
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RepottingOverviewPreferencesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    private lateinit var prefs: RepottingOverviewPreferences

    @Before
    fun setUp() {
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("settings.preferences_pb") }
        prefs = RepottingOverviewPreferences(dataStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `defaults to two years when unset`() = runBlocking {
        assertEquals(RepottingOverviewThreshold.TWO_YEARS, prefs.threshold.first())
    }

    @Test
    fun `every threshold round-trips through the store`() = runBlocking {
        for (t in RepottingOverviewThreshold.entries) {
            prefs.setThreshold(t)
            assertEquals(t, prefs.threshold.first())
            assertEquals(t.name, dataStore.data.first()[SettingsKeys.REPOTTING_OVERVIEW_THRESHOLD])
        }
    }

    @Test
    fun `unknown stored value falls back to two years`() = runBlocking {
        dataStore.edit { it[SettingsKeys.REPOTTING_OVERVIEW_THRESHOLD] = "FIVE_YEARS" }
        assertEquals(RepottingOverviewThreshold.TWO_YEARS, prefs.threshold.first())
    }

    @Test
    fun `key is distinct from the plant list sort option`() {
        assertFalse(SettingsKeys.REPOTTING_OVERVIEW_THRESHOLD.name == SettingsKeys.SORT_OPTION.name)
    }
}
