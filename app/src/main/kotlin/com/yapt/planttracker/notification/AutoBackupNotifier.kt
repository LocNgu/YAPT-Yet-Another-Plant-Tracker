package com.yapt.planttracker.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import com.yapt.planttracker.MainActivity
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.autobackup.AutoBackupFailureKind

/**
 * Failure alert for automatic backups (#1001, technical ADR-0043). Its own channel and reserved ID `-3`,
 * which `ReminderWorker`'s daily cleanup skips (technical ADR-0026), so the reminder run never clears it.
 */
object AutoBackupNotifier {

    const val NOTIFICATION_ID = -3
    const val CHANNEL_ID = "auto_backup"
    const val PROGRESS_NOTIFICATION_ID = -4
    const val PROGRESS_CHANNEL_ID = "auto_backup_progress"

    fun postFailure(context: Context, kind: AutoBackupFailureKind) {
        if (!NotificationPermission.isGranted(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.auto_backup_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val body = context.getString(
            if (kind == AutoBackupFailureKind.FOLDER_UNAVAILABLE) {
                R.string.auto_backup_notification_folder_body
            } else {
                R.string.auto_backup_notification_failed_body
            }
        )
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.auto_backup_notification_title))
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()
        )
    }

    /** Silent ongoing notification a long-running export needs to stay alive as a foreground worker. */
    fun progressForegroundInfo(context: Context): ForegroundInfo {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                PROGRESS_CHANNEL_ID,
                context.getString(R.string.auto_backup_progress_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        val notification = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.auto_backup_progress_title))
            .setContentText(context.getString(R.string.auto_backup_progress_body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(PROGRESS_NOTIFICATION_ID, notification)
        }
    }

    fun clear(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
