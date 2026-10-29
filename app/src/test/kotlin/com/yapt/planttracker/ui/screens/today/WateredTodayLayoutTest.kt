package com.yapt.planttracker.ui.screens.today

import androidx.compose.ui.unit.Density
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayTaskBucket
import com.yapt.planttracker.domain.today.careTypeSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

class WateredTodayLayoutTest {

    private fun task(id: Long, kind: TodayCareKind, bucket: TodayTaskBucket) = TodayCareTask(
        id = "${kind.name}:$id",
        plant = Plant(id = id, name = "Plant $id", createdAt = 0L, updatedAt = 0L),
        kind = kind,
        dueAt = 0L,
        bucket = bucket
    )

    private fun insertIndex(vararg tasks: TodayCareTask) = wateredTodayInsertIndex(careTypeSections(tasks.toList()))

    private val tomorrow = TodayTaskBucket.Upcoming(1L)

    @Test
    fun `the section is first when nothing is overdue or due today in watering`() {
        assertEquals(0, insertIndex(task(1, TodayCareKind.WATER, tomorrow)))
        assertEquals(0, insertIndex(task(1, TodayCareKind.FERTILIZE, TodayTaskBucket.Today)))
        assertEquals(0, insertIndex())
    }

    @Test
    fun `the section follows watering overdue and today and precedes watering tomorrow`() {
        assertEquals(
            1,
            insertIndex(
                task(1, TodayCareKind.WATER, TodayTaskBucket.Overdue),
                task(2, TodayCareKind.WATER, tomorrow)
            )
        )
        assertEquals(
            2,
            insertIndex(
                task(1, TodayCareKind.WATER, TodayTaskBucket.Overdue),
                task(2, TodayCareKind.WATER, TodayTaskBucket.Today),
                task(3, TodayCareKind.WATER, tomorrow),
                task(4, TodayCareKind.PHOTO, TodayTaskBucket.Today)
            )
        )
    }

    @Test
    fun `the section precedes every non-watering section and follows a combined task in today`() {
        assertEquals(
            1,
            insertIndex(
                task(1, TodayCareKind.WATER_AND_FERTILIZE, TodayTaskBucket.Today),
                task(2, TodayCareKind.CUSTOM_REMINDER, TodayTaskBucket.Today)
            )
        )
        assertEquals(
            0,
            insertIndex(
                task(1, TodayCareKind.ISSUE_TREATMENT, TodayTaskBucket.Overdue),
                task(2, TodayCareKind.REPOT, TodayTaskBucket.Today)
            )
        )
    }

    @Test
    fun `the section goes last when only watering overdue and today exist`() {
        assertEquals(
            2,
            insertIndex(
                task(1, TodayCareKind.WATER, TodayTaskBucket.Overdue),
                task(2, TodayCareKind.WATER, TodayTaskBucket.Today)
            )
        )
    }

    private fun slotPx(availablePx: Int, density: Float) =
        wateredTodaySlotWidthPx(availablePx, Density(density))

    @Test
    fun `a 320dp phone fits six slots of at least 48dp across the 296dp grid width`() {
        val slot = slotPx(availablePx = 296, density = 1f)

        assertTrue(slot >= 48)
        assertTrue(slot * 6 <= 296)
    }

    @Test
    fun `six slots never exceed the row width in pixels at any density or remainder`() {
        for (density in listOf(1f, 1.5f, 2f, 2.625f, 2.8125f, 3f, 3.5f)) {
            val floorPx = ceil(48f * density).toInt()
            for (availablePx in (floorPx * 6)..(floorPx * 6 + 600)) {
                val slot = slotPx(availablePx, density)

                assertTrue("density $density, row ${availablePx}px, slot ${slot}px", slot * 6 <= availablePx)
                assertTrue("density $density, row ${availablePx}px, slot ${slot}px", slot >= floorPx)
            }
        }
    }

    @Test
    fun `a 1012px row at density 2_8125 gets 168px slots rather than rounded-up 169px ones`() {
        assertEquals(168, slotPx(availablePx = 1012, density = 2.8125f))
        assertEquals(168, slotPx(availablePx = 1013, density = 2.8125f))
    }

    @Test
    fun `the slot size is capped on wide screens and never drops below the touch target`() {
        assertEquals(72, slotPx(availablePx = 840, density = 1f))
        assertEquals(48, slotPx(availablePx = 200, density = 1f))
        assertEquals(144, slotPx(availablePx = 1680, density = 2f))
    }

    @Test
    fun `five or fewer plants are never truncated and six or more are cut to five until expanded`() {
        assertEquals(5, wateredTodayPhotoCount(plantCount = 5, expanded = false))
        assertEquals(1, wateredTodayPhotoCount(plantCount = 1, expanded = false))
        assertEquals(5, wateredTodayPhotoCount(plantCount = 6, expanded = false))
        assertEquals(5, wateredTodayPhotoCount(plantCount = 12, expanded = false))
        assertEquals(12, wateredTodayPhotoCount(plantCount = 12, expanded = true))
        assertEquals(5, wateredTodayPhotoCount(plantCount = 5, expanded = true))
    }
}
