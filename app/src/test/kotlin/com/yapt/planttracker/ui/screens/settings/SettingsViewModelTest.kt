package com.yapt.planttracker.ui.screens.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.cash.turbine.test
import com.yapt.planttracker.R
import com.yapt.planttracker.data.backup.BackupManagerInterface
import com.yapt.planttracker.data.backup.BackupResult
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlag
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.ui.theme.ThemeMode
import com.yapt.planttracker.util.MainDispatcherRule
import com.yapt.planttracker.worker.ReminderScheduler
import com.yapt.planttracker.writeDefaultReminderTimeIfAbsent
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val mockPrefs: Preferences = mockk()
    private val mockDataStore: DataStore<Preferences> = mockk()
    private val mockContext: Context = mockk(relaxed = true)
    private val mockDatabase: PlantDatabase = mockk(relaxed = true)
    private val mockPlantRepository: PlantRepository = mockk(relaxed = true)
    private val mockBackupManager: BackupManagerInterface = mockk()

    private lateinit var vm: SettingsViewModel

    @Before
    fun setup() {
        every { mockDataStore.data } returns flowOf(mockPrefs)
        every { mockPrefs[SettingsKeys.KEEP_SCREEN_ON] } returns null
        every { mockPrefs[SettingsKeys.COMBINE_NOTIFICATIONS] } returns null
        every { mockPrefs[SettingsKeys.FERTILIZING_NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.DEVELOPER_MODE_ENABLED] } returns null
        every { mockPlantRepository.getArchivedCount() } returns flowOf(0)
    }

    /**
     * A ViewModel with an explicitly **empty** flag list. Pinned here rather than relying on the
     * default [FeatureFlagRegistry], which ships real flags now (#436) — the "no flags" tests below
     * assert empty-registry behaviour and must not change meaning as flags come and go.
     */
    private fun buildVm() = SettingsViewModel(
        dataStore = mockDataStore,
        context = mockContext,
        database = mockDatabase,
        plantRepository = mockPlantRepository,
        featureFlags = FeatureFlags(mockDataStore, flags = emptyList()),
        backupManager = mockBackupManager
    )

    private val testFlag = FeatureFlag(key = "test_flag", titleRes = 1, descriptionRes = 2, default = true)

    private fun buildVmWithFlags() = SettingsViewModel(
        dataStore = mockDataStore,
        context = mockContext,
        database = mockDatabase,
        plantRepository = mockPlantRepository,
        featureFlags = FeatureFlags(mockDataStore, flags = listOf(testFlag)),
        backupManager = mockBackupManager
    )

    @Test
    fun `notificationsEnabled emits false when DataStore returns false`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns false
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        vm = buildVm()

        vm.notificationsEnabled.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reminderHour emits stored value when DataStore returns 21`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns 21
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        vm = buildVm()

        vm.reminderHour.test {
            assertEquals(21, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `reminderMinute emits stored value when DataStore returns 30`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns 30
        vm = buildVm()

        vm.reminderMinute.test {
            assertEquals(30, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `keepScreenOn emits true when DataStore returns true`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        every { mockPrefs[SettingsKeys.KEEP_SCREEN_ON] } returns true
        vm = buildVm()

        vm.keepScreenOn.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `themeMode defaults to SYSTEM when key is absent`() = runTest {
        every { mockPrefs[SettingsKeys.THEME_MODE] } returns null
        vm = buildVm()

        vm.themeMode.test {
            assertEquals(ThemeMode.SYSTEM, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `themeMode emits DARK when DataStore returns DARK`() = runTest {
        every { mockPrefs[SettingsKeys.THEME_MODE] } returns "DARK"
        vm = buildVm()

        vm.themeMode.test {
            assertEquals(ThemeMode.DARK, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `themeMode falls back to SYSTEM on an unrecognised stored value`() = runTest {
        every { mockPrefs[SettingsKeys.THEME_MODE] } returns "PURPLE"
        vm = buildVm()

        vm.themeMode.test {
            assertEquals(ThemeMode.SYSTEM, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setThemeMode persists the value to DataStore`() = runTest {
        coEvery { mockDataStore.updateData(any()) } returns mockPrefs
        vm = buildVm()

        vm.setThemeMode(ThemeMode.DARK)

        coVerify { mockDataStore.updateData(any()) }
    }

    @Test
    fun `combineNotifications defaults to false when DataStore key is absent`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        vm = buildVm()

        vm.combineNotifications.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `combineNotifications emits true when DataStore returns true`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        every { mockPrefs[SettingsKeys.COMBINE_NOTIFICATIONS] } returns true
        vm = buildVm()

        vm.combineNotifications.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setCombineNotifications persists the value to DataStore`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        coEvery { mockDataStore.updateData(any()) } returns mockPrefs
        vm = buildVm()

        vm.setCombineNotifications(true)
        advanceUntilIdle()

        coVerify { mockDataStore.updateData(any()) }
    }

    @Test
    fun `fertilizingNotificationsEnabled defaults to true when DataStore key is absent`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        vm = buildVm()

        vm.fertilizingNotificationsEnabled.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `fertilizingNotificationsEnabled emits false when DataStore returns false`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        every { mockPrefs[SettingsKeys.FERTILIZING_NOTIFICATIONS_ENABLED] } returns false
        vm = buildVm()

        vm.fertilizingNotificationsEnabled.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFertilizingNotificationsEnabled persists the value to DataStore`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        coEvery { mockDataStore.updateData(any()) } returns mockPrefs
        vm = buildVm()

        vm.setFertilizingNotificationsEnabled(false)
        advanceUntilIdle()

        coVerify { mockDataStore.updateData(any()) }
    }

    @Test
    fun `developerModeEnabled defaults to false when DataStore key is absent`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        vm = buildVm()

        vm.developerModeEnabled.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `developerModeEnabled emits true when DataStore returns true`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        every { mockPrefs[SettingsKeys.DEVELOPER_MODE_ENABLED] } returns true
        vm = buildVm()

        vm.developerModeEnabled.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setDeveloperModeEnabled persists the value to DataStore`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        coEvery { mockDataStore.updateData(any()) } returns mockPrefs
        vm = buildVm()

        vm.setDeveloperModeEnabled(true)
        advanceUntilIdle()

        coVerify { mockDataStore.updateData(any()) }
    }

    @Test
    fun `featureFlagStates is empty when the injected flag list is empty`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        vm = buildVm()

        vm.featureFlagStates.test {
            assertEquals(emptyMap<String, Boolean>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `featureFlagStates emits the flag's registry default when DataStore key is absent`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        every { mockPrefs[FeatureFlags.preferenceKeyFor(testFlag)] } returns null
        vm = buildVmWithFlags()

        vm.featureFlagStates.test {
            assertEquals(mapOf("test_flag" to true), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `featureFlagStates emits the persisted value when present`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        every { mockPrefs[FeatureFlags.preferenceKeyFor(testFlag)] } returns false
        vm = buildVmWithFlags()

        vm.featureFlagStates.test {
            assertEquals(mapOf("test_flag" to false), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `setFlagEnabled persists the value to DataStore`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        every { mockPrefs[FeatureFlags.preferenceKeyFor(testFlag)] } returns null
        coEvery { mockDataStore.updateData(any()) } returns mockPrefs
        vm = buildVmWithFlags()

        vm.setFlagEnabled(testFlag, false)
        advanceUntilIdle()

        coVerify { mockDataStore.updateData(any()) }
    }

    @Test
    fun `setDeveloperModeEnabled(false) does not touch DataStore a second time when there are no flags`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        coEvery { mockDataStore.updateData(any()) } returns mockPrefs
        vm = buildVm()

        vm.setDeveloperModeEnabled(false)
        advanceUntilIdle()

        coVerify(exactly = 1) { mockDataStore.updateData(any()) }
    }

    @Test
    fun `setDeveloperModeEnabled(false) resets every flag to its registry default`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        coEvery { mockDataStore.updateData(any()) } returns mockPrefs
        vm = buildVmWithFlags()

        vm.setDeveloperModeEnabled(false)
        advanceUntilIdle()

        // One write for DEVELOPER_MODE_ENABLED, one for FeatureFlags.resetAll(flags).
        coVerify(exactly = 2) { mockDataStore.updateData(any()) }
    }

    @Test
    fun `setDeveloperModeEnabled(true) does not reset flags`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        coEvery { mockDataStore.updateData(any()) } returns mockPrefs
        vm = buildVmWithFlags()

        vm.setDeveloperModeEnabled(true)
        advanceUntilIdle()

        coVerify(exactly = 1) { mockDataStore.updateData(any()) }
    }

    @Test
    fun `isBackupInProgress starts false`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        vm = buildVm()

        assertFalse(vm.isBackupInProgress.value)
    }

    @Test
    fun `isBackupInProgress is true during exportBackup and false after`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        coEvery { mockBackupManager.exportBackup(any(), any()) } returns BackupResult.ExportSuccess(1, 2)
        vm = buildVm()

        vm.isBackupInProgress.test {
            assertEquals(false, awaitItem())

            vm.exportBackup(mockk(), includePhotos = false)
            assertEquals(true, awaitItem())
            assertEquals(false, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isBackupInProgress is true during importBackup and false after`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        coEvery { mockBackupManager.importBackup(any()) } returns BackupResult.ImportSuccess(3, 5)
        vm = buildVm()

        vm.isBackupInProgress.test {
            assertEquals(false, awaitItem())

            vm.importBackup(mockk())
            assertEquals(true, awaitItem())
            assertEquals(false, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isBackupInProgress is true during proceedWithFutureSchemaImport and false after`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        vm = buildVm()

        vm.isBackupInProgress.test {
            assertEquals(false, awaitItem())

            vm.proceedWithFutureSchemaImport { BackupResult.ImportSuccess(1, 1) }
            assertEquals(true, awaitItem())
            assertEquals(false, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isBackupInProgress remains true during FutureSchemaWarning and resets to false after dismissFutureSchemaImport`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        coEvery { mockBackupManager.importBackup(any()) } returns BackupResult.FutureSchemaWarning(
            schemaVersion = 99,
            onProceed = { BackupResult.ImportSuccess(0, 0) },
            onDismiss = {}
        )
        vm = buildVm()

        vm.isBackupInProgress.test {
            assertEquals(false, awaitItem())

            vm.importBackup(mockk())
            assertEquals(true, awaitItem())
            // isBackupInProgress stays true while FutureSchemaWarning dialog is visible

            vm.dismissFutureSchemaImport {}
            assertEquals(false, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `first-launch default write sets hour=9 and minute=0 when key is absent`() = runTest {
        val tempFile = File.createTempFile("settings_test_defaults_", ".preferences_pb")
        tempFile.deleteOnExit()
        val realDataStore = PreferenceDataStoreFactory.create(
            scope = this,
            produceFile = { tempFile }
        )

        writeDefaultReminderTimeIfAbsent(realDataStore)

        val written = realDataStore.data.first()
        assertEquals(9, written[SettingsKeys.REMINDER_HOUR])
        assertEquals(0, written[SettingsKeys.REMINDER_MINUTE])
    }

    @Test
    fun `first-launch default write is skipped when REMINDER_HOUR is already present`() = runTest {
        val tempFile = File.createTempFile("settings_test_existing_", ".preferences_pb")
        tempFile.deleteOnExit()
        val realDataStore = PreferenceDataStoreFactory.create(
            scope = this,
            produceFile = { tempFile }
        )

        realDataStore.edit { it[SettingsKeys.REMINDER_HOUR] = 8 }

        writeDefaultReminderTimeIfAbsent(realDataStore)

        val written = realDataStore.data.first()
        assertEquals(8, written[SettingsKeys.REMINDER_HOUR])
    }

    @Test
    fun `resetWhatsNewSeenState clears LAST_SEEN_VERSION_CODE and emits a confirmation`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        every { mockContext.getString(R.string.dev_mode_reset_whats_new_snackbar) } returns "What's New seen state reset"
        coEvery { mockDataStore.updateData(any()) } returns mockPrefs
        vm = buildVm()

        vm.debugActionEvent.test {
            vm.resetWhatsNewSeenState()
            assertEquals("What's New seen state reset", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { mockDataStore.updateData(any()) }
    }

    @Test
    fun `runReminderCheckNow enqueues the worker and emits a confirmation when notifications are granted`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        every { mockContext.getString(R.string.dev_mode_run_reminder_check_snackbar) } returns "Reminder check enqueued"
        vm = buildVm()

        mockkStatic(ContextCompat::class)
        mockkObject(ReminderScheduler)
        try {
            every {
                ContextCompat.checkSelfPermission(mockContext, Manifest.permission.POST_NOTIFICATIONS)
            } returns PackageManager.PERMISSION_GRANTED
            every { ReminderScheduler.runNow(mockContext) } just Runs

            vm.debugActionEvent.test {
                vm.runReminderCheckNow()
                assertEquals("Reminder check enqueued", awaitItem())
                cancelAndIgnoreRemainingEvents()
            }

            verify { ReminderScheduler.runNow(mockContext) }
        } finally {
            unmockkObject(ReminderScheduler)
            unmockkStatic(ContextCompat::class)
        }
    }

    @Test
    fun `runReminderCheckNow does not enqueue and emits an explanatory message when notifications are denied`() = runTest {
        every { mockPrefs[SettingsKeys.NOTIFICATIONS_ENABLED] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_HOUR] } returns null
        every { mockPrefs[SettingsKeys.REMINDER_MINUTE] } returns null
        every {
            mockContext.getString(R.string.dev_mode_run_reminder_check_denied_snackbar)
        } returns "Notifications are disabled for this app, so no reminder was posted"
        vm = buildVm()

        mockkStatic(ContextCompat::class)
        mockkObject(ReminderScheduler)
        try {
            every {
                ContextCompat.checkSelfPermission(mockContext, Manifest.permission.POST_NOTIFICATIONS)
            } returns PackageManager.PERMISSION_DENIED

            vm.debugActionEvent.test {
                vm.runReminderCheckNow()
                assertEquals(
                    "Notifications are disabled for this app, so no reminder was posted",
                    awaitItem()
                )
                cancelAndIgnoreRemainingEvents()
            }

            verify(exactly = 0) { ReminderScheduler.runNow(any()) }
        } finally {
            unmockkObject(ReminderScheduler)
            unmockkStatic(ContextCompat::class)
        }
    }
}
