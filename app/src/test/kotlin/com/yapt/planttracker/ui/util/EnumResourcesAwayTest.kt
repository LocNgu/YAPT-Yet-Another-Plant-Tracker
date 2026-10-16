package com.yapt.planttracker.ui.util

import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import org.junit.Assert.assertEquals
import org.junit.Test

class EnumResourcesAwayTest {

    @Test
    fun `AWAY_EXCLUDED has its own label for Recent adjustments`() {
        assertEquals(R.string.adjustment_trigger_away_excluded, WateringAdjustmentTrigger.AWAY_EXCLUDED.labelRes())
    }
}
