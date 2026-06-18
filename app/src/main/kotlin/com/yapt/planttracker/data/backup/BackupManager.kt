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
import com.yapt.planttracker.data.entity.PlantPhotoEntity
import com.yapt.planttracker.domain.model.FertilizerType
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

// Schema 3 (PR #290): plant_photos table added — bump signals this backup may contain per-plant photo gallery data.
// Schema 2 (PR #209): useLiquidFertilizer added.
// wateringDueDateOverride (PR #176) was nullable with a default — backward-compatible, no bump was needed then.
const val CURRENT_SCHEMA_VERSION = 3
private const val BACKUP_JSON_ENTRY = "backup.json"
private const val PHOTOS_DIR = "photos/"

sealed class BackupResult {
    data class ExportSuccess(val plantCount: Int, val logCount: Int) : BackupResult()
    data class ImportSuccess(val plantCount: Int, val logCount: Int) : BackupResult()
    data class FutureSchemaWarning(
        val schemaVersion: Int,
        val onProceed: suspend () -> BackupResult,
        val onDismiss: suspend () -> Unit,
    ) : BackupResult()
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
            val plantPhotoDao = database.plantPhotoDao()

            val plants = plantDao.getAllPlants().first()
            val allLogs = careLogDao.getAllLogs().first().groupBy { it.plantId }
            val careLogs = plants.flatMap { allLogs[it.id].orEmpty() }
            val activePlantIds = plants.map { it.id }.toSet()
            val allPlantPhotos = plantPhotoDao.getAllPhotos().first().filter { it.plantId in activePlantIds }

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
                for (photo in allPlantPhotos) {
                    if (photo.uri !in photoMapping) {
                        photoMapping[photo.uri] = buildZipPhotoName(photo.uri)
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
                    updatedAt = entity.updatedAt,
                    wateringDueDateOverride = entity.wateringDueDateOverride,
                    useLiquidFertilizer = entity.useLiquidFertilizer
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
                    wateringFeedback = entity.wateringFeedback,
                    fertilizerType = entity.fertilizerType
                )
            }

            val backupPlantPhotos = allPlantPhotos.map { entity ->
                BackupPlantPhoto(
                    id = entity.id,
                    plantId = entity.plantId,
                    uri = if (includePhotos) photoMapping[entity.uri] else entity.uri,
                    capturedAt = entity.capturedAt
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
                plantPhotos = backupPlantPhotos,
                settings = BackupSettings(
                    notificationsEnabled = notificationsEnabled,
                    reminderHour = reminderHour,
                    reminderMinute = reminderMinute,
                    keepScreenOn = keepScreenOn
                )
            )

            val jsonString = backupJson.encodeToString(BackupRoot.serializer(), backupRoot)

            val tempFile = File(context.cacheDir, UUID.randomUUID().toString())
            try {
                tempFile.outputStream().buffered().use { tempOut ->
                    ZipOutputStream(tempOut).use { zip ->
                        zip.putNextEntry(ZipEntry(BACKUP_JSON_ENTRY))
                        zip.write(jsonString.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()

                        if (includePhotos) {
                            for ((originalUri, zipPath) in photoMapping) {
                                val input = runCatching {
                                    val parsedUri = Uri.parse(originalUri)
                                    when (parsedUri.scheme) {
                                        null -> File(originalUri).inputStream()
                                        "file" -> File(parsedUri.path!!).inputStream()
                                        else -> context.contentResolver.openInputStream(parsedUri)
                                    }
                                }.getOrNull() ?: continue
                                input.use {
                                    zip.putNextEntry(ZipEntry(zipPath))
                                    it.copyTo(zip)
                                    zip.closeEntry()
                                }
                            }
                        }
                    }
                }
                context.contentResolver.openOutputStream(destinationUri)?.use { out ->
                    tempFile.inputStream().copyTo(out)
                } ?: error("Could not open output stream for URI: $destinationUri")
            } finally {
                tempFile.delete()
            }

            BackupResult.ExportSuccess(plants.size, careLogs.size)
        }.getOrElse { e ->
            BackupResult.Error(e.message ?: "Export failed")
        }
    }

    suspend fun importBackup(sourceUri: Uri): BackupResult = withContext(Dispatchers.IO) {
        val photoTempFiles = mutableMapOf<String, File>()
        // When FutureSchemaWarning is returned, onProceed owns cleanup; skip the finally block.
        var deferCleanup = false
        try {
            runCatching {
                val jsonBytes: ByteArray

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
                                    val tmp = File(context.cacheDir, UUID.randomUUID().toString())
                                    photoTempFiles[entry.name] = tmp
                                    tmp.outputStream().use { out -> zip.copyTo(out) }
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
                    deferCleanup = true
                    return@runCatching BackupResult.FutureSchemaWarning(
                        schemaVersion = backup.schemaVersion,
                        onProceed = {
                            try {
                                performImport(backup, photoTempFiles)
                            } finally {
                                photoTempFiles.values.forEach { it.delete() }
                            }
                        },
                        onDismiss = {
                            withContext(Dispatchers.IO) {
                                photoTempFiles.values.forEach { it.delete() }
                            }
                        },
                    )
                }

                performImport(backup, photoTempFiles)
            }.getOrElse { e ->
                BackupResult.Error(e.message ?: "Import failed")
            }
        } finally {
            if (!deferCleanup) photoTempFiles.values.forEach { it.delete() }
        }
    }

    private suspend fun performImport(
        backup: BackupRoot,
        photoTempFiles: Map<String, File>
    ): BackupResult = withContext(Dispatchers.IO) {
        val restoredPhotosDir = context.filesDir.resolve("restored_photos").also { it.mkdirs() }
        val writtenFiles = mutableListOf<File>()
        var dbCommitted = false
        try {
            val zipPathToLocalPath = mutableMapOf<String, String>()
            for ((zipPath, tmpFile) in photoTempFiles) {
                val filename = File(zipPath.removePrefix(PHOTOS_DIR)).name
                val destFile = File(restoredPhotosDir, filename)
                tmpFile.copyTo(destFile, overwrite = true)
                tmpFile.delete()
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
                    updatedAt = bp.updatedAt,
                    wateringDueDateOverride = bp.wateringDueDateOverride,
                    useLiquidFertilizer = bp.useLiquidFertilizer
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
                    wateringFeedback = bl.wateringFeedback,
                    fertilizerType = runCatching { FertilizerType.valueOf(bl.fertilizerType) }.getOrDefault(FertilizerType.UNSPECIFIED).name
                )
            }

            val plantPhotoEntities = backup.plantPhotos.mapNotNull { bp ->
                val resolvedUri = bp.uri?.let { zipPathToLocalPath[it] ?: it } ?: return@mapNotNull null
                PlantPhotoEntity(
                    id = bp.id,
                    plantId = bp.plantId,
                    uri = resolvedUri,
                    capturedAt = bp.capturedAt
                )
            }

            database.withTransaction {
                database.plantPhotoDao().deleteAll()
                database.careLogDao().deleteAll()
                database.plantDao().deleteAll()
                database.plantDao().insertAll(plantEntities)
                database.careLogDao().insertAll(careLogEntities)
                database.plantPhotoDao().insertAll(plantPhotoEntities)
            }
            dbCommitted = true

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
            if (!dbCommitted) writtenFiles.forEach { it.delete() }
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
