package com.yapt.planttracker.util

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.entity.CareLogEntity
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.data.entity.PlantPhotoEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

private const val PACKAGE_NAME = "com.yapt.planttracker"

class OrphanPhotoSweeperNormalizationTest {

    private val filesDir = File("/data/data/$PACKAGE_NAME/files")

    @Test
    fun `bare absolute path is used as-is`() {
        val ref = "/data/data/$PACKAGE_NAME/files/restored_photos/photo.jpg"
        assertEquals(File(ref), OrphanPhotoSweeper.normalizeToFile(ref, PACKAGE_NAME, filesDir))
    }

    @Test
    fun `file scheme uri resolves to its path`() {
        val ref = "file:///data/data/$PACKAGE_NAME/files/images/photo.jpg"
        assertEquals(
            File("/data/data/$PACKAGE_NAME/files/images/photo.jpg"),
            OrphanPhotoSweeper.normalizeToFile(ref, PACKAGE_NAME, filesDir)
        )
    }

    @Test
    fun `own fileprovider plant_images content uri maps under images`() {
        val ref = "content://$PACKAGE_NAME.fileprovider/plant_images/plant_photo_123.jpg"
        assertEquals(
            File(filesDir, "images/plant_photo_123.jpg"),
            OrphanPhotoSweeper.normalizeToFile(ref, PACKAGE_NAME, filesDir)
        )
    }

    @Test
    fun `foreign content uri from another authority never maps to a file`() {
        val ref = "content://media/external/images/media/42"
        assertNull(OrphanPhotoSweeper.normalizeToFile(ref, PACKAGE_NAME, filesDir))
    }

    @Test
    fun `own fileprovider authority with an unrecognised first segment never maps to a file`() {
        val ref = "content://$PACKAGE_NAME.fileprovider/some_other_root/photo.jpg"
        assertNull(OrphanPhotoSweeper.normalizeToFile(ref, PACKAGE_NAME, filesDir))
    }

    @Test
    fun `external plant_photos cache root is not swept`() {
        // file_paths.xml declares an external-cache-path named plant_photos rooted at ".", which
        // this sweep deliberately never maps to a file — only the own fileprovider images root does.
        val ref = "content://$PACKAGE_NAME.fileprovider/plant_photos/photo.jpg"
        assertNull(OrphanPhotoSweeper.normalizeToFile(ref, PACKAGE_NAME, filesDir))
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OrphanPhotoSweeperTest {

    private lateinit var db: PlantDatabase
    private lateinit var tempFolder: TemporaryFolder
    private lateinit var filesDir: File
    private lateinit var imagesDir: File
    private lateinit var restoredPhotosDir: File

    private val now = 10_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        tempFolder = TemporaryFolder().apply { create() }
        filesDir = tempFolder.newFolder("files")
        imagesDir = File(filesDir, "images").apply { mkdirs() }
        restoredPhotosDir = File(filesDir, "restored_photos").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        db.close()
        tempFolder.delete()
    }

    private fun oldFile(dir: File, name: String): File =
        File(dir, name).apply {
            writeText("data")
            setLastModified(now - OrphanPhotoSweeper.MIN_AGE_MILLIS - 1)
        }

    private fun freshFile(dir: File, name: String): File =
        File(dir, name).apply {
            writeText("data")
            setLastModified(now - 1_000)
        }

    private suspend fun insertPlant(coverPhotoUri: String? = null, archivedAt: Long? = null): Long =
        db.plantDao().insertPlant(
            PlantEntity(
                name = "TestPlant",
                species = null,
                room = null,
                coverPhotoUri = coverPhotoUri,
                notes = null,
                wateringIntervalDays = 7,
                fertilizingIntervalDays = null,
                createdAt = 1L,
                updatedAt = 1L,
                archivedAt = archivedAt
            )
        )

    @Test
    fun `unreferenced old file is deleted`() = runTest {
        val orphan = oldFile(imagesDir, "orphan.jpg")

        val result = OrphanPhotoSweeper.sweep(db, PACKAGE_NAME, filesDir, now = now)

        assertFalse(orphan.exists())
        assertEquals(1, result.filesDeleted)
        assertEquals(4L, result.bytesDeleted)
    }

    @Test
    fun `file referenced via plants coverPhotoUri including an archived plant is kept`() = runTest {
        val file = oldFile(imagesDir, "cover.jpg")
        insertPlant(coverPhotoUri = file.absolutePath, archivedAt = 5_000L)

        OrphanPhotoSweeper.sweep(db, PACKAGE_NAME, filesDir, now = now)

        assertTrue(file.exists())
    }

    @Test
    fun `file referenced via care_logs photoUri is kept`() = runTest {
        val file = oldFile(imagesDir, "log.jpg")
        val plantId = insertPlant()
        db.careLogDao().insertLog(
            CareLogEntity(
                plantId = plantId,
                careType = "PHOTO",
                loggedAt = 1L,
                notes = null,
                photoUri = file.absolutePath,
                amount = null,
                wateringFeedback = null
            )
        )

        OrphanPhotoSweeper.sweep(db, PACKAGE_NAME, filesDir, now = now)

        assertTrue(file.exists())
    }

    @Test
    fun `file referenced via plant_photos uri is kept`() = runTest {
        val file = oldFile(imagesDir, "gallery.jpg")
        val plantId = insertPlant()
        db.plantPhotoDao().insertPhoto(
            PlantPhotoEntity(plantId = plantId, uri = file.absolutePath, capturedAt = 1L)
        )

        OrphanPhotoSweeper.sweep(db, PACKAGE_NAME, filesDir, now = now)

        assertTrue(file.exists())
    }

    @Test
    fun `fresh unreferenced file is kept as an in-flight capture`() = runTest {
        val file = freshFile(imagesDir, "in_flight.jpg")

        OrphanPhotoSweeper.sweep(db, PACKAGE_NAME, filesDir, now = now)

        assertTrue(file.exists())
    }

    @Test
    fun `fresh processing temp file is kept`() = runTest {
        val file = freshFile(imagesDir, "photo.jpg.processing")

        OrphanPhotoSweeper.sweep(db, PACKAGE_NAME, filesDir, now = now)

        assertTrue(file.exists())
    }

    @Test
    fun `stale processing temp file is reclaimed`() = runTest {
        val file = oldFile(imagesDir, "photo.jpg.processing")

        OrphanPhotoSweeper.sweep(db, PACKAGE_NAME, filesDir, now = now)

        assertFalse(file.exists())
    }

    @Test
    fun `subdirectory of a swept dir is untouched`() = runTest {
        val subDir = File(imagesDir, "nested").apply { mkdirs() }
        val nestedFile = oldFile(subDir, "orphan.jpg")

        OrphanPhotoSweeper.sweep(db, PACKAGE_NAME, filesDir, now = now)

        assertTrue(nestedFile.exists())
        // The subdirectory entry itself is not a regular file, so it's skipped without error.
        assertTrue(subDir.exists())
    }

    @Test
    fun `files outside the two swept dirs are untouched`() = runTest {
        val outside = oldFile(filesDir, "orphan.jpg")

        OrphanPhotoSweeper.sweep(db, PACKAGE_NAME, filesDir, now = now)

        assertTrue(outside.exists())
    }

    @Test
    fun `gallery content uri reference deletes nothing`() = runTest {
        val file = oldFile(imagesDir, "unrelated.jpg")
        val plantId = insertPlant()
        db.plantPhotoDao().insertPhoto(
            PlantPhotoEntity(plantId = plantId, uri = "content://media/external/images/media/1", capturedAt = 1L)
        )

        val result = OrphanPhotoSweeper.sweep(db, PACKAGE_NAME, filesDir, now = now)

        // The gallery URI never maps to a file, so it protects nothing — the unrelated orphan is
        // still swept, and the gallery-owned image itself was of course never touched (never on disk here).
        assertFalse(file.exists())
        assertEquals(1, result.filesDeleted)
    }

    @Test
    fun `restored_photos directory is swept the same as images`() = runTest {
        val orphan = oldFile(restoredPhotosDir, "orphan.jpg")

        OrphanPhotoSweeper.sweep(db, PACKAGE_NAME, filesDir, now = now)

        assertFalse(orphan.exists())
    }
}
