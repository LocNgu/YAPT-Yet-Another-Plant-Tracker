package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The sitter list structure, its summary card and its PDF page plan (#899, product ADR-0065). */
class SitterListTest {

    private val visitDay = LocalDate.of(2026, 10, 12)

    @Suppress("LongParameterList")
    private fun plant(
        id: Long,
        name: String = "Plant $id",
        species: String? = null,
        room: String? = null,
        photo: String? = "content://photo/$id"
    ) = Plant(
        id = id,
        name = name,
        species = species,
        room = room,
        coverPhotoUri = photo,
        wateringIntervalDays = 7,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun listed(plant: Plant, missed: Boolean = false, longer: Boolean = false) =
        ProjectedPlant(plant, visitDay, usualIntervalDays = 7, missedLastTime = missed, longerThanUsual = longer)

    private fun projection(listed: List<ProjectedPlant>, resting: List<Plant> = emptyList()) =
        VisitProjection(visitDay, gapDays = 7, includesDueBy = visitDay.plusDays(3), listed = listed, resting = resting)

    private fun compose(listed: List<ProjectedPlant>, resting: List<Plant> = emptyList()) =
        SitterListComposer.compose(projection(listed, resting))

    @Test
    fun `rooms are alphabetical ignoring case with No room last`() {
        val list = compose(
            listOf(
                listed(plant(1, room = "kitchen")),
                listed(plant(2, room = null)),
                listed(plant(3, room = "Bedroom")),
                listed(plant(4, room = "  ")),
                listed(plant(5, room = "Living room"))
            )
        )

        assertEquals(listOf("Bedroom", "kitchen", "Living room", null), list.rooms.map { it.room })
        assertEquals(listOf(2L, 4L), list.rooms.last().plants.map { it.plantId })
    }

    @Test
    fun `rooms that differ only by case are one room`() {
        val list = compose(listOf(listed(plant(1, room = "Kitchen")), listed(plant(2, room = "kitchen"))))

        assertEquals(1, list.rooms.size)
        assertEquals(2, list.rooms.single().plants.size)
    }

    @Test
    fun `plants sort by name inside a room`() {
        val list = compose(
            listOf(
                listed(plant(1, "Fern", room = "A")),
                listed(plant(2, "basil", room = "A")),
                listed(plant(3, "Aloe", room = "A"))
            )
        )

        assertEquals(listOf("Aloe", "basil", "Fern"), list.rooms.single().plants.map { it.name })
    }

    @Test
    fun `species shows only when set and different from the name`() {
        val list = compose(
            listOf(
                listed(plant(1, "Monstera", species = "Monstera deliciosa", room = "A")),
                listed(plant(2, "Pothos", species = "pothos", room = "A")),
                listed(plant(3, "Basil", species = "  ", room = "A")),
                listed(plant(4, "Cactus", species = null, room = "A"))
            )
        )

        val species = list.rooms.single().plants.associate { it.name to it.species }
        assertEquals("Monstera deliciosa", species["Monstera"])
        assertNull(species["Pothos"])
        assertNull(species["Basil"])
        assertNull(species["Cactus"])
    }

    @Test
    fun `a plant without a photo keeps a null cover and a missed plant is tagged`() {
        val list = compose(listOf(listed(plant(1, photo = null), missed = true), listed(plant(2))))

        val plants = list.rooms.single().plants.associateBy { it.plantId }
        assertNull(plants.getValue(1L).coverPhotoUri)
        assertTrue(plants.getValue(1L).missedLastTime)
        assertEquals("content://photo/2", plants.getValue(2L).coverPhotoUri)
        assertEquals(false, plants.getValue(2L).missedLastTime)
    }

    @Test
    fun `resting plants form their own rooms and the counts add up`() {
        val list = compose(
            listed = listOf(listed(plant(1, room = "A"))),
            resting = listOf(plant(2, room = "B"), plant(3, room = "B"), plant(4))
        )

        assertEquals(1, list.plantCount)
        assertEquals(3, list.restingCount)
        assertEquals(listOf("B", null), list.restingRooms.map { it.room })
    }

    @Test
    fun `the summary counts plants per room and resting plants`() {
        val summary = projection(
            listOf(listed(plant(1, room = "Kitchen")), listed(plant(2, room = "Kitchen")), listed(plant(3))),
            resting = listOf(plant(4), plant(5))
        ).summary()

        assertEquals(3, summary.plantCount)
        assertEquals(listOf(RoomCount("Kitchen", 2), RoomCount(null, 1)), summary.roomCounts)
        assertEquals(2, summary.restingCount)
    }

    @Test
    fun `the summary names missed, longer, no photo and look-alike plants`() {
        val twinA = plant(1, "Basil", room = "Kitchen", photo = null)
        val twinB = plant(2, "basil", room = "kitchen", photo = null)
        val otherRoom = plant(3, "Basil", room = "Bedroom", photo = null)
        val withPhoto = plant(4, "Fern", room = "Kitchen")
        val summary = projection(
            listOf(
                listed(twinA, missed = true, longer = true),
                listed(twinB),
                listed(otherRoom),
                listed(withPhoto, longer = true)
            )
        ).summary()

        assertEquals(listOf(1L), summary.missedLastTime.map { it.id })
        assertEquals(listOf(1L, 4L), summary.longerThanUsual.map { it.plant.id })
        assertEquals(listOf(1L, 2L, 3L), summary.noPhoto.map { it.id })
        // Same name and room as another listed plant, and no photo: the two kitchen basils, not the bedroom one.
        assertEquals(listOf(1L, 2L), summary.lookAlikes.map { it.id })
    }

    @Test
    fun `a look-alike with a photo is not flagged`() {
        val summary = projection(
            listOf(listed(plant(1, "Basil", room = "A")), listed(plant(2, "Basil", room = "A")))
        ).summary()

        assertTrue(summary.lookAlikes.isEmpty())
        assertTrue(summary.noPhoto.isEmpty())
    }

    private fun roomOf(count: Int, room: String, firstId: Long = 1L) =
        (0 until count).map { listed(plant(firstId + it, room = room)) }

    private val rowHeight = SitterPdfLayout.TILE_HEIGHT + SitterPdfLayout.ROW_GAP

    @Test
    fun `a small list fits one visit page and nothing rests so there is no resting page`() {
        val pages = SitterPdfLayout.paginate(compose(roomOf(4, "Kitchen")))

        assertEquals(1, pages.size)
        assertEquals(SitterPdfLayout.PageKind.VISIT, pages.single().kind)
        assertEquals(
            listOf(SitterPdfLayout.Block.RoomHeader("Kitchen", continued = false)),
            pages.single().blocks.filterIsInstance<SitterPdfLayout.Block.RoomHeader>()
        )
        val rows = pages.single().blocks.filterIsInstance<SitterPdfLayout.Block.TileRow>()
        assertEquals(listOf(3, 1), rows.map { it.plants.size })
    }

    @Test
    fun `tiles fill rows of three`() {
        val rows = SitterPdfLayout.paginate(compose(roomOf(7, "A"))).single().blocks
            .filterIsInstance<SitterPdfLayout.Block.TileRow>()

        assertEquals(listOf(3, 3, 1), rows.map { it.plants.size })
    }

    @Test
    fun `a room that overflows a page continues on the next with a continued header`() {
        val rowsPerPage = (SitterPdfLayout.BODY_HEIGHT - SitterPdfLayout.ROOM_HEADER_HEIGHT) / rowHeight
        val plants = roomOf((rowsPerPage + 1) * SitterPdfLayout.COLUMNS, "Big")

        val pages = SitterPdfLayout.paginate(compose(plants))

        assertEquals(2, pages.size)
        assertEquals(
            SitterPdfLayout.Block.RoomHeader("Big", continued = true),
            pages[1].blocks.first()
        )
        assertEquals(1, pages[1].blocks.filterIsInstance<SitterPdfLayout.Block.TileRow>().size)
    }

    @Test
    fun `a new room that does not fit under its header starts the next page`() {
        val rowsPerPage = (SitterPdfLayout.BODY_HEIGHT - SitterPdfLayout.ROOM_HEADER_HEIGHT) / rowHeight
        val filling = roomOf(rowsPerPage * SitterPdfLayout.COLUMNS, "A")
        val next = roomOf(1, "B", firstId = 100L)

        val pages = SitterPdfLayout.paginate(compose(filling + next))

        assertEquals(2, pages.size)
        assertEquals(SitterPdfLayout.Block.RoomHeader("B", continued = false), pages[1].blocks.first())
    }

    @Test
    fun `resting plants go on their own pages after the visit pages`() {
        val pages = SitterPdfLayout.paginate(
            compose(roomOf(2, "Kitchen"), resting = listOf(plant(50, room = "Bedroom"), plant(51)))
        )

        assertEquals(listOf(SitterPdfLayout.PageKind.VISIT, SitterPdfLayout.PageKind.RESTING), pages.map { it.kind })
        assertEquals(
            listOf("Bedroom", null),
            pages.last().blocks.filterIsInstance<SitterPdfLayout.Block.RoomHeader>().map { it.room }
        )
    }

    @Test
    fun `an empty list has no pages`() {
        assertTrue(SitterPdfLayout.paginate(compose(emptyList())).isEmpty())
    }
}
