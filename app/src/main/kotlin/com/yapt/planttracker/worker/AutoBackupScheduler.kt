package com.yapt.planttracker.worker

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.yapt.planttracker.domain.autobackup.AutoBackupFrequency
import java.util.concurrent.TimeUnit

object AutoBackupScheduler {

    const val WORK_NAME = "yapt_auto_backup"

    /** `UPDATE` so a frequency change re-times the one unique job instead of stacking a second. */
    fun schedule(context: Context, frequency: AutoBackupFrequency) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            buildRequest(frequency)
        )
    }

    /** Battery and storage not low; deliberately no network constraint (offline feature). */
    internal fun buildRequest(frequency: AutoBackupFrequency) =
        PeriodicWorkRequestBuilder<AutoBackupWorker>(frequency.days, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build()
            )
            .build()

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }
}
