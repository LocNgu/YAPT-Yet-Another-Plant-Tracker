package com.yapt.planttracker.ui.screens.addplant

import com.yapt.planttracker.domain.schedule.DormancyWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeciesPresetMatcherTest {

    private val names = mapOf(
        "monstera" to "Monstera",
        "pothos" to "Pothos",
        "calathea" to "Calathea",
        "peace_lily" to "Peace lily",
        "boston_fern" to "Boston fern",
        "heartleaf_philodendron" to "Heartleaf philodendron",
        "wax_plant" to "Wax plant"
    )

    private fun ids(query: String) = matchSpeciesPresets(query) { names[it.id] ?: it.id }.map { it.id }

    @Test
    fun `matching ignores case`() {
        assertEquals(listOf("pothos"), ids("POTH"))
    }

    @Test
    fun `matching ignores accents on both sides`() {
        assertEquals(listOf("calathea"), ids("cálathéa"))
        val accented = matchSpeciesPresets("peace lily") { if (it.id == "peace_lily") "Pèace lïly" else it.id }
        assertEquals(listOf("peace_lily"), accented.map { it.id })
    }

    @Test
    fun `botanical names match too`() {
        assertEquals(listOf("pothos"), ids("epipremnum"))
        assertTrue("monstera" in ids("deliciosa"))
    }

    @Test
    fun `names starting with the query come first`() {
        val result = ids("phil")
        assertEquals("heartleaf_philodendron", result.first())
    }

    @Test
    fun `no match, blank or one character gives nothing`() {
        assertTrue(ids("zzzz").isEmpty())
        assertTrue(ids("   ").isEmpty())
        assertTrue(ids("m").isEmpty())
    }

    @Test
    fun `an exact botanical name hides the suggestions`() {
        assertTrue(ids("Monstera deliciosa").isEmpty())
    }

    @Test
    fun `suggestions are capped`() {
        assertTrue(ids("a").isEmpty())
        assertTrue(matchSpeciesPresets("an") { it.id }.size <= 5)
    }

    @Test
    fun `every bundled preset has values the form can hold`() {
        val ids = SpeciesPresets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertTrue(SpeciesPresets.all.size in 15..25)
        SpeciesPresets.all.forEach { p ->
            assertTrue(p.id, p.wateringBaseIntervalDays in WATERING_SLIDER_RANGE)
            assertTrue(p.id, p.fertilizingIntervalDays == null || p.fertilizingIntervalDays in 7..180)
            assertTrue(p.id, p.fertilizingSeasons.isNotEmpty())
            p.dormancy?.let {
                assertTrue(p.id, it.startMonth in 1..12 && it.endMonth in 1..12)
                val cadence = it.dormantWateringIntervalDays
                assertTrue(p.id, cadence == null || DormancyWindow.validWateringInterval(cadence) != null)
            }
        }
    }
}
