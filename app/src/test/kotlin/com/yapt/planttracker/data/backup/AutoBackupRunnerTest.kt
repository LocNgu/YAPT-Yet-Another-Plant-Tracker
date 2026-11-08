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
import com.yapt.planttracker.domain.autobackup.AutoBackupFiles
import kotlinx.coroutines.CancellationException
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
    var createFails: Boolean = false,
    var renameFails: Boolean = false,
    var undeletable: Set<String> = emptySet(),
    var listThrowsAfterDelete: Boolean = false
) : BackupFolder {
    val files = names.toMutableList()
    val deleted = mutableListOf<String>()

    private fun entry(name: String) = BackupFolderEntry(name, Uri.parse("content://fake/$name"))

    override fun isAccessible() = accessible
    override fun list(): List<BackupFolderEntry> {
        if (!accessible || (listThrowsAfterDelete && deleted.isNotEmpty())) throw BackupFolderUnavailableException()
        return files.map(::entry)
    }
    override fun create(name: String): Uri? {
        if (createFails) return null
        files += name
        return entry(name).uri
    }
    override fun delete(entry: BackupFolderEntry): Boolean {
        if (entry.name in undeletable) return false
        files.remove(entry.name)
        deleted += entry.name
        return true
    }
    override fun rename(uri: Uri, newName: String): String? {
        val old = uri.lastPathSegment
        if (renameFails || old == null || !files.remove(old)) return null
        val result = if (newName in files) newName.replace(".yapt", " (1).yapt") else newName
        files += result
        return result
    }
}

private class FakeBackupManager(var result: BackupResult) : BackupManagerInterface {
    var exports = 0
    var folder: FakeFolder? = null
    var filesAtExport: List<String> = emptyList()
    var throwOnExport: Throwable? = null
    override suspend fun exportBackup(
        destinationUri: Uri,
        includePhotos: Boolean,
        optimizePhotos: Boolean
    ): BackupResult {
        exports++
        filesAtExport = folder?.files?.toList().orEmpty()
        throwOnExport?.let { throw it }
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

    private val tempName get() = AutoBackupFiles.tempFileName(today, 1234L)

    @Test
    fun `same-day file is replaced, never duplicated, and survives until the export is done`() = runBlocking {
        val folder = FakeFolder(names = listOf(todayName))
        val manager = FakeBackupManager(success())
        assertEquals(AutoBackupOutcome.Succeeded(0), runner(folder, manager).run())
        assertEquals(listOf(todayName), folder.files)
        assertTrue(todayName in manager.filesAtExport)
        assertTrue(tempName in manager.filesAtExport)
    }

    @Test
    fun `old same-day file survives an error result and a thrown exception, temp removed`() = runBlocking {
        val folder = FakeFolder(names = listOf(todayName))
        val manager = FakeBackupManager(BackupResult.Error("boom"))
        runner(folder, manager).run()
        assertEquals(listOf(todayName), folder.files)

        manager.throwOnExport = IllegalStateException("kaboom")
        val outcome = runner(folder, manager).run() as AutoBackupOutcome.Failed
        assertEquals(AutoBackupFailureKind.EXPORT, outcome.kind)
        assertEquals(listOf(todayName), folder.files)
    }

    @Test
    fun `rename failure after the old file was removed counts a failure and keeps the temp`() = runBlocking {
        val folder = FakeFolder(names = listOf(todayName), renameFails = true)
        val outcome = runner(folder, FakeBackupManager(success())).run() as AutoBackupOutcome.Failed
        assertEquals(AutoBackupFailureKind.STORAGE, outcome.kind)
        assertEquals(listOf(tempName), folder.files)
        assertEquals(1, dataStore.data.first()[SettingsKeys.AUTO_BACKUP_FAILURE_COUNT])
    }

    @Test
    fun `undeletable old file fails the run, deletes the temp and keeps the old file`() = runBlocking {
        val folder = FakeFolder(names = listOf(todayName), undeletable = setOf(todayName))
        val outcome = runner(folder, FakeBackupManager(success())).run() as AutoBackupOutcome.Failed
        assertEquals(AutoBackupFailureKind.STORAGE, outcome.kind)
        assertEquals(listOf(todayName), folder.files)
    }

    @Test
    fun `a re-list that throws after the old file was deleted keeps the temp`() = runBlocking {
        val folder = FakeFolder(names = listOf(todayName), listThrowsAfterDelete = true)
        val outcome = runner(folder, FakeBackupManager(success())).run() as AutoBackupOutcome.Failed
        assertEquals(AutoBackupFailureKind.STORAGE, outcome.kind)
        assertEquals(listOf(tempName), folder.files)
    }

    @Test
    fun `stale temp files are not retention, untouched by a failed run, swept by a successful one`() = runBlocking {
        val stale = "yapt-backup-2026-11-01.yapt.99.partial"
        val folder = FakeFolder(
            names = listOf(
                stale,
                "yapt-backup-2026-10-01.yapt",
                "yapt-backup-2026-10-02.yapt",
                "yapt-backup-2026-10-03.yapt"
            )
        )
        val manager = FakeBackupManager(BackupResult.Error("boom"))
        runner(folder, manager).run()
        assertTrue(stale in folder.files)

        manager.result = success()
        runner(folder, manager).run()
        assertFalse(stale in folder.files)
        assertEquals(
            listOf("yapt-backup-2026-10-02.yapt", "yapt-backup-2026-10-03.yapt", todayName).sorted(),
            folder.files.sorted()
        )
        assertFalse("yapt-backup-2026-10-02.yapt" in folder.deleted)
    }

    @Test
    fun `cancellation leaves the temp and the old file`() = runBlocking {
        val folder = FakeFolder(names = listOf(todayName))
        val manager = FakeBackupManager(success())
        manager.throwOnExport = CancellationException("stopped")
        val cancelled = runCatching { runner(folder, manager).run() }.exceptionOrNull() is CancellationException
        assertTrue(cancelled)
        assertEquals(listOf(todayName, tempName), folder.files)
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

    @Test
    fun `fingerprint failure fails the run as an export failure instead of skipping`() = runBlocking {
        val manager = FakeBackupManager(success())
        val failing = AutoBackupRunner(
            dataStore = dataStore,
            backupManager = manager,
            folderFor = { FakeFolder() },
            fingerprint = { _, _ -> error("db unreadable") },
            today = { today }
        )

        val first = failing.run() as AutoBackupOutcome.Failed
        assertEquals(AutoBackupFailureKind.EXPORT, first.kind)
        assertFalse(first.notify)
        assertTrue((failing.run() as AutoBackupOutcome.Failed).notify)
        assertEquals(0, manager.exports)
        assertEquals(2, dataStore.data.first()[SettingsKeys.AUTO_BACKUP_FAILURE_COUNT])
    }

    @Test
    fun `pruning never deletes the file just written even when older-named files are newer`() = runBlocking {
        val folder = FakeFolder(
            names = listOf("yapt-backup-2027-01-01.yapt", "yapt-backup-2027-01-02.yapt", "yapt-backup-2027-01-03.yapt")
        )
        runner(folder, FakeBackupManager(success())).run()
        assertTrue(todayName in folder.files)
        assertFalse(todayName in folder.deleted)
    }
}
