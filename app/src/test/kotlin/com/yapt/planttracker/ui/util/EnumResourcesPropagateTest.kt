package com.yapt.planttracker.ui.util

import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EnumResourcesPropagateTest {

    @Test
    fun `PROPAGATE has its own label and icon`() {
        assertEquals(R.string.care_type_propagated, CareType.PROPAGATE.labelRes())
        assertNotEquals(CareType.PRUNE.icon(), CareType.PROPAGATE.icon())
        assertNotEquals(CareType.REPOT.icon(), CareType.PROPAGATE.icon())
    }

    @Test
    fun `PROPAGATE_CONFIDENCE_DROP is the last trigger and has its own label`() {
        assertEquals(WateringAdjustmentTrigger.PROPAGATE_CONFIDENCE_DROP, WateringAdjustmentTrigger.entries.last())
        assertEquals(
            R.string.adjustment_trigger_propagate_confidence_drop,
            WateringAdjustmentTrigger.PROPAGATE_CONFIDENCE_DROP.labelRes()
        )
    }
}
