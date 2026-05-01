package com.yapt.planttracker.ui.navigation

sealed class Screen(val route: String) {
    object PlantList : Screen("plant_list")
    object AddPlant : Screen("add_plant")
    object Settings : Screen("settings")

    object EditPlant : Screen("edit_plant/{plantId}") {
        fun createRoute(plantId: Long) = "edit_plant/$plantId"
    }

    object PlantDetail : Screen("plant_detail/{plantId}") {
        fun createRoute(plantId: Long) = "plant_detail/$plantId"
    }

    object AddCareLog : Screen("add_care_log/{plantId}") {
        fun createRoute(plantId: Long) = "add_care_log/$plantId"
    }
}
