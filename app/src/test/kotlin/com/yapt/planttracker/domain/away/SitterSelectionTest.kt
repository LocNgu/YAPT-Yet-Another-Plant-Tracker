package com.yapt.planttracker.domain.away

import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** The owner's edits to a suggested sitter list (product ADR-0077). */
class SitterSelectionTest {

    private val visitDay = LocalDate.of(2026, 10, 12)

    private fun plant(id: Long, name: String = "Plant $id") = Plant(
        id = id,
        name = name,
        wateringIntervalDays = 7,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun projected(id: Long, due: LocalDate, name: String = "Plant $id", missed: Boolean = false) =
        ProjectedPlant(plant(id, name), due, usualIntervalDays = 7, missedLastTime = missed, longerThanUsual = true)

    private val projection = VisitProjection(
        visitDay = visitDay,
        gapDays = 7,
        includesDueBy = visitDay.plusDays(3),
        listed = listOf(projected(1, visitDay), projected(2, visitDay.plusDays(2))),
        resting = listOf(plant(5, "Rest"), plant(6, "Sleep")),
        notListed = listOf(projected(3, visitDay.plusDays(9)), projected(4, visitDay.plusDays(20))),
        restingAsListed = mapOf(5L to ProjectedPlant(plant(5, "Rest"), visitDay, 14, true, false))
    )

    private fun VisitProjection.listedIds() = listed.map { it.plant.id }
    private fun VisitProjection.notListedIds() = notListed.map { it.plant.id }

    @Test
    fun `no edits leave the suggestion as it is`() {
        assertEquals(projection, projection.applySelection(SitterSelection()))
    }

    @Test
    fun `unticking a listed plant moves it to not listed`() {
        val result = projection.applySelection(SitterSelection(removed = setOf(1L)))

        assertEquals(listOf(2L), result.listedIds())
        assertEquals(listOf(1L, 3L, 4L), result.notListedIds())
    }

    @Test
    fun `adding a not listed plant lists it with its own due date`() {
        val result = projection.applySelection(SitterSelection(added = setOf(3L)))

        assertEquals(listOf(1L, 2L, 3L), result.listedIds())
        assertEquals(visitDay.plusDays(9), result.listed.last().dueDate)
        assertEquals(listOf(4L), result.notListedIds())
    }

    @Test
    fun `an added resting plant moves onto the list due on the visit day and off the resting page`() {
        val result = projection.applySelection(SitterSelection(added = setOf(5L)))

        assertEquals(listOf(1L, 5L, 2L), result.listedIds())
        val moved = result.listed.first { it.plant.id == 5L }
        assertEquals(visitDay, moved.dueDate)
        assertEquals(14, moved.usualIntervalDays)
        assertTrue(moved.missedLastTime)
        assertEquals(false, moved.longerThanUsual)
        assertEquals(listOf(6L), result.resting.map { it.id })
    }

    @Test
    fun `an added resting plant without carried details still lists`() {
        val result = projection.applySelection(SitterSelection(added = setOf(6L)))

        val moved = result.listed.first { it.plant.id == 6L }
        assertEquals(visitDay, moved.dueDate)
        assertEquals(null, moved.usualIntervalDays)
        assertEquals(false, moved.missedLastTime)
    }

    @Test
    fun `overrides that no longer matter and unknown ids are ignored`() {
        val result = projection.applySelection(
            SitterSelection(added = setOf(1L, 99L), removed = setOf(3L, 5L, 98L))
        )

        assertEquals(projection, result)
    }

    @Test
    fun `listed is ordered by due date, then lowercase name, then id`() {
        val base = projection.copy(
            listed = listOf(projected(1, visitDay, "pear")),
            notListed = listOf(
                projected(3, visitDay, "Apple"),
                projected(4, visitDay, "apple"),
                projected(2, visitDay.minusDays(1), "zed")
            )
        )

        val result = base.applySelection(SitterSelection(added = setOf(2L, 3L, 4L)))

        assertEquals(listOf(2L, 3L, 4L, 1L), result.listedIds())
    }

    @Test
    fun `seeding from a saved list reproduces it`() {
        val seeded = seedSelection(projection, saved = setOf(2L, 3L, 6L))

        assertEquals(SitterSelection(added = setOf(3L, 6L), removed = setOf(1L)), seeded)
        assertEquals(setOf(2L, 3L, 6L), projection.applySelection(seeded).listedIds().toSet())
    }

    @Test
    fun `seeding ignores saved plants the projection does not know`() {
        val seeded = seedSelection(projection, saved = setOf(1L, 2L, 77L))

        assertEquals(SitterSelection(), seeded)
        assertEquals(setOf(1L, 2L), projection.applySelection(seeded).listedIds().toSet())
    }

    @Test
    fun `seeding round trips to the known part of any saved set`() {
        val known = setOf(1L, 2L, 3L, 4L, 5L, 6L)
        val saves = listOf(emptySet(), setOf(1L), setOf(4L, 5L), setOf(1L, 2L, 3L, 4L, 5L, 6L, 100L), setOf(100L))

        for (saved in saves) {
            val result = projection.applySelection(seedSelection(projection, saved))
            assertEquals(saved intersect known, result.listedIds().toSet())
        }
    }
}
