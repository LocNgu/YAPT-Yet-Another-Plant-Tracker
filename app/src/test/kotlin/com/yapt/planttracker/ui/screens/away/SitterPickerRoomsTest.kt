package com.yapt.planttracker.ui.screens.away

import com.yapt.planttracker.domain.away.ProjectedPlant
import com.yapt.planttracker.domain.away.VisitProjection
import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SitterPickerRoomsTest {

    private val day = LocalDate.of(2026, 11, 2)

    private fun plant(id: Long, room: String?) = Plant(
        id = id,
        name = "Plant $id",
        room = room,
        wateringIntervalDays = 5,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun projected(plant: Plant) = ProjectedPlant(plant, day, 5, missedLastTime = false, longerThanUsual = false)

    private fun projection(
        listed: List<Plant> = emptyList(),
        notListed: List<Plant> = emptyList(),
        resting: List<Plant> = emptyList()
    ) = VisitProjection(
        visitDay = day,
        gapDays = 7,
        includesDueBy = day.plusDays(3),
        listed = listed.map(::projected),
        resting = resting,
        notListed = notListed.map(::projected)
    )

    @Test
    fun `rooms come from all three sections, alphabetical and case-insensitive`() {
        val rooms = sitterPickerRooms(
            projection(
                listed = listOf(plant(1, "study")),
                notListed = listOf(plant(2, "Bedroom"), plant(3, "Study")),
                resting = listOf(plant(4, "attic"))
            )
        )

        assertEquals(listOf("attic", "Bedroom", "study"), rooms.chips.map { it.label })
        assertEquals(listOf("attic", "bedroom", "study"), rooms.chips.map { it.key })
        assertFalse(rooms.hasNoRoom)
    }

    @Test
    fun `no room is offered only when a plant lacks a room, blank counting as none`() {
        assertFalse(sitterPickerRooms(projection(listed = listOf(plant(1, "Kitchen")))).hasNoRoom)
        assertTrue(sitterPickerRooms(projection(listed = listOf(plant(1, "Kitchen"), plant(2, null)))).hasNoRoom)
        assertTrue(sitterPickerRooms(projection(resting = listOf(plant(1, "  ")))).hasNoRoom)
    }

    @Test
    fun `rooms with a ticked plant are keyed like the chips`() {
        val selected = projection(listed = listOf(plant(1, " Kitchen "), plant(2, null)))

        assertEquals(setOf("kitchen", NO_ROOM_FILTER), sitterRoomsWithSelection(selected))
    }

    @Test
    fun `a filter matches its own room only and All matches everything`() {
        val kitchen = plant(1, "Kitchen")

        assertTrue(kitchen.inRoomFilter(null))
        assertTrue(kitchen.inRoomFilter("kitchen"))
        assertFalse(kitchen.inRoomFilter("study"))
        assertFalse(kitchen.inRoomFilter(NO_ROOM_FILTER))
        assertTrue(plant(2, "").inRoomFilter(NO_ROOM_FILTER))
    }

    @Test
    fun `a filter whose room has disappeared falls back to All`() {
        val rooms = sitterPickerRooms(projection(listed = listOf(plant(1, "Kitchen"))))

        assertEquals("kitchen", rooms.resolve("kitchen"))
        assertNull(rooms.resolve("study"))
        assertNull(rooms.resolve(NO_ROOM_FILTER))
        assertNull(rooms.resolve(null))
    }
}
