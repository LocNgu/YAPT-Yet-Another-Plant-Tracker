package com.yapt.planttracker.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yapt.planttracker.settingsDataStore
import com.yapt.planttracker.util.ExistingCameraPhotoCompression

class ExistingCameraPhotoCompressionWorker(
    context: Context,
    workerParameters: WorkerParameters
) : CoroutineWorker(context, workerParameters) {

    override suspend fun doWork(): Result = runCatching {
        ExistingCameraPhotoCompression.runIfNeeded(applicationContext, applicationContext.settingsDataStore)
        Result.success()
    }.getOrElse { Result.retry() }

    companion object {
        private const val WORK_NAME = "yapt_existing_camera_photo_compression"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<ExistingCameraPhotoCompressionWorker>().build()
            )
        }
    }
}
