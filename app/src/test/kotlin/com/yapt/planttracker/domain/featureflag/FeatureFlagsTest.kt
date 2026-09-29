package com.yapt.planttracker.domain.featureflag

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import com.yapt.planttracker.util.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureFlagsTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var dataStoreFile: File
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var featureFlags: FeatureFlags

    private val flagOffByDefault = FeatureFlag(
        key = "flag_a",
        titleRes = 1,
        descriptionRes = 2,
        default = false
    )
    private val flagOnByDefault = FeatureFlag(
        key = "flag_b",
        titleRes = 3,
        descriptionRes = 4,
        default = true
    )

    @Before
    fun setup() {
        dataStoreFile = File.createTempFile("feature_flags_test_", ".preferences_pb")
        dataStoreFile.deleteOnExit()
        dataStore = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.Unconfined),
            produceFile = { dataStoreFile }
        )
        featureFlags = FeatureFlags(dataStore, flags = listOf(flagOffByDefault, flagOnByDefault))
    }

    @Test
    fun `isEnabled emits the flag's registry default when no value is persisted`() = runTest {
        assertEquals(false, featureFlags.isEnabled(flagOffByDefault).first())
        assertEquals(true, featureFlags.isEnabled(flagOnByDefault).first())
    }

    @Test
    fun `setEnabled persists the flag's value independently of other flags`() = runTest {
        featureFlags.setEnabled(flagOffByDefault, true)

        assertEquals(true, featureFlags.isEnabled(flagOffByDefault).first())
        assertEquals(true, featureFlags.isEnabled(flagOnByDefault).first())
    }

    @Test
    fun `resetAll returns every flag to its registry default`() = runTest {
        featureFlags.setEnabled(flagOffByDefault, true)
        featureFlags.setEnabled(flagOnByDefault, false)

        featureFlags.resetAll()

        assertEquals(false, featureFlags.isEnabled(flagOffByDefault).first())
        assertEquals(true, featureFlags.isEnabled(flagOnByDefault).first())
    }

    @Test
    fun `constructor throws on duplicate flag keys`() {
        val duplicateKeyFlag = FeatureFlag(
            key = flagOffByDefault.key,
            titleRes = 5,
            descriptionRes = 6,
            default = true
        )

        assertThrows(IllegalArgumentException::class.java) {
            FeatureFlags(dataStore, flags = listOf(flagOffByDefault, duplicateKeyFlag))
        }
    }

    @Test
    fun `resetAll only resets the flags it was constructed with`() = runTest {
        featureFlags.setEnabled(flagOffByDefault, true)
        featureFlags.setEnabled(flagOnByDefault, false)

        val partialFeatureFlags = FeatureFlags(dataStore, flags = listOf(flagOffByDefault))
        partialFeatureFlags.resetAll()

        assertEquals(false, featureFlags.isEnabled(flagOffByDefault).first())
        assertEquals(false, featureFlags.isEnabled(flagOnByDefault).first())
    }
}
