package com.yapt.planttracker.ui.screens.plantdetail

import com.yapt.planttracker.domain.model.PotMaterial

/** The values the repot prompt starts with: the plant's current pot facts (#1003). */
data class PotPrompt(val material: PotMaterial?, val diameterCm: Int?)
