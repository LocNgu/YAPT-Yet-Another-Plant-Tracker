package com.yapt.planttracker.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yapt.planttracker.BuildConfig
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.settingsDataStore
import com.yapt.planttracker.ui.screens.addcarelog.AddCareLogScreen
import com.yapt.planttracker.ui.screens.addcarelog.AddCareLogViewModel
import com.yapt.planttracker.ui.screens.addplant.AddEditPlantScreen
import com.yapt.planttracker.ui.screens.addplant.AddEditPlantViewModel
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailScreen
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailViewModel
import com.yapt.planttracker.ui.screens.plantlist.PlantListScreen
import com.yapt.planttracker.ui.screens.plantlist.PlantListViewModel
import com.yapt.planttracker.ui.screens.settings.SettingsScreen
import com.yapt.planttracker.ui.screens.settings.SettingsViewModel
import com.yapt.planttracker.ui.screens.whatsnew.WhatsNewSheet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun YaptNavGraph(
    app: YaptApplication,
    initialPlantId: Long? = null,
    onDeepLinkConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val scope = rememberCoroutineScope()
    var showWhatsNew by remember { mutableStateOf(false) }
    var updateStoreOnWhatsNewDismiss by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val lastSeen = app.settingsDataStore.data.first()[SettingsKeys.LAST_SEEN_VERSION_CODE] ?: 0
        if (BuildConfig.VERSION_CODE > lastSeen) {
            showWhatsNew = true
            updateStoreOnWhatsNewDismiss = true
        }
    }

    LaunchedEffect(initialPlantId) {
        if (initialPlantId != null) {
            navController.navigate(Screen.PlantDetail.createRoute(initialPlantId))
            onDeepLinkConsumed()
        }
    }

    NavHost(
        navController = navController,
        startDestination = Screen.PlantList.route
    ) {
        composable(
            route = Screen.PlantList.route,
            arguments = listOf(
                navArgument("restoreMessage") {
                    type = NavType.StringType
                    nullable = true
                    defaultValue = null
                }
            )
        ) { backStackEntry ->
            val restoreMessage = backStackEntry.arguments?.getString("restoreMessage")
            val vm: PlantListViewModel = viewModel(
                factory = PlantListViewModel.Factory(
                    app,
                    app.plantRepository,
                    app.careLogRepository,
                    app.settingsDataStore
                )
            )
            PlantListScreen(
                viewModel = vm,
                restoreMessage = restoreMessage,
                onNavigateToPlant = { plantId ->
                    navController.navigate(Screen.PlantDetail.createRoute(plantId))
                },
                onNavigateToPlantWithSuggestion = { plantId, suggestedInterval ->
                    navController.navigate(Screen.PlantDetail.createRoute(plantId))
                    navController.currentBackStackEntry
                        ?.savedStateHandle
                        ?.set("suggestedWateringInterval", suggestedInterval)
                },
                onNavigateToAdd = {
                    navController.navigate(Screen.AddPlant.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }

        composable(Screen.AddPlant.route) {
            val vm: AddEditPlantViewModel = viewModel(
                factory = AddEditPlantViewModel.Factory(app.plantRepository, app.plantPhotoRepository, null)
            )
            AddEditPlantScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.EditPlant.route,
            arguments = listOf(navArgument("plantId") { type = NavType.LongType })
        ) { backStackEntry ->
            val plantId = backStackEntry.arguments!!.getLong("plantId")
            val vm: AddEditPlantViewModel = viewModel(
                factory = AddEditPlantViewModel.Factory(app.plantRepository, app.plantPhotoRepository, plantId)
            )
            AddEditPlantScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.PlantDetail.route,
            arguments = listOf(navArgument("plantId") { type = NavType.LongType })
        ) { backStackEntry ->
            val plantId = backStackEntry.arguments!!.getLong("plantId")
            val vm: PlantDetailViewModel = viewModel(
                factory = PlantDetailViewModel.Factory(
                    app.plantRepository,
                    app.careLogRepository,
                    app.plantPhotoRepository,
                    plantId
                )
            )

            val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
            LaunchedEffect(savedStateHandle) {
                val suggestedInterval = savedStateHandle?.get<Int>("suggestedWateringInterval")
                if (suggestedInterval != null) {
                    vm.suggestedWateringInterval.value = suggestedInterval
                    savedStateHandle.remove<Int>("suggestedWateringInterval")
                }
            }

            PlantDetailScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToEdit = {
                    navController.navigate(Screen.EditPlant.createRoute(plantId))
                },
                onNavigateToAddLog = {
                    navController.navigate(Screen.AddCareLog.createRoute(plantId))
                },
                onNavigateToEditLog = { careLogId ->
                    navController.navigate(Screen.AddCareLog.createRoute(plantId, careLogId))
                }
            )
        }

        composable(
            route = Screen.AddCareLog.route,
            arguments = listOf(
                navArgument("plantId") { type = NavType.LongType },
                navArgument("careLogId") { type = NavType.LongType; defaultValue = 0L }
            )
        ) { backStackEntry ->
            val plantId = backStackEntry.arguments!!.getLong("plantId")
            val careLogId = backStackEntry.arguments!!.getLong("careLogId")
            val vm: AddCareLogViewModel = viewModel(
                factory = AddCareLogViewModel.Factory(
                    app.careLogRepository,
                    app.plantRepository,
                    plantId,
                    careLogId
                )
            )
            AddCareLogScreen(
                viewModel = vm,
                onNavigateBack = { suggestedInterval ->
                    suggestedInterval?.let { interval ->
                        navController.previousBackStackEntry
                            ?.savedStateHandle
                            ?.set("suggestedWateringInterval", interval)
                    }
                    navController.popBackStack()
                }
            )
        }

        composable(Screen.Settings.route) {
            val vm: SettingsViewModel = viewModel(
                factory = SettingsViewModel.Factory(app.settingsDataStore, app, app.database)
            )
            SettingsScreen(
                viewModel = vm,
                onNavigateBack = { navController.popBackStack() },
                onRestoreSuccess = { plantCount, logCount ->
                    val encodedMsg = Uri.encode("Restored $plantCount plants and $logCount logs")
                    navController.navigate(Screen.PlantList.createRoute(encodedMsg)) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onShowWhatsNew = { showWhatsNew = true }
            )
        }
    }

    if (showWhatsNew) {
        WhatsNewSheet(onDismiss = {
            showWhatsNew = false
            if (updateStoreOnWhatsNewDismiss) {
                updateStoreOnWhatsNewDismiss = false
                scope.launch {
                    app.settingsDataStore.edit {
                        it[SettingsKeys.LAST_SEEN_VERSION_CODE] = BuildConfig.VERSION_CODE
                    }
                }
            }
        })
    }
}
