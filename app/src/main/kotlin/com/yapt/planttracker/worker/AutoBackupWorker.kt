package com.yapt.planttracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.data.backup.autobackup.AutoBackupFingerprint
import com.yapt.planttracker.data.backup.autobackup.AutoBackupOutcome
import com.yapt.planttracker.data.backup.autobackup.AutoBackupRunner
import com.yapt.planttracker.data.backup.autobackup.SafBackupFolder
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.notification.AutoBackupNotifier
import com.yapt.planttracker.settingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Periodic automatic `.yapt` export into the user's folder (#1001); see [AutoBackupRunner]. */
class AutoBackupWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val app = context.applicationContext as YaptApplication
        val runner = AutoBackupRunner(
            dataStore = context.settingsDataStore,
            backupManager = app.backupManager,
            folderFor = { SafBackupFolder(context, it) },
            fingerprint = { includePhotos, optimizePhotos ->
                AutoBackupFingerprint.compute(app.database, context.settingsDataStore, includePhotos, optimizePhotos)
            },
            beforeExport = { setForeground(AutoBackupNotifier.progressForegroundInfo(context)) }
        )
        when (val outcome = withContext(Dispatchers.IO) { runner.run() }) {
            is AutoBackupOutcome.Succeeded -> AutoBackupNotifier.clear(context)
            is AutoBackupOutcome.Failed -> if (outcome.notify && notificationsEnabled()) {
                AutoBackupNotifier.postFailure(context, outcome.kind)
            }
            AutoBackupOutcome.Disabled, AutoBackupOutcome.Unchanged -> Unit
        }
        return Result.success()
    }

    private suspend fun notificationsEnabled(): Boolean =
        context.settingsDataStore.data.first()[SettingsKeys.NOTIFICATIONS_ENABLED] != false
}
