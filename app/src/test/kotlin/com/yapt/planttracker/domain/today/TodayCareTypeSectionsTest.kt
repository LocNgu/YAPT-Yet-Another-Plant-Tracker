package com.yapt.planttracker.domain.today

import com.yapt.planttracker.domain.model.Plant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class TodayCareTypeSectionsTest {

    private val today = LocalDate.of(2026, 9, 27)
    private val zone = ZoneId.systemDefault()

    @Test
    fun `care type sections order watering first and hide empty sections`() {
        val fern = plant()
        val tasks = listOf(
            task("photo:1", fern, TodayCareKind.PHOTO, TodayTaskBucket.Today),
            task("repot:1", fern, TodayCareKind.REPOT, TodayTaskBucket.Today),
            task("custom:1", fern, TodayCareKind.CUSTOM_REMINDER, TodayTaskBucket.Today),
            task("fertilize:1", fern, TodayCareKind.FERTILIZE, TodayTaskBucket.Today),
            task("issue:1", fern, TodayCareKind.ISSUE_TREATMENT, TodayTaskBucket.Today),
            task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Overdue)
        )

        val sections = careTypeSections(tasks)

        assertEquals(
            listOf(
                TodayCareSection.WATERING,
                TodayCareSection.ISSUE_TREATMENTS,
                TodayCareSection.FERTILIZING,
                TodayCareSection.CUSTOM_REMINDERS,
                TodayCareSection.REPOTTING,
                TodayCareSection.PHOTOS
            ),
            sections.map { it.section }
        )
        assertEquals(
            listOf(TodayCareSection.WATERING, TodayCareSection.PHOTOS),
            careTypeSections(tasks.filter { it.kind == TodayCareKind.WATER || it.kind == TodayCareKind.PHOTO })
                .map { it.section }
        )
        assertTrue(careTypeSections(emptyList()).isEmpty())
    }

    @Test
    fun `care type sections keep every task once and preserve queue order within a section`() {
        val fern = plant(id = 1L, name = "Fern")
        val aloe = plant(id = 2L, name = "Aloe")
        val tasks = listOf(
            task("water:2", aloe, TodayCareKind.WATER, TodayTaskBucket.Overdue),
            task("fertilize:1", fern, TodayCareKind.FERTILIZE, TodayTaskBucket.Today),
            task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Today),
            task("custom:1", fern, TodayCareKind.CUSTOM_REMINDER, TodayTaskBucket.Today)
        )

        val sections = careTypeSections(tasks)

        assertEquals(tasks.map { it.id }.sorted(), sections.flatMap { it.tasks }.map { it.id }.sorted())
        assertEquals(
            listOf("water:2", "water:1"),
            sections.filter { it.section == TodayCareSection.WATERING }.flatMap { it.tasks }.map { it.id }
        )
    }

    @Test
    fun `combined water and fertilize task sits under watering only`() {
        val fern = plant()
        val combined = task("water_fertilize:1", fern, TodayCareKind.WATER_AND_FERTILIZE, TodayTaskBucket.Today)

        val sections = careTypeSections(listOf(combined))

        assertEquals(listOf(TodayCareSection.WATERING), sections.map { it.section })
        assertEquals(listOf(combined), sections.single().tasks)
    }

    @Test
    fun `watering splits into flat overdue today and tomorrow entries and hides empty ones`() {
        val fern = plant(id = 1L, name = "Fern")
        val aloe = plant(id = 2L, name = "Aloe")
        val cactus = plant(id = 3L, name = "Cactus")
        val tasks = listOf(
            task("water:3", cactus, TodayCareKind.WATER, TodayTaskBucket.Upcoming(today.plusDays(1).toEpochDay())),
            task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Overdue),
            task("water:2", aloe, TodayCareKind.WATER_AND_FERTILIZE, TodayTaskBucket.Today)
        )

        val sections = careTypeSections(tasks)

        assertEquals(
            listOf(TodayWateringGroup.OVERDUE, TodayWateringGroup.TODAY, TodayWateringGroup.TOMORROW),
            sections.map { it.wateringGroup }
        )
        assertTrue(sections.all { it.section == TodayCareSection.WATERING })
        assertEquals(listOf("water:1"), sections[0].tasks.map { it.id })
        assertEquals(listOf("water:2"), sections[1].tasks.map { it.id })
        assertEquals(listOf("water:3"), sections[2].tasks.map { it.id })

        val withoutToday = careTypeSections(tasks.filter { it.bucket != TodayTaskBucket.Today })

        assertEquals(
            listOf(TodayWateringGroup.OVERDUE, TodayWateringGroup.TOMORROW),
            withoutToday.map { it.wateringGroup }
        )
    }

    @Test
    fun `entries come in the full order with every watering bucket before the other sections`() {
        val fern = plant()
        val tomorrow = TodayTaskBucket.Upcoming(today.plusDays(1).toEpochDay())
        val tasks = listOf(
            task("photo:1", fern, TodayCareKind.PHOTO, TodayTaskBucket.Today),
            task("repot:1", fern, TodayCareKind.REPOT, TodayTaskBucket.Today),
            task("custom:1", fern, TodayCareKind.CUSTOM_REMINDER, TodayTaskBucket.Today),
            task("fertilize:1", fern, TodayCareKind.FERTILIZE, TodayTaskBucket.Overdue),
            task("issue:1", fern, TodayCareKind.ISSUE_TREATMENT, TodayTaskBucket.Today),
            task("water:1", fern, TodayCareKind.WATER, tomorrow),
            task("water:2", plant(id = 2L), TodayCareKind.WATER, TodayTaskBucket.Today),
            task("water:3", plant(id = 3L), TodayCareKind.WATER, TodayTaskBucket.Overdue)
        )

        val sections = careTypeSections(tasks)

        assertEquals(
            listOf(
                TodayCareSection.WATERING to TodayWateringGroup.OVERDUE,
                TodayCareSection.WATERING to TodayWateringGroup.TODAY,
                TodayCareSection.WATERING to TodayWateringGroup.TOMORROW,
                TodayCareSection.ISSUE_TREATMENTS to null,
                TodayCareSection.FERTILIZING to null,
                TodayCareSection.CUSTOM_REMINDERS to null,
                TodayCareSection.REPOTTING to null,
                TodayCareSection.PHOTOS to null
            ),
            sections.map { it.section to it.wateringGroup }
        )
    }

    @Test
    fun `every entry has a stable key and watering buckets get their own`() {
        val fern = plant()
        val tomorrow = TodayTaskBucket.Upcoming(today.plusDays(1).toEpochDay())
        val tasks = listOf(
            task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Overdue),
            task("water:2", plant(id = 2L), TodayCareKind.WATER, TodayTaskBucket.Today),
            task("water:3", plant(id = 3L), TodayCareKind.WATER, tomorrow),
            task("fertilize:1", fern, TodayCareKind.FERTILIZE, TodayTaskBucket.Today),
            task("photo:1", fern, TodayCareKind.PHOTO, TodayTaskBucket.Today)
        )

        assertEquals(
            listOf("watering:OVERDUE", "watering:TODAY", "watering:TOMORROW", "section:FERTILIZING", "section:PHOTOS"),
            careTypeSections(tasks).map { it.key }
        )
    }

    @Test
    fun `a combined task sits in the entry for its watering bucket`() {
        val fern = plant()
        val tomorrow = TodayTaskBucket.Upcoming(today.plusDays(1).toEpochDay())
        val combined = task("water_fertilize:1", fern, TodayCareKind.WATER_AND_FERTILIZE, tomorrow)

        val section = careTypeSections(listOf(combined)).single()

        assertEquals(TodayWateringGroup.TOMORROW, section.wateringGroup)
        assertEquals(listOf(combined), section.tasks)
    }

    @Test
    fun `an upcoming bucket maps to tomorrow and queue order is kept inside an entry`() {
        val fern = plant(id = 1L, name = "Fern")
        val aloe = plant(id = 2L, name = "Aloe")
        val tomorrow = TodayTaskBucket.Upcoming(today.plusDays(1).toEpochDay())
        val tasks = listOf(
            task("water:2", aloe, TodayCareKind.WATER, tomorrow),
            task("water:1", fern, TodayCareKind.WATER, tomorrow)
        )

        val section = careTypeSections(tasks).single()

        assertEquals(TodayWateringGroup.TOMORROW, section.wateringGroup)
        assertEquals(listOf("water:2", "water:1"), section.tasks.map { it.id })
    }

    @Test
    fun `plant counts are distinct plants per entry`() {
        val fern = plant(id = 1L, name = "Fern")
        val aloe = plant(id = 2L, name = "Aloe")
        val tasks = listOf(
            task("water:1", fern, TodayCareKind.WATER, TodayTaskBucket.Overdue),
            task("water_fertilize:1", fern, TodayCareKind.WATER_AND_FERTILIZE, TodayTaskBucket.Overdue),
            task("water:2", aloe, TodayCareKind.WATER, TodayTaskBucket.Overdue),
            task("water:3", plant(id = 3L, name = "Cactus"), TodayCareKind.WATER, TodayTaskBucket.Today),
            task("custom:1", fern, TodayCareKind.CUSTOM_REMINDER, TodayTaskBucket.Today),
            task("custom:2", fern, TodayCareKind.CUSTOM_REMINDER, TodayTaskBucket.Today),
            task("custom:3", aloe, TodayCareKind.CUSTOM_REMINDER, TodayTaskBucket.Today)
        )

        val sections = careTypeSections(tasks)
        val custom = sections.first { it.section == TodayCareSection.CUSTOM_REMINDERS }

        assertEquals(listOf(2, 1), sections.filter { it.section == TodayCareSection.WATERING }.map { it.plantCount })
        assertEquals(3, custom.tasks.size)
        assertEquals(2, custom.plantCount)
    }

    @Test
    fun `only watering entries carry a watering group`() {
        val fern = plant()
        val tasks = TodayCareKind.entries.mapIndexed { index, kind ->
            task("$kind:$index", fern, kind, if (index % 2 == 0) TodayTaskBucket.Overdue else TodayTaskBucket.Today)
        }

        val sections = careTypeSections(tasks)

        assertTrue(sections.filter { it.section == TodayCareSection.WATERING }.all { it.wateringGroup != null })
        assertTrue(sections.filter { it.section != TodayCareSection.WATERING }.all { it.wateringGroup == null })
    }

    private fun plant(id: Long = 1L, name: String = "Plant") = Plant(
        id = id,
        name = name,
        createdAt = millis(today.minusDays(60)),
        updatedAt = millis(today.minusDays(60))
    )

    private fun task(id: String, plant: Plant, kind: TodayCareKind, bucket: TodayTaskBucket) = TodayCareTask(
        id = id,
        plant = plant,
        kind = kind,
        dueAt = millis(today),
        bucket = bucket
    )

    private fun millis(date: LocalDate): Long = date.atStartOfDay(zone).toInstant().toEpochMilli()
}
