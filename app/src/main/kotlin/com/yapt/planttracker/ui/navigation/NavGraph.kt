package com.yapt.planttracker.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yapt.planttracker.YaptApplication
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

@Composable
fun YaptNavGraph(app: YaptApplication) {
    val navController = rememberNavController()

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
                factory = AddEditPlantViewModel.Factory(app.plantRepository, null)
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
                factory = AddEditPlantViewModel.Factory(app.plantRepository, plantId)
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
                }
            )
        }
    }
}
