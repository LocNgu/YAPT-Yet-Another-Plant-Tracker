package com.yapt.planttracker.data.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.entity.CareLogEntity
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.worker.ReminderScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

const val CURRENT_SCHEMA_VERSION = 1
private const val BACKUP_JSON_ENTRY = "backup.json"
private const val PHOTOS_DIR = "photos/"

sealed class BackupResult {
    data class ExportSuccess(val plantCount: Int, val logCount: Int) : BackupResult()
    data class ImportSuccess(val plantCount: Int, val logCount: Int) : BackupResult()
    data class FutureSchemaWarning(val schemaVersion: Int, val onProceed: suspend () -> BackupResult) : BackupResult()
    data class Error(val message: String) : BackupResult()
}

class BackupManager(
    private val context: Context,
    private val database: PlantDatabase,
    private val dataStore: DataStore<Preferences>
) {

    suspend fun exportBackup(
        destinationUri: Uri,
        includePhotos: Boolean
    ): BackupResult = withContext(Dispatchers.IO) {
        runCatching {
            val plantDao = database.plantDao()
            val careLogDao = database.careLogDao()

            val plants = plantDao.getAllPlants().first()
            val allLogs = careLogDao.getAllLogs().first().groupBy { it.plantId }
            val careLogs = plants.flatMap { allLogs[it.id].orEmpty() }

            val prefs = dataStore.data.first()
            val notificationsEnabled = prefs[SettingsKeys.NOTIFICATIONS_ENABLED] ?: true
            val reminderHour = prefs[SettingsKeys.REMINDER_HOUR] ?: 9
            val reminderMinute = prefs[SettingsKeys.REMINDER_MINUTE] ?: 0
            val keepScreenOn = prefs[SettingsKeys.KEEP_SCREEN_ON] ?: false

            val photoMapping = mutableMapOf<String, String>()
            if (includePhotos) {
                for (plant in plants) {
                    plant.coverPhotoUri?.let { uri ->
                        if (uri !in photoMapping) {
                            photoMapping[uri] = buildZipPhotoName(uri)
                        }
                    }
                }
                for (log in careLogs) {
                    log.photoUri?.let { uri ->
                        if (uri !in photoMapping) {
                            photoMapping[uri] = buildZipPhotoName(uri)
                        }
                    }
                }
            }

            val backupPlants = plants.map { entity ->
                BackupPlant(
                    id = entity.id,
                    name = entity.name,
                    species = entity.species,
                    room = entity.room,
                    coverPhotoUri = if (includePhotos) entity.coverPhotoUri?.let { photoMapping[it] } else null,
                    notes = entity.notes,
                    wateringIntervalDays = entity.wateringIntervalDays,
                    fertilizingIntervalDays = entity.fertilizingIntervalDays,
                    createdAt = entity.createdAt,
                    updatedAt = entity.updatedAt
                )
            }

            val backupLogs = careLogs.map { entity ->
                BackupCareLog(
                    id = entity.id,
                    plantId = entity.plantId,
                    careType = entity.careType,
                    loggedAt = entity.loggedAt,
                    notes = entity.notes,
                    photoUri = if (includePhotos) entity.photoUri?.let { photoMapping[it] } else null,
                    amount = entity.amount,
                    wateringFeedback = entity.wateringFeedback
                )
            }

            val backupRoot = BackupRoot(
                schemaVersion = CURRENT_SCHEMA_VERSION,
                exportedAt = System.currentTimeMillis(),
                appVersion = runCatching {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName
                }.getOrNull() ?: "1.0",
                plants = backupPlants,
                careLogs = backupLogs,
                settings = BackupSettings(
                    notificationsEnabled = notificationsEnabled,
                    reminderHour = reminderHour,
                    reminderMinute = reminderMinute,
                    keepScreenOn = keepScreenOn
                )
            )

            val jsonString = backupJson.encodeToString(BackupRoot.serializer(), backupRoot)

            context.contentResolver.openOutputStream(destinationUri)?.use { outputStream ->
                ZipOutputStream(outputStream.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(BACKUP_JSON_ENTRY))
                    zip.write(jsonString.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    if (includePhotos) {
                        for ((originalUri, zipPath) in photoMapping) {
                            val input = runCatching {
                                context.contentResolver.openInputStream(Uri.parse(originalUri))
                            }.getOrNull() ?: continue
                            input.use {
                                zip.putNextEntry(ZipEntry(zipPath))
                                it.copyTo(zip)
                                zip.closeEntry()
                            }
                        }
                    }
                }
            } ?: error("Could not open output stream for URI: $destinationUri")

            BackupResult.ExportSuccess(plants.size, careLogs.size)
        }.getOrElse { e ->
            BackupResult.Error(e.message ?: "Export failed")
        }
    }

    suspend fun importBackup(sourceUri: Uri): BackupResult = withContext(Dispatchers.IO) {
        runCatching {
            val jsonBytes: ByteArray
            val photoEntries = mutableMapOf<String, ByteArray>()

            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                ZipInputStream(inputStream.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    var foundJson = false
                    val jsonHolder = mutableListOf<ByteArray>()
                    while (entry != null) {
                        when {
                            entry.name == BACKUP_JSON_ENTRY -> {
                                jsonHolder.add(zip.readBytes())
                                foundJson = true
                            }
                            entry.name.startsWith(PHOTOS_DIR) && !entry.isDirectory -> {
                                photoEntries[entry.name] = zip.readBytes()
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                    if (!foundJson) error("Backup file is not compatible: backup.json not found")
                    jsonBytes = jsonHolder[0]
                }
            } ?: error("Could not open input stream for URI: $sourceUri")

            val backup = backupJson.decodeFromString(BackupRoot.serializer(), jsonBytes.toString(Charsets.UTF_8))

            if (backup.schemaVersion > CURRENT_SCHEMA_VERSION) {
                return@withContext BackupResult.FutureSchemaWarning(backup.schemaVersion) {
                    performImport(backup, photoEntries)
                }
            }

            performImport(backup, photoEntries)
        }.getOrElse { e ->
            BackupResult.Error(e.message ?: "Import failed")
        }
    }

    private suspend fun performImport(
        backup: BackupRoot,
        photoEntries: Map<String, ByteArray>
    ): BackupResult = withContext(Dispatchers.IO) {
        val restoredPhotosDir = context.filesDir.resolve("restored_photos").also { it.mkdirs() }
        val writtenFiles = mutableListOf<File>()
        try {
            val zipPathToLocalPath = mutableMapOf<String, String>()
            for ((zipPath, bytes) in photoEntries) {
                val filename = File(zipPath.removePrefix(PHOTOS_DIR)).name
                val destFile = File(restoredPhotosDir, filename)
                destFile.writeBytes(bytes)
                writtenFiles.add(destFile)
                zipPathToLocalPath[zipPath] = destFile.absolutePath
            }

            val plantEntities = backup.plants.map { bp ->
                PlantEntity(
                    id = bp.id,
                    name = bp.name,
                    species = bp.species,
                    room = bp.room,
                    coverPhotoUri = bp.coverPhotoUri?.let { zipPathToLocalPath[it] ?: it },
                    notes = bp.notes,
                    wateringIntervalDays = bp.wateringIntervalDays,
                    fertilizingIntervalDays = bp.fertilizingIntervalDays,
                    createdAt = bp.createdAt,
                    updatedAt = bp.updatedAt
                )
            }

            val careLogEntities = backup.careLogs.map { bl ->
                CareLogEntity(
                    id = bl.id,
                    plantId = bl.plantId,
                    careType = bl.careType,
                    loggedAt = bl.loggedAt,
                    notes = bl.notes,
                    photoUri = bl.photoUri?.let { zipPathToLocalPath[it] ?: it },
                    amount = bl.amount,
                    wateringFeedback = bl.wateringFeedback
                )
            }

            database.withTransaction {
                database.careLogDao().deleteAll()
                database.plantDao().deleteAll()
                database.plantDao().insertAll(plantEntities)
                database.careLogDao().insertAll(careLogEntities)
            }

            dataStore.edit { prefs ->
                prefs[SettingsKeys.NOTIFICATIONS_ENABLED] = backup.settings.notificationsEnabled
                prefs[SettingsKeys.REMINDER_HOUR] = backup.settings.reminderHour
                prefs[SettingsKeys.REMINDER_MINUTE] = backup.settings.reminderMinute
                prefs[SettingsKeys.KEEP_SCREEN_ON] = backup.settings.keepScreenOn
            }

            if (backup.settings.notificationsEnabled) {
                ReminderScheduler.schedule(context, backup.settings.reminderHour, backup.settings.reminderMinute)
            } else {
                ReminderScheduler.cancel(context)
            }

            BackupResult.ImportSuccess(backup.plants.size, backup.careLogs.size)
        } catch (e: Exception) {
            writtenFiles.forEach { it.delete() }
            throw e
        }
    }

    private fun buildZipPhotoName(uriString: String): String {
        val uri = Uri.parse(uriString)
        val lastSegment = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast('%')
            ?.let { if (it.contains('.')) it else "$it.jpg" }
            ?: "photo.jpg"
        return "$PHOTOS_DIR${UUID.randomUUID()}_$lastSegment"
    }
}
