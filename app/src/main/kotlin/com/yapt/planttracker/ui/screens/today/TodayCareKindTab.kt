package com.yapt.planttracker.ui.screens.today

import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab

fun TodayCareKind.plantDetailTab(): PlantDetailTab = when (this) {
    TodayCareKind.WATER -> PlantDetailTab.WATER
    TodayCareKind.WATER_AND_FERTILIZE,
    TodayCareKind.FERTILIZE -> PlantDetailTab.FERTILIZE
    TodayCareKind.REPOT -> PlantDetailTab.REPOT
    TodayCareKind.PHOTO -> PlantDetailTab.PHOTO
    TodayCareKind.CUSTOM_REMINDER -> PlantDetailTab.CUSTOM_REMINDERS
    TodayCareKind.ISSUE_TREATMENT -> PlantDetailTab.ISSUES
}
