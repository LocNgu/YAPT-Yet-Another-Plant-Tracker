package com.yapt.planttracker.data.backup.autobackup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.yapt.planttracker.data.backup.CURRENT_SCHEMA_VERSION
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

/**
 * A cheap "has anything a backup would contain changed" token: hashes the rows and the backed-up
 * settings, never touching photo files. Includes the photo toggles so changing them forces a fresh run.
 */
object AutoBackupFingerprint {

    suspend fun compute(
        database: PlantDatabase,
        dataStore: DataStore<Preferences>,
        includePhotos: Boolean,
        optimizePhotos: Boolean
    ): String {
        val awayDao = database.awayPeriodDao()
        val parts = listOf(
            CURRENT_SCHEMA_VERSION,
            includePhotos,
            optimizePhotos,
            database.plantDao().getAllPlantsIncludingArchived().first(),
            database.careLogDao().getAllLogs().first(),
            database.plantPhotoDao().getAllPhotos().first(),
            database.customReminderDao().getAllReminders().first(),
            database.plantIssueDao().getAllIssues().first(),
            database.wateringAdjustmentDao().getAllAdjustments().first(),
            awayDao.getAllPeriods(),
            awayDao.getAllVisits(),
            awayDao.getAllVisitPlants(),
            backedUpSettings(dataStore.data.first())
        )
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(parts.joinToString("|") { "${it.hashCode()}:${(it as? Collection<*>)?.size ?: 0}" }.toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun backedUpSettings(prefs: Preferences): List<Any?> = listOf(
        prefs[SettingsKeys.NOTIFICATIONS_ENABLED],
        prefs[SettingsKeys.REMINDER_HOUR],
        prefs[SettingsKeys.REMINDER_MINUTE],
        prefs[SettingsKeys.KEEP_SCREEN_ON],
        prefs[SettingsKeys.COMBINE_NOTIFICATIONS],
        prefs[SettingsKeys.PHOTO_REMINDER_ENABLED],
        prefs[SettingsKeys.THEME_MODE],
        prefs[SettingsKeys.FERTILIZING_NOTIFICATIONS_ENABLED],
        prefs[SettingsKeys.ASK_BEFORE_CHANGING_INTERVALS],
        prefs[SettingsKeys.SEASONAL_AMPLITUDE],
        prefs[SettingsKeys.POST_WATERING_REMINDER_ENABLED]
    )
}
