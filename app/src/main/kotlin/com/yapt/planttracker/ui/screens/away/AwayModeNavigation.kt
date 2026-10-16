package com.yapt.planttracker.ui.screens.away

/** Where the Away mode entry points (Settings section, Care banner) lead: the slice-4 sitter screens. */
data class AwayModeNavigation(
    val onSitterRhythm: () -> Unit = {},
    val onBeforeYouLeave: () -> Unit = {},
    val onPlanVisit: () -> Unit = {}
)
