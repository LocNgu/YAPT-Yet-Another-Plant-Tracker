package com.yapt.planttracker.ui.screens.today

import androidx.compose.ui.unit.dp
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayTaskBucket
import com.yapt.planttracker.domain.today.careTypeSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test
    fun `a 320dp phone fits six slots of at least 48dp across the 296dp grid width`() {
        val slot = wateredTodaySlotWidth(296.dp)

        assertTrue(slot >= 48.dp)
        assertTrue(slot * 6 <= 296.dp)
    }

    @Test
    fun `the slot size is capped on wide screens and never drops below the touch target`() {
        assertEquals(72.dp, wateredTodaySlotWidth(840.dp))
        assertEquals(48.dp, wateredTodaySlotWidth(200.dp))
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
