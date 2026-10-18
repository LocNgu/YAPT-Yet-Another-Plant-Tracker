package com.yapt.planttracker.ui.screens.away

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.domain.away.SitterList
import com.yapt.planttracker.domain.away.SitterListPlant
import com.yapt.planttracker.domain.away.SitterListRoom
import com.yapt.planttracker.domain.away.SitterPdfLayout
import com.yapt.planttracker.util.ImageUtils
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate

/** The sitter PDF on a real device (#899, product ADR-0065): a readable A4 document with one page per plan page. */
@RunWith(AndroidJUnit4::class)
class SitterListPdfRendererTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file = File(context.cacheDir, "sitter-renderer-test.pdf")
    private val photoFiles = mutableListOf<File>()

    @After
    fun tearDown() {
        file.delete()
        photoFiles.forEach { it.delete() }
    }

    private fun jpeg(dir: File, name: String): File {
        dir.mkdirs()
        val photo = File(dir, name).also { photoFiles += it }
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        return photo
    }

    private fun plant(id: Long, photo: String? = null, missed: Boolean = false) = SitterListPlant(
        plantId = id,
        name = "Plant $id",
        species = if (id % 2 == 0L) "Species $id" else null,
        coverPhotoUri = photo,
        missedLastTime = missed
    )

    private fun list(visitPlants: Int, resting: Int) = SitterList(
        LocalDate.now(),
        rooms = listOf(
            SitterListRoom("Kitchen", (1..visitPlants).map { plant(it.toLong(), missed = it == 1) })
        ),
        restingRooms = if (resting == 0) {
            emptyList()
        } else {
            listOf(SitterListRoom(null, (1..resting).map { plant(100L + it) }))
        }
    )

    private fun pageCount(file: File): Int = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        .use { descriptor -> PdfRenderer(descriptor).use { it.pageCount } }

    @Test
    fun theDocumentHasOnePagePerPlannedPageAndPhotolessPlantsRender() {
        val sitterList = list(visitPlants = 11, resting = 2)

        SitterListPdfRenderer.write(context, sitterList, file)

        assertTrue(file.length() > 0)
        assertEquals(SitterPdfLayout.paginate(sitterList).size, pageCount(file))
    }

    @Test
    fun anUnreadablePhotoFallsBackToTheNoPhotoBox() {
        val sitterList = SitterList(
            LocalDate.now(),
            listOf(SitterListRoom("Kitchen", listOf(plant(1, photo = "content://does.not.exist/photo")))),
            emptyList()
        )

        SitterListPdfRenderer.write(context, sitterList, file)

        assertEquals(1, pageCount(file))
    }

    @Test
    fun aRestoredCoverStoredAsABarePathIsRead() {
        val photo = jpeg(File(context.filesDir, "restored_photos"), "sitter-test-restored.jpg")

        assertNotNull(SitterPhotoLoader.load(context, photo.absolutePath))
    }

    @Test
    fun aCameraCoverThroughTheAppFileProviderIsRead() {
        val photo = jpeg(File(context.filesDir, "images"), "sitter-test-camera.jpg")
        val uri = ImageUtils.createCameraImageUri(context, photo)

        assertNotNull(SitterPhotoLoader.load(context, uri.toString()))
    }
}
