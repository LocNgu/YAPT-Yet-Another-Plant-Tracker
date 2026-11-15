package com.yapt.planttracker.data.backup.autobackup

import android.net.Uri
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.yapt.planttracker.data.backup.BackupManagerInterface
import com.yapt.planttracker.data.backup.BackupResult
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.domain.autobackup.AutoBackupFailureKind
import com.yapt.planttracker.domain.autobackup.AutoBackupFiles
import com.yapt.planttracker.domain.autobackup.AutoBackupPolicy
import com.yapt.planttracker.domain.autobackup.AutoBackupRetention
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.time.LocalDate

sealed interface AutoBackupOutcome {
    data object Disabled : AutoBackupOutcome
    data object Unchanged : AutoBackupOutcome
    data class Succeeded(val skippedPhotos: Int) : AutoBackupOutcome
    data class Failed(val kind: AutoBackupFailureKind, val notify: Boolean) : AutoBackupOutcome
}

/**
 * One scheduled auto-backup run (#1001, technical ADR-0043). Pure orchestration over injected
 * collaborators so the failure state machine, skip-unchanged and pruning are unit-testable without
 * WorkManager. The export itself is the unchanged [BackupManagerInterface.exportBackup].
 */
@Suppress("LongParameterList")
class AutoBackupRunner(
    private val dataStore: DataStore<Preferences>,
    private val backupManager: BackupManagerInterface,
    private val folderFor: (Uri) -> BackupFolder,
    private val fingerprint: suspend (includePhotos: Boolean, optimizePhotos: Boolean) -> String,
    private val now: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
    private val beforeExport: suspend () -> Unit = {}
) {

    @Suppress("ReturnCount", "SwallowedException", "TooGenericExceptionCaught", "ThrowsCount")
    suspend fun run(): AutoBackupOutcome {
        val prefs = dataStore.data.first()
        val folderUri = prefs[SettingsKeys.AUTO_BACKUP_FOLDER_URI]
        if (prefs[SettingsKeys.AUTO_BACKUP_ENABLED] != true || folderUri == null) return AutoBackupOutcome.Disabled

        val folder = folderFor(folderUri.toUri())
        if (!folder.isAccessible()) return fail(AutoBackupFailureKind.FOLDER_UNAVAILABLE)

        val includePhotos = prefs[SettingsKeys.AUTO_BACKUP_INCLUDE_PHOTOS] ?: true
        val optimizePhotos = prefs[SettingsKeys.AUTO_BACKUP_OPTIMIZE_PHOTOS] ?: false
        val currentFingerprint = try {
            fingerprint(includePhotos, optimizePhotos)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(AutoBackupFailureKind.EXPORT)
        }
        if (currentFingerprint == prefs[SettingsKeys.AUTO_BACKUP_FINGERPRINT]) return AutoBackupOutcome.Unchanged

        // Best effort (e.g. setForeground throws from the background on API 31+); never a failure.
        try {
            beforeExport()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // ignored on purpose: the export continues as a normal worker
        }

        return try {
            export(folder, prefs, includePhotos, optimizePhotos, currentFingerprint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: BackupFolderUnavailableException) {
            fail(AutoBackupFailureKind.FOLDER_UNAVAILABLE)
        } catch (e: Exception) {
            fail(AutoBackupFailureKind.EXPORT)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun export(
        folder: BackupFolder,
        prefs: Preferences,
        includePhotos: Boolean,
        optimizePhotos: Boolean,
        currentFingerprint: String
    ): AutoBackupOutcome {
        val name = AutoBackupFiles.fileName(today())
        val tempName = AutoBackupFiles.tempFileName(today(), now())
        val temp = folder.create(tempName) ?: return fail(AutoBackupFailureKind.STORAGE)

        // Until the swap the folder keeps the previous same-day file; no partial file ever carries the real name.
        val result = discardingOnError(folder, temp) {
            backupManager.exportBackup(temp, includePhotos, optimizePhotos)
        }
        if (result !is BackupResult.ExportSuccess) {
            discard(folder, temp)
            val message = (result as? BackupResult.Error)?.message.orEmpty()
            val kind = if (message.contains("ENOSPC") || message.contains("No space", ignoreCase = true)) {
                AutoBackupFailureKind.STORAGE
            } else {
                AutoBackupFailureKind.EXPORT
            }
            return fail(kind)
        }

        discardingOnError(folder, temp) { folder.list().filter { it.name == name }.forEach { folder.delete(it) } }
        // Unknown outcome (the re-list threw): the delete may have succeeded, so the temp must stay.
        val stillThere = existsOrNull(folder, name) ?: return fail(AutoBackupFailureKind.STORAGE)
        if (stillThere) {
            discard(folder, temp)
            return fail(AutoBackupFailureKind.STORAGE)
        }
        // The old file is gone: from here a failure keeps the temp, the only complete copy for today.
        if (folder.rename(temp, name) != name) return fail(AutoBackupFailureKind.STORAGE)

        val keep = AutoBackupRetention.fromStored(prefs[SettingsKeys.AUTO_BACKUP_RETENTION])
        prune(folder, keep, justWritten = name)
        sweepTemps(folder)

        dataStore.edit {
            it[SettingsKeys.AUTO_BACKUP_LAST_SUCCESS_AT] = now()
            it[SettingsKeys.AUTO_BACKUP_LAST_SKIPPED_PHOTOS] = result.skippedPhotoCount
            it[SettingsKeys.AUTO_BACKUP_FINGERPRINT] = currentFingerprint
            it[SettingsKeys.AUTO_BACKUP_FAILURE_COUNT] = 0
            it[SettingsKeys.AUTO_BACKUP_FAILURE_NOTIFIED] = false
            it.remove(SettingsKeys.AUTO_BACKUP_LAST_FAILURE)
        }
        return AutoBackupOutcome.Succeeded(result.skippedPhotoCount)
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> discardingOnError(folder: BackupFolder, temp: Uri, block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        discard(folder, temp)
        throw e
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun existsOrNull(folder: BackupFolder, name: String): Boolean? = try {
        folder.list().any { it.name == name }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun discard(folder: BackupFolder, uri: Uri) {
        runCatching { folder.list().filter { it.uri == uri }.forEach { folder.delete(it) } }
    }

    private fun sweepTemps(folder: BackupFolder) {
        runCatching { folder.list().filter { AutoBackupFiles.isTempName(it.name) }.forEach { folder.delete(it) } }
    }

    private fun prune(folder: BackupFolder, keep: Int, justWritten: String) {
        runCatching {
            val entries = folder.list()
            // A set-back clock can date existing files after today; the new file must never be deleted.
            val doomed = AutoBackupFiles.selectPrunable(entries.map { it.name }, keep).toSet() - justWritten
            entries.filter { it.name in doomed }.forEach { folder.delete(it) }
        }
    }

    private suspend fun fail(kind: AutoBackupFailureKind): AutoBackupOutcome.Failed {
        var notify = false
        dataStore.edit {
            val count = (it[SettingsKeys.AUTO_BACKUP_FAILURE_COUNT] ?: 0) + 1
            notify = AutoBackupPolicy.shouldNotify(
                kind,
                count,
                it[SettingsKeys.AUTO_BACKUP_FAILURE_NOTIFIED] ?: false
            )
            it[SettingsKeys.AUTO_BACKUP_FAILURE_COUNT] = count
            it[SettingsKeys.AUTO_BACKUP_LAST_FAILURE] = kind.name
            if (notify) it[SettingsKeys.AUTO_BACKUP_FAILURE_NOTIFIED] = true
        }
        return AutoBackupOutcome.Failed(kind, notify)
    }
}
