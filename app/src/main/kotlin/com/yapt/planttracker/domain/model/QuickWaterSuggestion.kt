package com.yapt.planttracker.domain.model

/** Carries a watering-interval suggestion from a quick-water action back to the calling screen. */
data class QuickWaterSuggestion(val plantId: Long, val plantName: String, val suggestedInterval: Int)
