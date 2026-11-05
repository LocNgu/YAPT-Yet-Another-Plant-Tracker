package com.yapt.planttracker.data.backup

import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.yapt.planttracker.data.backup.autobackup.AutoBackupOutcome
import com.yapt.planttracker.data.backup.autobackup.AutoBackupRunner
import com.yapt.planttracker.data.backup.autobackup.BackupFolder
import com.yapt.planttracker.data.backup.autobackup.BackupFolderEntry
import com.yapt.planttracker.data.backup.autobackup.BackupFolderUnavailableException
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.domain.autobackup.AutoBackupFailureKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

private class FakeFolder(
    var accessible: Boolean = true,
    names: List<String> = emptyList(),
    var createFails: Boolean = false
) : BackupFolder {
    val files = names.toMutableList()
    val deleted = mutableListOf<String>()

    private fun entry(name: String) = BackupFolderEntry(name, Uri.parse("content://fake/$name"))

    override fun isAccessible() = accessible
    override fun list(): List<BackupFolderEntry> {
        if (!accessible) throw BackupFolderUnavailableException()
        return files.map(::entry)
    }
    override fun create(name: String): Uri? {
        if (createFails) return null
        files += name
        return entry(name).uri
    }
    override fun delete(entry: BackupFolderEntry): Boolean {
        files.remove(entry.name)
        deleted += entry.name
        return true
    }
}

private class FakeBackupManager(var result: BackupResult) : BackupManagerInterface {
    var exports = 0
    var folder: FakeFolder? = null
    var filesAtExport: List<String> = emptyList()
    override suspend fun exportBackup(
        destinationUri: Uri,
        includePhotos: Boolean,
        optimizePhotos: Boolean
    ): BackupResult {
        exports++
        filesAtExport = folder?.files?.toList().orEmpty()
        return result
    }
    override suspend fun importBackup(sourceUri: Uri): BackupResult = error("unused")
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutoBackupRunnerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var scope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private var fingerprintValue = "fp1"
    private val today = LocalDate.of(2026, 11, 4)
    private val todayName = "yapt-backup-2026-11-04.yapt"

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("prefs.preferences_pb") }
        runBlocking {
            dataStore.edit {
                it[SettingsKeys.AUTO_BACKUP_ENABLED] = true
                it[SettingsKeys.AUTO_BACKUP_FOLDER_URI] = "content://tree/backups"
                it[SettingsKeys.AUTO_BACKUP_RETENTION] = 3
            }
        }
    }

    @After
    fun tearDown() = scope.cancel()

    private var beforeExport: suspend () -> Unit = {}

    private fun runner(folder: FakeFolder, manager: FakeBackupManager): AutoBackupRunner {
        manager.folder = folder
        return AutoBackupRunner(
            dataStore = dataStore,
            backupManager = manager,
            folderFor = { folder },
            fingerprint = { _, _ -> fingerprintValue },
            now = { 1234L },
            today = { today },
            beforeExport = { beforeExport() }
        )
    }

    private fun success(skipped: Int = 0) = BackupResult.ExportSuccess(1, 1, skipped)

    @Test
    fun `disabled does nothing`() = runBlocking {
        dataStore.edit { it[SettingsKeys.AUTO_BACKUP_ENABLED] = false }
        val manager = FakeBackupManager(success())
        assertEquals(AutoBackupOutcome.Disabled, runner(FakeFolder(), manager).run())
        assertEquals(0, manager.exports)
    }

    @Test
    fun `success exports, prunes to newest N after the write, and records state`() = runBlocking {
        val folder = FakeFolder(
            names = listOf(
                "yapt-backup-2026-10-01.yapt",
                "yapt-backup-2026-10-10.yapt",
                "yapt-backup-2026-10-20.yapt",
                "yapt-backup-2026-11-01.yapt",
                "holiday.jpg",
                "yapt-backup-2026-10-01 (1).yapt"
            )
        )
        val manager = FakeBackupManager(success(skipped = 2))

        assertEquals(AutoBackupOutcome.Succeeded(2), runner(folder, manager).run())

        assertTrue(todayName in folder.files)
        assertEquals(listOf("yapt-backup-2026-10-01.yapt", "yapt-backup-2026-10-10.yapt"), folder.deleted)
        assertTrue("holiday.jpg" in folder.files)
        assertTrue("yapt-backup-2026-10-01 (1).yapt" in folder.files)
        // Nothing was pruned before the export ran.
        assertTrue("yapt-backup-2026-10-01.yapt" in manager.filesAtExport)
        val prefs = dataStore.data.first()
        assertEquals(1234L, prefs[SettingsKeys.AUTO_BACKUP_LAST_SUCCESS_AT])
        assertEquals(2, prefs[SettingsKeys.AUTO_BACKUP_LAST_SKIPPED_PHOTOS])
        assertEquals("fp1", prefs[SettingsKeys.AUTO_BACKUP_FINGERPRINT])
    }

    @Test
    fun `unchanged fingerprint skips the run and keeps the last success`() = runBlocking {
        val folder = FakeFolder()
        val manager = FakeBackupManager(success())
        assertEquals(AutoBackupOutcome.Succeeded(0), runner(folder, manager).run())
        assertEquals(AutoBackupOutcome.Unchanged, runner(folder, manager).run())
        assertEquals(1, manager.exports)
        assertEquals(1234L, dataStore.data.first()[SettingsKeys.AUTO_BACKUP_LAST_SUCCESS_AT])

        fingerprintValue = "fp2"
        assertEquals(AutoBackupOutcome.Succeeded(0), runner(folder, manager).run())
        assertEquals(2, manager.exports)
    }

    @Test
    fun `same-day file is replaced, never duplicated`() = runBlocking {
        val folder = FakeFolder(names = listOf(todayName))
        runner(folder, FakeBackupManager(success())).run()
        assertEquals(listOf(todayName), folder.files)
    }

    @Test
    fun `export error cleans the partial file, never prunes, and notifies on the second failure only`() = runBlocking {
        val old = listOf("yapt-backup-2026-10-01.yapt", "yapt-backup-2026-10-02.yapt", "yapt-backup-2026-10-03.yapt")
        val folder = FakeFolder(names = old)
        val manager = FakeBackupManager(BackupResult.Error("boom"))

        val first = runner(folder, manager).run() as AutoBackupOutcome.Failed
        assertEquals(AutoBackupFailureKind.EXPORT, first.kind)
        assertFalse(first.notify)
        assertEquals(old, folder.files)

        val second = runner(folder, manager).run() as AutoBackupOutcome.Failed
        assertTrue(second.notify)
        val third = runner(folder, manager).run() as AutoBackupOutcome.Failed
        assertFalse(third.notify)
        assertEquals(3, dataStore.data.first()[SettingsKeys.AUTO_BACKUP_FAILURE_COUNT])
        assertEquals(old, folder.files)
    }

    @Test
    fun `success after failures resets the streak`() = runBlocking {
        val folder = FakeFolder()
        val manager = FakeBackupManager(BackupResult.Error("boom"))
        runner(folder, manager).run()
        runner(folder, manager).run()
        manager.result = success()
        runner(folder, manager).run()
        val prefs = dataStore.data.first()
        assertEquals(0, prefs[SettingsKeys.AUTO_BACKUP_FAILURE_COUNT])
        assertEquals(false, prefs[SettingsKeys.AUTO_BACKUP_FAILURE_NOTIFIED])
        assertNull(prefs[SettingsKeys.AUTO_BACKUP_LAST_FAILURE])
    }

    @Test
    fun `lost folder notifies immediately`() = runBlocking {
        val manager = FakeBackupManager(success())
        val outcome = runner(FakeFolder(accessible = false), manager).run() as AutoBackupOutcome.Failed
        assertEquals(AutoBackupFailureKind.FOLDER_UNAVAILABLE, outcome.kind)
        assertTrue(outcome.notify)
        assertEquals(0, manager.exports)
        val again = runner(FakeFolder(accessible = false), manager).run() as AutoBackupOutcome.Failed
        assertFalse(again.notify)
    }

    @Test
    fun `create refusal counts as a storage failure`() = runBlocking {
        val outcome = runner(FakeFolder(createFails = true), FakeBackupManager(success())).run()
        assertEquals(AutoBackupFailureKind.STORAGE, (outcome as AutoBackupOutcome.Failed).kind)
    }

    @Test
    fun `foreground hook runs only for a real export and its failure is tolerated`() = runBlocking {
        var calls = 0
        beforeExport = {
            calls++
            error("ForegroundServiceStartNotAllowed")
        }
        val folder = FakeFolder()
        val manager = FakeBackupManager(success())
        assertEquals(AutoBackupOutcome.Succeeded(0), runner(folder, manager).run())
        assertEquals(1, calls)
        assertEquals(0, dataStore.data.first()[SettingsKeys.AUTO_BACKUP_FAILURE_COUNT])

        assertEquals(AutoBackupOutcome.Unchanged, runner(folder, manager).run())
        assertEquals(1, calls)
    }
}
