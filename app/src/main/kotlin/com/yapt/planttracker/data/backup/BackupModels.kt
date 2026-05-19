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
    val createdAt: Long,
    val updatedAt: Long
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
    val wateringFeedback: String? = null
)

@Serializable
data class BackupSettings(
    val notificationsEnabled: Boolean,
    val reminderHour: Int,
    val reminderMinute: Int,
    val keepScreenOn: Boolean = false
)

@Serializable
data class BackupRoot(
    val schemaVersion: Int,
    val exportedAt: Long,
    val appVersion: String,
    val plants: List<BackupPlant>,
    val careLogs: List<BackupCareLog>,
    val settings: BackupSettings
)
