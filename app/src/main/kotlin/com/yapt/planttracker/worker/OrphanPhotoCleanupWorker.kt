package com.yapt.planttracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.util.OrphanPhotoSweeper
import java.util.concurrent.TimeUnit

/** Runs [OrphanPhotoSweeper.sweep] in the background — see that object's KDoc (#736/#559). */
class OrphanPhotoCleanupWorker(
    context: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result = runCatching {
        val app = applicationContext as YaptApplication
        OrphanPhotoSweeper.sweep(
            database = app.database,
            packageName = applicationContext.packageName,
            filesDir = applicationContext.filesDir
        )
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        private const val PERIODIC_WORK_NAME = "yapt_orphan_photo_cleanup_periodic"
        private const val ONE_SHOT_WORK_NAME = "yapt_orphan_photo_cleanup_one_shot"
        private const val PERIODIC_INTERVAL_HOURS = 24L

        /** Registered once (`KEEP`) next to [ExistingCameraPhotoCompressionWorker] in `MainActivity`. */
        fun schedulePeriodic(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<OrphanPhotoCleanupWorker>(PERIODIC_INTERVAL_HOURS, TimeUnit.HOURS).build()
            )
        }

        /**
         * Fired after a photo-reference-removing write (repository `onPhotoReferencesRemoved`
         * callbacks) or a successful `.yapt` restore. `REPLACE`, not `KEEP`: under `KEEP` a request made
         * while a sweep is already *running* (past its referenced-set read) was dropped, so that
         * write's orphan waited for the next periodic run. `REPLACE` guarantees one sweep starts after
         * the latest trigger. Cancelling a running sweep is harmless — its delete loop has no suspension
         * point, so cancellation lands before the transaction or after it, and every file it already
         * deleted was unreferenced under the transaction's lock.
         */
        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                ONE_SHOT_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<OrphanPhotoCleanupWorker>().build()
            )
        }
    }
}
