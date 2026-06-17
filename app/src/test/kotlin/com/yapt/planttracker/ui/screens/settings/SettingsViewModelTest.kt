package com.yapt.planttracker.ui.screens.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import app.cash.turbine.test
import com.yapt.planttracker.data.backup.BackupManagerInterface
import com.yapt.planttracker.data.backup.BackupResult
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.writeDefaultReminderTimeIfAbsent
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
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
        every { mockPlantRepository.getArchivedCount() } returns flowOf(0)
    }

    private fun buildVm() = SettingsViewModel(
        dataStore = mockDataStore,
        context = mockContext,
        database = mockDatabase,
        plantRepository = mockPlantRepository,
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
}
