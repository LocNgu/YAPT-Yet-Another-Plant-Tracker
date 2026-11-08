package com.yapt.planttracker.ui.components

import com.yapt.planttracker.domain.schedule.SeasonalAmplitude

/** What the strength control reports back; Pin and Custom are mutually exclusive by construction (#700). */
data class WateringStrengthCallbacks(
    val onSelectAppDefault: () -> Unit,
    val onSelectCustom: (SeasonalAmplitude) -> Unit,
    val onSelectPin: () -> Unit
)
