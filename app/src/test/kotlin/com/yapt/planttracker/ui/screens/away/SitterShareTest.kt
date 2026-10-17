package com.yapt.planttracker.ui.screens.away

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.domain.away.SitterList
import com.yapt.planttracker.domain.away.SitterListPlant
import com.yapt.planttracker.domain.away.SitterListRoom
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** Sharing the sitter list (#899, product ADR-0065): the PDF intent, the cache file and the text fallback. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-rUS")
class SitterShareTest {

    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val day = LocalDate.now()

    @Before
    fun clean() {
        File(application.cacheDir, SitterShare.CACHE_SUBDIR).deleteRecursively()
        // FileProvider keeps its per-authority roots in a static map, but every Robolectric test gets its own
        // cache directory, so a root remembered from an earlier test would no longer contain this one's files.
        val cache = FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
        (cache.get(null) as MutableMap<*, *>).clear()
    }

    @After
    fun cleanUp() = clean()

    private fun pdfFile(name: String = "list"): File =
        SitterShare.newPdfFile(application, day).also { it.writeText(name) }

    @Test
    fun `the pdf intent has the type, a read grant on the uri, the stream and the caption`() {
        val file = pdfFile()

        val intent = SitterShare.pdfIntent(application, file, "Only water what's on page 1")

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("application/pdf", intent.type)
        assertEquals("Only water what's on page 1", intent.getStringExtra(Intent.EXTRA_TEXT))
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        assertNotNull(uri)
        assertEquals("${application.packageName}.fileprovider", uri!!.authority)
        assertEquals(file.name, uri.lastPathSegment)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
    }

    @Test
    fun `the file lives in the cache and a new share clears the old one`() {
        val first = pdfFile("one")
        assertTrue(first.exists())
        assertEquals(File(application.cacheDir, SitterShare.CACHE_SUBDIR), first.parentFile)

        val second = SitterShare.newPdfFile(application, day.plusDays(1))

        assertFalse(first.exists())
        assertEquals(0, second.parentFile!!.listFiles()!!.size)
    }

    @Test
    fun `sharing the pdf opens the chooser around the send intent`() {
        val file = pdfFile()

        SitterShare.sharePdf(application, file, "caption")

        val chooser = shadowOf(application).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals("application/pdf", send.type)
        assertEquals("caption", send.getStringExtra(Intent.EXTRA_TEXT))
    }

    @Test
    fun `the text fallback is a plain text send`() {
        SitterShare.shareText(application, "Plants to water")

        val chooser = shadowOf(application).nextStartedActivity
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("text/plain", send.type)
        assertEquals("Plants to water", send.getStringExtra(Intent.EXTRA_TEXT))
        assertFalse(send.hasExtra(Intent.EXTRA_STREAM))
    }

    private fun plant(id: Long) = SitterListPlant(id, "Plant $id", null, null, false)

    private fun list(visitPlants: Int, resting: Int): SitterList {
        val visit = (1..visitPlants).map { plant(it.toLong()) }
        val rest = (1..resting).map { plant(100L + it) }
        return SitterList(
            day,
            rooms = if (visit.isEmpty()) emptyList() else listOf(SitterListRoom("Kitchen", visit)),
            restingRooms = if (rest.isEmpty()) emptyList() else listOf(SitterListRoom("Bedroom", rest))
        )
    }

    private val dayText get() = awayWeekdayDayText(day, LocalDate.now())

    @Test
    fun `the caption names the page of the list and the page of the plants to leave dry`() {
        val output = AndroidSitterListOutput(application)

        assertEquals(
            "Plants to water on $dayText, by room. Only water what's on page 1; page 2 shows the ones to leave dry.",
            output.caption(list(visitPlants = 3, resting = 2))
        )
        assertEquals(
            "Plants to water on $dayText, by room. Only water what's on this page.",
            output.caption(list(visitPlants = 3, resting = 0))
        )
    }

    @Test
    fun `the caption covers several visit pages`() {
        val output = AndroidSitterListOutput(application)

        assertEquals(
            "Plants to water on $dayText, by room. Only water what's on pages 1 to 2.",
            output.caption(list(visitPlants = 12, resting = 0))
        )
        assertEquals(
            "Plants to water on $dayText, by room. Only water what's on pages 1 to 2; " +
                "page 3 onwards shows the ones to leave dry.",
            output.caption(list(visitPlants = 12, resting = 2))
        )
    }
}
