package com.yapt.planttracker.ui.screens.today

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayTaskBucket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CareMenuActionTest {

    @Test
    fun `each task kind offers exactly its valid quick actions`() {
        assertEquals(
            listOf(CareMenuAction.WATER, CareMenuAction.RESCHEDULE),
            careMenuActions(TodayCareKind.WATER)
        )
        assertEquals(
            listOf(CareMenuAction.WATER_AND_FERTILIZE, CareMenuAction.RESCHEDULE),
            careMenuActions(TodayCareKind.WATER_AND_FERTILIZE)
        )
        assertEquals(listOf(CareMenuAction.FERTILIZE), careMenuActions(TodayCareKind.FERTILIZE))
        assertEquals(listOf(CareMenuAction.REPOT), careMenuActions(TodayCareKind.REPOT))
        assertEquals(listOf(CareMenuAction.MARK_DONE), careMenuActions(TodayCareKind.CUSTOM_REMINDER))
        assertEquals(listOf(CareMenuAction.MARK_DONE), careMenuActions(TodayCareKind.ISSUE_TREATMENT))
        assertEquals(listOf(CareMenuAction.TAKE_PHOTO), careMenuActions(TodayCareKind.PHOTO))
    }

    @Test
    fun `only watering kinds can be rescheduled`() {
        val reschedulable = TodayCareKind.entries.filter { CareMenuAction.RESCHEDULE in careMenuActions(it) }

        assertEquals(listOf(TodayCareKind.WATER, TodayCareKind.WATER_AND_FERTILIZE), reschedulable)
    }

    @Test
    fun `every menu entry routes to the handler the removed inline button used`() {
        val calls = mutableListOf<String>()
        val actions = TodayTaskActions(
            onOpen = { calls += "open" },
            onComplete = { calls += "complete" },
            onReschedule = { calls += "reschedule" },
            onRepot = { calls += "repot" },
            onPhoto = { calls += "photo" }
        )
        val task = TodayCareTask(
            id = "water:1",
            plant = Plant(id = 1L, name = "Fern", createdAt = 0L, updatedAt = 0L),
            kind = TodayCareKind.WATER,
            dueAt = 0L,
            bucket = TodayTaskBucket.Today
        )

        for (action in CareMenuAction.entries) actions.perform(action, task)

        assertEquals(
            listOf("complete", "complete", "complete", "reschedule", "repot", "complete", "photo"),
            calls
        )
        assertTrue("open" !in calls)
    }
}
