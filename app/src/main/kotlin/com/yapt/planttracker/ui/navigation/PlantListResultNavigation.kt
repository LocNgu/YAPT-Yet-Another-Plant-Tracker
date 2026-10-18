package com.yapt.planttracker.ui.navigation

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination

// Care is the start destination, so Plants is not guaranteed to sit below Edit Plant on the back
// stack (Care -> Plant Detail -> Edit). Reuse it when present; otherwise stack it above Care first.
@androidx.annotation.VisibleForTesting
internal fun NavController.showArchivedPlantOnPlantList(
    editEntry: NavBackStackEntry,
    archivedId: Long,
    archivedName: String
) = showPlantResultOnPlantList(editEntry, ARCHIVED_PLANT_ID_KEY, ARCHIVED_PLANT_NAME_KEY, archivedId, archivedName)

// Hiding (#915) leaves Edit Plant the same way archiving does, so it gets the same #836-safe return.
@androidx.annotation.VisibleForTesting
internal fun NavController.showHiddenPlantOnPlantList(
    editEntry: NavBackStackEntry,
    hiddenId: Long,
    hiddenName: String
) = showPlantResultOnPlantList(editEntry, HIDDEN_PLANT_ID_KEY, HIDDEN_PLANT_NAME_KEY, hiddenId, hiddenName)

internal const val ARCHIVED_PLANT_ID_KEY = "archivedPlantId"
internal const val ARCHIVED_PLANT_NAME_KEY = "archivedPlantName"
internal const val HIDDEN_PLANT_ID_KEY = "hiddenPlantId"
internal const val HIDDEN_PLANT_NAME_KEY = "hiddenPlantName"

private fun NavController.showPlantResultOnPlantList(
    editEntry: NavBackStackEntry,
    idKey: String,
    nameKey: String,
    plantId: Long,
    plantName: String
) {
    val existing = runCatching { getBackStackEntry(Screen.PlantList.route) }.getOrNull()
    if (existing != null) {
        existing.savedStateHandle[idKey] = plantId
        existing.savedStateHandle[nameKey] = plantName
        popBackStackOnce(editEntry, Screen.PlantList.route)
        return
    }
    navigate(Screen.PlantList.createRoute()) {
        popUpTo(graph.findStartDestination().id)
        launchSingleTop = true
    }
    runCatching { getBackStackEntry(Screen.PlantList.route) }.getOrNull()?.savedStateHandle?.let { handle ->
        handle[idKey] = plantId
        handle[nameKey] = plantName
    }
}
