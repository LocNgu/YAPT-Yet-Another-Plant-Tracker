package com.yapt.planttracker.data.backup

import android.content.Context
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.entity.CareLogEntity
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.data.preferences.SettingsKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: PlantDatabase
    private lateinit var dataStore: DataStore<Preferences>
    private val testScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var backupManager: BackupManager

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries().build()
        dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { tmpFolder.newFile("test_settings.preferences_pb") }
        )
        backupManager = BackupManager(context, db, dataStore)
    }

    @After
    fun tearDown() {
        db.close()
        testScope.cancel()
        context.filesDir.resolve("restored_photos").deleteRecursively()
    }

    @Test
    fun roundTrip_withoutPhotos() = runBlocking {
        val plant = PlantEntity(
            id = 1L,
            name = "Monstera",
            species = null,
            room = null,
            coverPhotoUri = null,
            notes = null,
            wateringIntervalDays = null,
            fertilizingIntervalDays = null,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        db.plantDao().insertPlant(plant)
        val log = CareLogEntity(
            id = 1L,
            plantId = 1L,
            careType = "WATER",
            loggedAt = 2000L,
            notes = null,
            photoUri = null,
            amount = null,
            wateringFeedback = null
        )
        db.careLogDao().insertLog(log)

        val exportFile = tmpFolder.newFile("backup.yapt")
        val exportUri = Uri.fromFile(exportFile)
        val exportResult = backupManager.exportBackup(exportUri, includePhotos = false)
        assertTrue("Expected ExportSuccess", exportResult is BackupResult.ExportSuccess)
        assertEquals(1, (exportResult as BackupResult.ExportSuccess).plantCount)
        assertEquals(1, (exportResult as BackupResult.ExportSuccess).logCount)

        db.careLogDao().deleteAll()
        db.plantDao().deleteAll()
        assertEquals(0, db.plantDao().getAllPlants().first().size)

        val importResult = backupManager.importBackup(exportUri)
        assertTrue("Expected ImportSuccess", importResult is BackupResult.ImportSuccess)
        assertEquals(1, (importResult as BackupResult.ImportSuccess).plantCount)
        assertEquals(1, (importResult as BackupResult.ImportSuccess).logCount)

        val restoredPlants = db.plantDao().getAllPlants().first()
        assertEquals(1, restoredPlants.size)
        assertEquals("Monstera", restoredPlants[0].name)
        assertNull(restoredPlants[0].coverPhotoUri)
    }

    @Test
    fun roundTrip_withPhotos() = runBlocking {
        val photoBytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val photoFile = tmpFolder.newFile("cover.jpg")
        photoFile.writeBytes(photoBytes)
        val photoUri = Uri.fromFile(photoFile).toString()

        val plant = PlantEntity(
            id = 1L,
            name = "Ficus",
            species = null,
            room = null,
            coverPhotoUri = photoUri,
            notes = null,
            wateringIntervalDays = null,
            fertilizingIntervalDays = null,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        db.plantDao().insertPlant(plant)

        val exportFile = tmpFolder.newFile("backup_photos.yapt")
        val exportUri = Uri.fromFile(exportFile)
        val exportResult = backupManager.exportBackup(exportUri, includePhotos = true)
        assertTrue("Expected ExportSuccess", exportResult is BackupResult.ExportSuccess)

        db.plantDao().deleteAll()
        assertEquals(0, db.plantDao().getAllPlants().first().size)

        val importResult = backupManager.importBackup(exportUri)
        assertTrue("Expected ImportSuccess", importResult is BackupResult.ImportSuccess)

        val restoredPlants = db.plantDao().getAllPlants().first()
        assertEquals(1, restoredPlants.size)
        assertNotNull("coverPhotoUri should not be null after photo round-trip", restoredPlants[0].coverPhotoUri)

        val restoredPath = restoredPlants[0].coverPhotoUri!!
        val restoredBytes = File(restoredPath).readBytes()
        assertArrayEquals("Restored photo bytes must match originals", photoBytes, restoredBytes)
    }

    @Test
    fun emptyDb_exportAndImport() = runBlocking {
        val exportFile = tmpFolder.newFile("empty_backup.yapt")
        val exportUri = Uri.fromFile(exportFile)
        val exportResult = backupManager.exportBackup(exportUri, includePhotos = false)

        assertTrue("Expected ExportSuccess", exportResult is BackupResult.ExportSuccess)
        assertEquals(0, (exportResult as BackupResult.ExportSuccess).plantCount)
        assertEquals(0, exportResult.logCount)

        val importResult = backupManager.importBackup(exportUri)
        assertTrue("Expected ImportSuccess", importResult is BackupResult.ImportSuccess)
        assertEquals(0, (importResult as BackupResult.ImportSuccess).plantCount)
        assertEquals(0, db.plantDao().getAllPlants().first().size)
    }

    @Test
    fun futureSchema_returnsWarningThenImportsOnProceed() = runBlocking {
        val futureJson = """
            {"schemaVersion":999,"exportedAt":1000,"appVersion":"99.0","plants":[{"id":1,"name":"FuturePlant","createdAt":1000,"updatedAt":1000}],"careLogs":[],"settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0}}
        """.trimIndent()

        val zipFile = tmpFolder.newFile("future.yapt")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(futureJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val uri = Uri.fromFile(zipFile)

        val result = backupManager.importBackup(uri)
        assertTrue("Expected FutureSchemaWarning", result is BackupResult.FutureSchemaWarning)
        assertEquals(999, (result as BackupResult.FutureSchemaWarning).schemaVersion)
        assertEquals(0, db.plantDao().getAllPlants().first().size)

        val proceedResult = result.onProceed()
        assertTrue("Expected ImportSuccess after proceed", proceedResult is BackupResult.ImportSuccess)
        assertEquals(1, (proceedResult as BackupResult.ImportSuccess).plantCount)

        val plants = db.plantDao().getAllPlants().first()
        assertEquals(1, plants.size)
        assertEquals("FuturePlant", plants[0].name)
    }

    @Test
    fun corruptZip_returnsError() = runBlocking {
        val corruptFile = tmpFolder.newFile("corrupt.yapt")
        corruptFile.writeBytes(ByteArray(256) { it.toByte() })
        val uri = Uri.fromFile(corruptFile)

        val result = backupManager.importBackup(uri)
        assertTrue("Expected BackupResult.Error for corrupt ZIP", result is BackupResult.Error)
        assertEquals(0, db.plantDao().getAllPlants().first().size)
    }

    @Test
    fun missingBackupJson_returnsError() = runBlocking {
        val zipFile = tmpFolder.newFile("nojson.yapt")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("photos/some.jpg"))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        val uri = Uri.fromFile(zipFile)

        val result = backupManager.importBackup(uri)
        assertTrue("Expected BackupResult.Error when backup.json is missing", result is BackupResult.Error)
        assertEquals(0, db.plantDao().getAllPlants().first().size)
    }

    @Test
    fun zipSlip_photoDoesNotEscapeFilesDir() = runBlocking {
        val validJson = """
            {"schemaVersion":1,"exportedAt":1000,"appVersion":"1.0","plants":[],"careLogs":[],"settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0}}
        """.trimIndent()

        val zipFile = tmpFolder.newFile("zipslip.yapt")
        ZipOutputStream(zipFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(validJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()

            zip.putNextEntry(ZipEntry("photos/../../prefs.xml"))
            zip.write("sensitive".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }

        backupManager.importBackup(Uri.fromFile(zipFile))

        // Security property: the malicious entry must not land outside filesDir.
        // (On some Android versions the ZipEntry name is normalized and the entry is
        // silently dropped, which also satisfies the security requirement.)
        assertFalse(
            "Zip-slip file must NOT land in filesDir root",
            File(context.filesDir, "prefs.xml").exists()
        )
        assertFalse(
            "Zip-slip file must NOT escape to parent of filesDir",
            File(context.filesDir.parentFile ?: context.filesDir, "prefs.xml").exists()
        )
    }

    @Test
    fun settingsRoundTrip() = runBlocking {
        dataStore.edit { prefs ->
            prefs[SettingsKeys.NOTIFICATIONS_ENABLED] = false
            prefs[SettingsKeys.REMINDER_HOUR] = 21
            prefs[SettingsKeys.REMINDER_MINUTE] = 30
        }

        val exportFile = tmpFolder.newFile("settings_backup.yapt")
        val exportUri = Uri.fromFile(exportFile)
        backupManager.exportBackup(exportUri, includePhotos = false)

        dataStore.edit { prefs ->
            prefs[SettingsKeys.NOTIFICATIONS_ENABLED] = true
            prefs[SettingsKeys.REMINDER_HOUR] = 9
            prefs[SettingsKeys.REMINDER_MINUTE] = 0
        }

        backupManager.importBackup(exportUri)

        val prefs = dataStore.data.first()
        assertFalse(
            "notificationsEnabled should be restored to false",
            prefs[SettingsKeys.NOTIFICATIONS_ENABLED] ?: true
        )
        assertEquals(21, prefs[SettingsKeys.REMINDER_HOUR])
        assertEquals(30, prefs[SettingsKeys.REMINDER_MINUTE])
    }

    @Test
    fun photoContentIntegrity_sha256Match() = runBlocking {
        val knownBytes = "Hello YAPT photo integrity test".toByteArray(Charsets.UTF_8)
        val photoFile = tmpFolder.newFile("integrity.jpg")
        photoFile.writeBytes(knownBytes)
        val photoUri = Uri.fromFile(photoFile).toString()

        val plant = PlantEntity(
            id = 1L,
            name = "IntegrityPlant",
            species = null,
            room = null,
            coverPhotoUri = photoUri,
            notes = null,
            wateringIntervalDays = null,
            fertilizingIntervalDays = null,
            createdAt = 1000L,
            updatedAt = 1000L
        )
        db.plantDao().insertPlant(plant)

        val exportFile = tmpFolder.newFile("integrity_backup.yapt")
        val exportUri = Uri.fromFile(exportFile)
        backupManager.exportBackup(exportUri, includePhotos = true)

        db.plantDao().deleteAll()

        backupManager.importBackup(exportUri)

        val restoredPlants = db.plantDao().getAllPlants().first()
        assertEquals(1, restoredPlants.size)
        val restoredPath = restoredPlants[0].coverPhotoUri
        assertNotNull("Restored coverPhotoUri must not be null", restoredPath)

        val restoredBytes = File(restoredPath!!).readBytes()
        val digest = MessageDigest.getInstance("SHA-256")
        val originalHash = digest.digest(knownBytes)
        digest.reset()
        val restoredHash = digest.digest(restoredBytes)
        assertArrayEquals("SHA-256 hash of restored photo must match original", originalHash, restoredHash)
    }
}
