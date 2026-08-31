package com.yapt.planttracker.data.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupPlant(
    val id: Long,
    val name: String,
    val species: String? = null,
    val room: String? = null,
    val coverPhotoUri: String? = null,
    val notes: String? = null,
    val wateringIntervalDays: Int? = null,
    val fertilizingIntervalDays: Int? = null,
    val repottingIntervalDays: Int? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val wateringDueDateOverride: Long? = null,
    val useLiquidFertilizer: Boolean = false,
    val wateringConfidence: Int? = null,
    val wateringBaseIntervalDays: Double? = null,
    val pinIntervalToBase: Boolean = false,
    val wateringResetAt: Long? = null,
    val wateringFreezeUntil: Long? = null
)

@Serializable
data class BackupCareLog(
    val id: Long,
    val plantId: Long,
    val careType: String,
    val loggedAt: Long,
    val notes: String? = null,
    val photoUri: String? = null,
    val amount: String? = null,
    val wateringFeedback: String? = null,
    val fertilizerType: String = "UNSPECIFIED",
    val customReminderId: Long? = null
)

@Serializable
data class BackupCustomReminder(
    val id: Long,
    val plantId: Long,
    val name: String,
    val intervalDays: Int,
    val lastDoneAt: Long? = null,
    val createdAt: Long
)

@Serializable
data class BackupPlantIssue(
    val id: Long,
    val plantId: Long,
    val name: String,
    val startedAt: Long,
    val resolvedAt: Long? = null,
    val resolutionNote: String? = null,
    val linkedReminderId: Long? = null
)

@Serializable
data class BackupWateringAdjustment(
    val id: Long,
    val plantId: Long,
    val triggeredAt: Long,
    val trigger: String,
    val beforeIntervalDays: Int,
    val afterIntervalDays: Int
)

@Serializable
data class BackupSettings(
    val notificationsEnabled: Boolean,
    val reminderHour: Int,
    val reminderMinute: Int,
    val keepScreenOn: Boolean = false,
    val combineNotifications: Boolean = false,
    val photoReminderEnabled: Boolean = false,
    val themeMode: String = "SYSTEM",
    val fertilizingNotificationsEnabled: Boolean = true,
    val askBeforeChangingIntervals: Boolean = true
)

@Serializable
data class BackupPlantPhoto(
    val id: Long,
    val plantId: Long,
    val uri: String? = null,
    val capturedAt: Long
)

@Serializable
data class BackupRoot(
    val schemaVersion: Int,
    val exportedAt: Long,
    val appVersion: String,
    val plants: List<BackupPlant>,
    val careLogs: List<BackupCareLog>,
    val settings: BackupSettings,
    val plantPhotos: List<BackupPlantPhoto> = emptyList(),
    val customReminders: List<BackupCustomReminder> = emptyList(),
    val plantIssues: List<BackupPlantIssue> = emptyList(),
    val wateringAdjustments: List<BackupWateringAdjustment> = emptyList()
)
