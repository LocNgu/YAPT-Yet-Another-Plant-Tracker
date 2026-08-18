package com.yapt.planttracker.ui.navigation

import android.net.Uri
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yapt.planttracker.BuildConfig
import com.yapt.planttracker.R
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.settingsDataStore
import com.yapt.planttracker.ui.screens.addcarelog.AddCareLogScreen
import com.yapt.planttracker.ui.screens.addcarelog.AddCareLogViewModel
import com.yapt.planttracker.ui.screens.addplant.AddEditPlantScreen
import com.yapt.planttracker.ui.screens.addplant.AddEditPlantViewModel
import com.yapt.planttracker.ui.screens.calendar.CalendarScreen
import com.yapt.planttracker.ui.screens.calendar.CalendarViewModel
import com.yapt.planttracker.ui.screens.graveyard.GraveyardScreen
import com.yapt.planttracker.ui.screens.graveyard.GraveyardViewModel
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailScreen
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailViewModel
import com.yapt.planttracker.ui.screens.plantlist.PlantListScreen
import com.yapt.planttracker.ui.screens.plantlist.PlantListViewModel
import com.yapt.planttracker.ui.screens.settings.SettingsScreen
import com.yapt.planttracker.ui.screens.settings.SettingsViewModel
import com.yapt.planttracker.ui.screens.whatsnew.WhatsNewSheet
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// Rapid double-taps on a back button can fire two clicks in the same frame,
// causing popBackStack() to run twice before Navigation processes the first
// pop — that pops both the current entry and its parent, leaving NavHost with
// no destination (a blank white screen). Guarding on RESUMED short-circuits
// the second call: the entry's lifecycle transitions to STARTED as soon as
// the first pop begins.
@androidx.annotation.VisibleForTesting
internal fun NavController.popBackStackOnce(
    entry: NavBackStackEntry,
    route: String? = null,
    inclusive: Boolean = false
): Boolean {
    if (!entry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return false
    return if (route != null) popBackStack(route, inclusive) else popBackStack()
}

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

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    // Hidden while the plant list is in multi-select mode so the bulk action bar can use that space.
    var plantListSelectionActive by remember { mutableStateOf(false) }
    val showBottomBar = (currentRoute == Screen.PlantList.route || currentRoute == Screen.Calendar.route) &&
        !plantListSelectionActive

    Scaffold(
        // No topBar on this outer Scaffold: without zeroing contentWindowInsets, Scaffold would
        // still reserve the status-bar inset at the top of every screen's content (since there's
        // no top bar to consume it), breaking PlantDetailScreen's edge-to-edge hero photo (#29)
        // and other screens' own inset handling. Each nested screen manages its own insets;
        // this outer Scaffold's only job is to reserve room for the bottom nav bar.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == Screen.PlantList.route,
                        onClick = {
                            navController.navigate(Screen.PlantList.createRoute()) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Filled.LocalFlorist, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_tab_plants)) }
                    )
                    NavigationBarItem(
                        selected = currentRoute == Screen.Calendar.route,
                        onClick = {
                            navController.navigate(Screen.Calendar.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Filled.CalendarMonth, contentDescription = null) },
                        label = { Text(stringResource(R.string.nav_tab_calendar)) }
                    )
                }
            }
        }
    ) { scaffoldPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.PlantList.route,
            modifier = Modifier.padding(scaffoldPadding)
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
                        app.plantPhotoRepository,
                        app.settingsDataStore,
                        app.quickLogUseCase,
                        app.plantIssueRepository
                    )
                )
                LaunchedEffect(vm) {
                    backStackEntry.savedStateHandle.getStateFlow<Long?>("archivedPlantId", null)
                        .collect { plantId ->
                            if (plantId != null) {
                                val plantName = backStackEntry.savedStateHandle.remove<String>("archivedPlantName") ?: ""
                                backStackEntry.savedStateHandle.remove<Long>("archivedPlantId")
                                vm.onPlantArchived(plantId, plantName)
                            }
                        }
                }
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
                    },
                    onSelectionModeChanged = { plantListSelectionActive = it }
                )
            }

            composable(Screen.AddPlant.route) { backStackEntry ->
                val vm: AddEditPlantViewModel = viewModel(
                    factory = AddEditPlantViewModel.Factory(app.plantRepository, app.plantPhotoRepository, null)
                )
                AddEditPlantScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStackOnce(backStackEntry) }
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
                    onNavigateBack = { navController.popBackStackOnce(backStackEntry) },
                    onPlantArchived = { archivedId, archivedName ->
                        navController.getBackStackEntry(Screen.PlantList.route)
                            .savedStateHandle["archivedPlantId"] = archivedId
                        navController.getBackStackEntry(Screen.PlantList.route)
                            .savedStateHandle["archivedPlantName"] = archivedName
                        navController.popBackStackOnce(backStackEntry, Screen.PlantList.route)
                    }
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
                        plantId,
                        app.settingsDataStore,
                        app.quickLogUseCase,
                        app.customReminderRepository,
                        app.plantIssueRepository
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
                    onNavigateBack = { navController.popBackStackOnce(backStackEntry) },
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
                    navArgument("careLogId") {
                        type = NavType.LongType;
                        defaultValue = 0L
                    }
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
                        navController.popBackStackOnce(backStackEntry)
                    }
                )
            }

            composable(Screen.Settings.route) { backStackEntry ->
                val vm: SettingsViewModel = viewModel(
                    factory = SettingsViewModel.Factory(
                        app.settingsDataStore,
                        app,
                        app.database,
                        app.plantRepository,
                        app.featureFlags
                    )
                )
                SettingsScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStackOnce(backStackEntry) },
                    onRestoreSuccess = { plantCount, logCount ->
                        val encodedMsg = Uri.encode("Restored $plantCount plants and $logCount logs")
                        navController.navigate(Screen.PlantList.createRoute(encodedMsg)) {
                            popUpTo(0) { inclusive = true }
                        }
                    },
                    onShowWhatsNew = { showWhatsNew = true },
                    onNavigateToGraveyard = { navController.navigate(Screen.Graveyard.route) }
                )
            }

            composable(Screen.Graveyard.route) { backStackEntry ->
                val vm: GraveyardViewModel = viewModel(
                    factory = GraveyardViewModel.Factory(app.plantRepository)
                )
                GraveyardScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStackOnce(backStackEntry) }
                )
            }

            composable(Screen.Calendar.route) {
                val vm: CalendarViewModel = viewModel(
                    factory = CalendarViewModel.Factory(
                        app,
                        app.plantRepository,
                        app.careLogRepository,
                        app.plantPhotoRepository,
                        app.settingsDataStore,
                        app.quickLogUseCase
                    )
                )
                CalendarScreen(
                    viewModel = vm,
                    onNavigateToPlant = { plantId ->
                        navController.navigate(Screen.PlantDetail.createRoute(plantId))
                    }
                )
            }
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
