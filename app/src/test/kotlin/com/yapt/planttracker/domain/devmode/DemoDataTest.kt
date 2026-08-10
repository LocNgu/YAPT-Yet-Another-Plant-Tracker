package com.yapt.planttracker.domain.devmode

import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.schedule.CareSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class DemoDataTest {

    // 2023-11-14 22:13:20 UTC — same fixture value CareScheduleTest uses.
    private val now = 1_700_000_000_000L

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun dataset() = DemoData.generate(now)

    @Test
    fun `generate produces exactly 8 plants`() {
        assertEquals(8, dataset().plants.size)
    }

    @Test
    fun `generate is deterministic for the same now`() {
        val first = DemoData.generate(now)
        val second = DemoData.generate(now)
        assertEquals(first.plants.map { it.plant }, second.plants.map { it.plant })
        assertEquals(first.plants.map { it.careLogs }, second.plants.map { it.careLogs })
    }

    @Test
    fun `every plant name carries the Demo prefix`() {
        dataset().plants.forEach { seed ->
            assertTrue(seed.plant.name.startsWith(DemoData.NAME_PREFIX))
        }
    }

    @Test
    fun `rooms cover multiple rooms plus one unassigned plant`() {
        val rooms = dataset().plants.map { it.plant.room }
        assertEquals(setOf("Living Room", "Bedroom", "Kitchen", "Bathroom"), rooms.filterNotNull().toSet())
        assertEquals(1, rooms.count { it == null })
    }

    @Test
    fun `exactly one plant uses liquid fertilizer`() {
        val liquidPlants = dataset().plants.filter { it.plant.useLiquidFertilizer }
        assertEquals(1, liquidPlants.size)
        assertEquals("${DemoData.NAME_PREFIX}Pothos", liquidPlants.single().plant.name)
    }

    @Test
    fun `watering and fertilizing intervals cover scheduled and not-scheduled plants`() {
        val plants = dataset().plants.map { it.plant }
        assertEquals(7, plants.count { it.wateringIntervalDays != null })
        assertEquals(1, plants.count { it.wateringIntervalDays == null })
        assertEquals(5, plants.count { it.fertilizingIntervalDays != null })
        assertEquals(3, plants.count { it.fertilizingIntervalDays == null })
    }

    @Test
    fun `liquid-fertilizer plant pairs every FERTILIZE log with a WATER log at the same timestamp`() {
        val pothos = dataset().plants.single { it.plant.useLiquidFertilizer }
        val fertilizeTimestamps = pothos.careLogs.filter { it.careType == CareType.FERTILIZE }.map { it.loggedAt }
        val waterTimestamps = pothos.careLogs.filter { it.careType == CareType.WATER }.map { it.loggedAt }.toSet()

        assertTrue(fertilizeTimestamps.isNotEmpty())
        fertilizeTimestamps.forEach { assertTrue(it in waterTimestamps) }
    }

    @Test
    fun `Monstera plant is overdue for watering`() {
        val status = statusFor("Monstera Deliciosa")
        assertTrue(status.isOverdue)
        assertFalse(status.isDueSoon)
    }

    @Test
    fun `Snake Plant is due today for watering and overdue for fertilizing`() {
        val status = statusFor("Snake Plant")
        assertFalse(status.isOverdue)
        assertTrue(status.isDueSoon)
        assertTrue(status.isFertilizingOverdue)
    }

    @Test
    fun `Fiddle Leaf Fig is due in 2 days, not yet due`() {
        val status = statusFor("Fiddle Leaf Fig")
        assertFalse(status.isOverdue)
        assertFalse(status.isDueSoon)
        assertNotNull(status.nextWateringDueAt)
    }

    @Test
    fun `Pothos is overdue for watering and fertilizing`() {
        val status = statusFor("Pothos")
        assertTrue(status.isOverdue)
        assertTrue(status.isFertilizingOverdue)
    }

    @Test
    fun `Peace Lily is not yet due for watering`() {
        val status = statusFor("Peace Lily")
        assertFalse(status.isOverdue)
        assertFalse(status.isDueSoon)
    }

    @Test
    fun `Aloe Vera has no room and is overdue for watering`() {
        val seed = dataset().plants.single { it.plant.name == "${DemoData.NAME_PREFIX}Aloe Vera" }
        assertNull(seed.plant.room)
        val status = statusFor("Aloe Vera")
        assertTrue(status.isOverdue)
    }

    @Test
    fun `Cactus has no watering schedule`() {
        val status = statusFor("Cactus")
        assertNull(status.nextWateringDueAt)
        assertFalse(status.isOverdue)
        assertFalse(status.isDueSoon)
    }

    @Test
    fun `Calathea was never watered and is due today`() {
        val seed = dataset().plants.single { it.plant.name == "${DemoData.NAME_PREFIX}Calathea" }
        assertTrue(seed.careLogs.none { it.careType == CareType.WATER })

        val status = CareSchedule.computeStatus(
            plant = seed.plant,
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = seed.careLogs.size,
            now = now
        )
        assertFalse(status.isOverdue)
        assertTrue(status.isDueSoon)
        assertEquals(now, status.nextWateringDueAt)
    }

    private fun statusFor(plainName: String) = run {
        val seed = dataset().plants.single { it.plant.name == DemoData.NAME_PREFIX + plainName }
        val lastWateredAt = seed.careLogs.filter { it.careType == CareType.WATER }.maxOfOrNull { it.loggedAt }
        val lastFertilizedAt = seed.careLogs.filter { it.careType == CareType.FERTILIZE }.maxOfOrNull { it.loggedAt }
        CareSchedule.computeStatus(
            plant = seed.plant,
            lastWateredAt = lastWateredAt,
            lastFertilizedAt = lastFertilizedAt,
            totalLogs = seed.careLogs.size,
            now = now
        )
    }
}
