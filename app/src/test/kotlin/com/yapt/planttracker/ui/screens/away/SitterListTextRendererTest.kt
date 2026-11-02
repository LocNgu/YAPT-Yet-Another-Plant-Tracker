package com.yapt.planttracker.ui.screens.away

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.domain.away.SitterList
import com.yapt.planttracker.domain.away.SitterListPlant
import com.yapt.planttracker.domain.away.SitterListRoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.Locale

/** The text fallback of the sitter list (#899, product ADR-0065): format and plurals. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], qualifiers = "en-rUS")
class SitterListTextRendererTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val today = LocalDate.of(2026, 10, 1)
    private val visitDay = LocalDate.of(2026, 10, 12)

    private fun plant(
        id: Long,
        name: String,
        species: String? = null,
        missed: Boolean = false
    ) = SitterListPlant(id, name, species, coverPhotoUri = null, missedLastTime = missed)

    private fun render(list: SitterList) = SitterListTextRenderer.render(context, list, today)

    @Test
    fun `it lists the date, the count, each room and the plants with species and the missed tag`() {
        val list = SitterList(
            visitDay,
            rooms = listOf(
                SitterListRoom(
                    "Kitchen",
                    listOf(plant(1, "Basil", species = "Ocimum basilicum", missed = true), plant(2, "Fern"))
                ),
                SitterListRoom(null, listOf(plant(3, "Pothos")))
            ),
            restingRooms = emptyList()
        )

        assertEquals(
            """
            Plants to water on Mon, Oct 12
            Water 3 plants

            Watering tips
            - Feel the soil first: if it's dry 2-3 cm down, water.
            - Water slowly until a little runs out of the bottom.
            - Empty saucers after about 15 minutes.
            - When in doubt, leave it dry.

            Kitchen
            - Basil (Ocimum basilicum) · Missed last time
            - Fern

            No room
            - Pothos

            Made with YAPT · github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker
            """.trimIndent(),
            render(list)
        )
    }

    @Test
    fun `the resting plants follow under their own heading`() {
        val list = SitterList(
            visitDay,
            rooms = listOf(SitterListRoom("Kitchen", listOf(plant(1, "Fern")))),
            restingRooms = listOf(
                SitterListRoom("Bedroom", listOf(plant(2, "Cactus", species = "Echinocactus"), plant(3, "Aloe")))
            )
        )

        assertEquals(
            """
            Plants to water on Mon, Oct 12
            Water 1 plant

            Watering tips
            - Feel the soil first: if it's dry 2-3 cm down, water.
            - Water slowly until a little runs out of the bottom.
            - Empty saucers after about 15 minutes.
            - When in doubt, leave it dry.

            Kitchen
            - Fern

            Please don't water these (2)

            Bedroom
            - Cactus (Echinocactus)
            - Aloe

            Made with YAPT · github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker
            """.trimIndent(),
            render(list)
        )
    }

    @Test
    fun `the resting section is omitted when nothing rests`() {
        val list = SitterList(visitDay, listOf(SitterListRoom("A", listOf(plant(1, "Fern")))), emptyList())

        assertFalse(render(list).contains("don't water"))
    }

    @Test
    fun `the date carries the year only when it differs from this year`() {
        val nextYear = SitterList(
            LocalDate.of(2027, 1, 4),
            listOf(SitterListRoom("A", listOf(plant(1, "Fern")))),
            emptyList()
        )

        assertEquals("Plants to water on Mon, Jan 4, 2027", render(nextYear).lines().first())
        assertEquals(Locale.US, Locale.getDefault())
    }

    @Test
    fun `the tips come before the first room and the branding line is last`() {
        val list = SitterList(visitDay, listOf(SitterListRoom("A", listOf(plant(1, "Fern")))), emptyList())

        val lines = render(list).lines()

        assertTrue(lines.indexOf("Watering tips") in 1 until lines.indexOf("A"))
        assertEquals(4, lines.count { it.startsWith("- ") && it.indexOf("Fern") < 0 })
        assertEquals("Made with YAPT · github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker", lines.last())
    }

    @Test
    fun `the branding line follows the resting plants`() {
        val list = SitterList(
            visitDay,
            listOf(SitterListRoom("A", listOf(plant(1, "Fern")))),
            listOf(SitterListRoom("B", listOf(plant(2, "Cactus"))))
        )

        val lines = render(list).lines()

        assertEquals("- Cactus", lines[lines.size - 3])
        assertEquals("Made with YAPT · github.com/LocNgu/YAPT-Yet-Another-Plant-Tracker", lines.last())
    }
}
