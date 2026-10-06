package com.yapt.planttracker.ui.navigation

import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab

sealed class Screen(val route: String) {
    object PlantList : Screen("plant_list?restoreMessage={restoreMessage}") {
        const val CARED_TODAY_DEEP_LINK_ID = -2L

        fun createRoute(restoreMessage: String? = null) =
            if (restoreMessage != null) {
                "plant_list?restoreMessage=$restoreMessage"
            } else {
                "plant_list"
            }
    }
    object AddPlant : Screen("add_plant")
    object Settings : Screen("settings")

    object EditPlant : Screen("edit_plant/{plantId}") {
        fun createRoute(plantId: Long) = "edit_plant/$plantId"
    }

    object PlantDetail : Screen("plant_detail/{plantId}?tab={tab}") {
        const val TAB_ARG = "tab"

        fun createRoute(plantId: Long, tab: PlantDetailTab? = null) =
            if (tab != null) "plant_detail/$plantId?tab=${tab.name}" else "plant_detail/$plantId"
    }

    object AddCareLog : Screen("add_care_log/{plantId}/{careLogId}") {
        fun createRoute(plantId: Long, careLogId: Long) = "add_care_log/$plantId/$careLogId"
    }

    object Graveyard : Screen("graveyard")

    object RepottingOverview : Screen("repotting_overview")

    object Today : Screen("today")

    object Calendar : Screen("calendar")
}
