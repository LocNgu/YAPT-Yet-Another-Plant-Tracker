package com.yapt.planttracker.ui.screens.repotting

import org.junit.Assert.assertEquals
import org.junit.Test

class RepottingMenuActionTest {

    @Test
    fun `an unplanned row offers Repot and Plan repot`() {
        assertEquals(
            listOf(RepottingMenuAction.REPOT, RepottingMenuAction.PLAN_REPOT),
            repottingMenuActions(isPlanned = false)
        )
    }

    @Test
    fun `a planned row offers Repot, Change plan and Clear plan`() {
        assertEquals(
            listOf(RepottingMenuAction.REPOT, RepottingMenuAction.CHANGE_PLAN, RepottingMenuAction.CLEAR_PLAN),
            repottingMenuActions(isPlanned = true)
        )
    }
}
