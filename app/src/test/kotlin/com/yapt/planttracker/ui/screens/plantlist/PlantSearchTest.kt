package com.yapt.planttracker.ui.screens.plantlist

import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantSearchTest {

    private fun plant(name: String, species: String? = null, room: String? = null) = Plant(
        name = name,
        species = species,
        room = room,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Test
    fun `blank query matches every plant`() {
        assertTrue(matchesSearchQuery(plant("Monstera"), ""))
        assertTrue(matchesSearchQuery(plant("Monstera"), "   "))
    }

    @Test
    fun `matches a case-insensitive substring of the name`() {
        assertTrue(matchesSearchQuery(plant("Monstera Deliciosa"), "monstera"))
        assertTrue(matchesSearchQuery(plant("Monstera Deliciosa"), "DELICIOSA"))
        assertFalse(matchesSearchQuery(plant("Monstera Deliciosa"), "fern"))
    }

    @Test
    fun `matches a substring of the species when the name does not match`() {
        assertTrue(matchesSearchQuery(plant("Fig", species = "Ficus lyrata"), "lyrata"))
    }

    @Test
    fun `null species never matches`() {
        assertFalse(matchesSearchQuery(plant("Fig", species = null), "lyrata"))
    }

    @Test
    fun `room text alone does not match`() {
        assertFalse(matchesSearchQuery(plant("Fig", room = "Kitchen Windowsill"), "windowsill"))
    }

    @Test
    fun `query is trimmed before matching`() {
        assertTrue(matchesSearchQuery(plant("Monstera"), "  monstera  "))
    }

    @Test
    fun `accent-folded query matches an accented name`() {
        assertTrue(matchesSearchQuery(plant("Grünlilie"), "grun"))
    }

    @Test
    fun `accent-folded name matches an accented query`() {
        assertTrue(matchesSearchQuery(plant("Grunlilie"), "grün"))
    }

    @Test
    fun `characters without a decomposition match themselves literally, unfolded`() {
        assertTrue(matchesSearchQuery(plant("Weiße Fahne"), "weiße"))
        assertFalse(matchesSearchQuery(plant("Weisse Fahne"), "weiße"))
    }
}
