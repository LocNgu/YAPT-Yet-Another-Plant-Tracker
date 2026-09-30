package com.yapt.planttracker.ui.navigation

import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import com.yapt.planttracker.domain.model.CareType
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
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailViewModel
import com.yapt.planttracker.ui.screens.plantdetail.handleSuggestedWateringInterval
import com.yapt.planttracker.ui.screens.plantlist.PlantListScreen
import com.yapt.planttracker.ui.screens.plantlist.PlantListViewModel
import com.yapt.planttracker.ui.screens.settings.SettingsScreen
import com.yapt.planttracker.ui.screens.settings.SettingsViewModel
import com.yapt.planttracker.ui.screens.today.TodayScreen
import com.yapt.planttracker.ui.screens.today.TodayViewModel
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

internal fun NavController.navigateToRootTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun NavController.navigateInitialDestination(
    initialPlantId: Long?,
    onShowCaredToday: () -> Unit,
    onConsumed: () -> Unit
) {
    if (initialPlantId == null) return
    if (initialPlantId == Screen.PlantList.CARED_TODAY_DEEP_LINK_ID) {
        onShowCaredToday()
        navigateToRootTab(Screen.PlantList.createRoute())
    } else {
        navigate(Screen.PlantDetail.createRoute(initialPlantId))
    }
    onConsumed()
}

// Care is the start destination, so Plants is not guaranteed to sit below Edit Plant on the back
// stack (Care -> Plant Detail -> Edit). Reuse it when present; otherwise stack it above Care first.
@androidx.annotation.VisibleForTesting
internal fun NavController.showArchivedPlantOnPlantList(
    editEntry: NavBackStackEntry,
    archivedId: Long,
    archivedName: String
) {
    val existing = runCatching { getBackStackEntry(Screen.PlantList.route) }.getOrNull()
    if (existing != null) {
        existing.savedStateHandle["archivedPlantId"] = archivedId
        existing.savedStateHandle["archivedPlantName"] = archivedName
        popBackStackOnce(editEntry, Screen.PlantList.route)
        return
    }
    navigate(Screen.PlantList.createRoute()) {
        popUpTo(graph.findStartDestination().id)
        launchSingleTop = true
    }
    runCatching { getBackStackEntry(Screen.PlantList.route) }.getOrNull()?.savedStateHandle?.let { handle ->
        handle["archivedPlantId"] = archivedId
        handle["archivedPlantName"] = archivedName
    }
}

@androidx.annotation.VisibleForTesting
internal fun shouldShowBottomNavigation(
    currentRoute: String?,
    plantListSelectionActive: Boolean
): Boolean = currentRoute in setOf(
    Screen.Today.route,
    Screen.PlantList.route,
    Screen.Calendar.route,
    Screen.Settings.route
) && !(currentRoute == Screen.PlantList.route && plantListSelectionActive)

// A restore replaces every table, so nothing already on the back stack is trustworthy: reset to
// Care, then stack Plants above it carrying the result message.
@androidx.annotation.VisibleForTesting
internal fun NavController.showRestoredPlantList(plantCount: Int, logCount: Int) {
    val encodedMsg = Uri.encode("Restored $plantCount plants and $logCount logs")
    navigate(Screen.Today.route) {
        popUpTo(0) { inclusive = true }
    }
    navigate(Screen.PlantList.createRoute(encodedMsg))
}

private data class BottomTab(
    val screenRoute: String,
    val navigateRoute: String,
    val icon: ImageVector,
    @StringRes val labelRes: Int
)

private val bottomTabs = listOf(
    BottomTab(Screen.Today.route, Screen.Today.route, Icons.Filled.Checklist, R.string.nav_tab_today),
    BottomTab(
        Screen.PlantList.route,
        Screen.PlantList.createRoute(),
        Icons.Filled.LocalFlorist,
        R.string.nav_tab_plants
    ),
    BottomTab(Screen.Calendar.route, Screen.Calendar.route, Icons.Filled.CalendarMonth, R.string.nav_tab_calendar),
    BottomTab(Screen.Settings.route, Screen.Settings.route, Icons.Filled.Settings, R.string.nav_tab_settings)
)

// Items are disabled, not hidden, while a backup/restore runs so its progress dialog can't be
// abandoned by switching tabs (product ADR-0058).
@Composable
@androidx.annotation.VisibleForTesting
internal fun YaptBottomNavigationBar(
    currentRoute: String?,
    enabled: Boolean,
    onNavigate: (String) -> Unit
) {
    NavigationBar {
        bottomTabs.forEach { tab ->
            NavigationBarItem(
                selected = currentRoute == tab.screenRoute,
                enabled = enabled,
                onClick = { onNavigate(tab.navigateRoute) },
                icon = { Icon(tab.icon, contentDescription = null) },
                label = { Text(stringResource(tab.labelRes)) }
            )
        }
    }
}

@Composable
private fun ApplyCaredTodayDeepLink(
    pending: Boolean,
    viewModel: PlantListViewModel,
    onApplied: () -> Unit
) {
    LaunchedEffect(pending) {
        if (pending) {
            viewModel.showCaredForTodayTransiently()
            onApplied()
        }
    }
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
    var pendingCaredTodayDeepLink by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val lastSeen = app.settingsDataStore.data.first()[SettingsKeys.LAST_SEEN_VERSION_CODE] ?: 0
        if (BuildConfig.VERSION_CODE > lastSeen) {
            showWhatsNew = true
            updateStoreOnWhatsNewDismiss = true
        }
    }

    LaunchedEffect(initialPlantId) {
        navController.navigateInitialDestination(
            initialPlantId = initialPlantId,
            onShowCaredToday = { pendingCaredTodayDeepLink = true },
            onConsumed = onDeepLinkConsumed
        )
    }

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = currentBackStackEntry?.destination?.route
    var plantListSelectionActive by remember { mutableStateOf(false) }
    var backupInProgress by remember { mutableStateOf(false) }
    val showBottomBar = shouldShowBottomNavigation(currentRoute, plantListSelectionActive)

    Scaffold(
        // No topBar on this outer Scaffold: without zeroing contentWindowInsets, Scaffold would
        // still reserve the status-bar inset at the top of every screen's content (since there's
        // no top bar to consume it), breaking PlantDetailScreen's edge-to-edge hero photo (#29)
        // and other screens' own inset handling. Each nested screen manages its own insets;
        // this outer Scaffold's only job is to reserve room for the bottom nav bar.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showBottomBar) {
                YaptBottomNavigationBar(
                    currentRoute = currentRoute,
                    enabled = !backupInProgress,
                    onNavigate = navController::navigateToRootTab
                )
            }
        }
    ) { scaffoldPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Today.route,
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
                        app.settingsDataStore,
                        app.quickLogUseCase,
                        app.plantIssueRepository
                    )
                )
                ApplyCaredTodayDeepLink(
                    pending = pendingCaredTodayDeepLink,
                    viewModel = vm,
                    onApplied = { pendingCaredTodayDeepLink = false }
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
                    onSelectionModeChanged = { plantListSelectionActive = it }
                )
            }

            composable(Screen.AddPlant.route) { backStackEntry ->
                val vm: AddEditPlantViewModel = viewModel(
                    factory = AddEditPlantViewModel.Factory(
                        app.plantRepository,
                        app.plantPhotoRepository,
                        null,
                        app.settingsDataStore,
                        app.wateringAdjustmentRepository
                    )
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
                    factory = AddEditPlantViewModel.Factory(
                        app.plantRepository,
                        app.plantPhotoRepository,
                        plantId,
                        app.settingsDataStore,
                        app.wateringAdjustmentRepository
                    )
                )
                AddEditPlantScreen(
                    viewModel = vm,
                    onNavigateBack = { navController.popBackStackOnce(backStackEntry) },
                    onPlantArchived = { archivedId, archivedName ->
                        navController.showArchivedPlantOnPlantList(backStackEntry, archivedId, archivedName)
                    }
                )
            }

            composable(
                route = Screen.PlantDetail.route,
                arguments = listOf(
                    navArgument("plantId") { type = NavType.LongType },
                    navArgument(Screen.PlantDetail.TAB_ARG) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) { backStackEntry ->
                val plantId = backStackEntry.arguments!!.getLong("plantId")
                val initialTab = PlantDetailTab.fromRouteArg(
                    backStackEntry.arguments?.getString(Screen.PlantDetail.TAB_ARG)
                )
                val vm: PlantDetailViewModel = viewModel(
                    factory = PlantDetailViewModel.Factory(
                        app.plantRepository,
                        app.careLogRepository,
                        app.plantPhotoRepository,
                        plantId,
                        app.settingsDataStore,
                        app.quickLogUseCase,
                        app.customReminderRepository,
                        app.plantIssueRepository,
                        app.database,
                        app.wateringAdjustmentRepository,
                        app.applicationScope
                    )
                )

                val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
                LaunchedEffect(savedStateHandle) {
                    val suggestedInterval = savedStateHandle?.get<Int>("suggestedWateringInterval")
                    if (suggestedInterval != null) {
                        val suggestedBase = savedStateHandle.get<Double>("suggestedWateringBaseInterval")
                        vm.handleSuggestedWateringInterval(suggestedInterval, suggestedBase)
                        savedStateHandle.remove<Int>("suggestedWateringInterval")
                        savedStateHandle.remove<Double>("suggestedWateringBaseInterval")
                    }
                }

                PlantDetailScreen(
                    viewModel = vm,
                    initialTab = initialTab,
                    onNavigateBack = { navController.popBackStackOnce(backStackEntry) },
                    onNavigateToEdit = {
                        navController.navigate(Screen.EditPlant.createRoute(plantId))
                    },
                    onNavigateToAddLog = {
                        navController.navigate(
                            Screen.AddCareLog.createRoute(
                                plantId,
                                careType = vm.consumeNewLogCareType()
                            )
                        )
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
                        type = NavType.LongType
                        defaultValue = 0L
                    },
                    navArgument("careType") {
                        type = NavType.StringType
                        defaultValue = CareType.WATER.name
                    }
                )
            ) { backStackEntry ->
                val plantId = backStackEntry.arguments!!.getLong("plantId")
                val careLogId = backStackEntry.arguments!!.getLong("careLogId")
                val initialCareType = runCatching {
                    CareType.valueOf(backStackEntry.arguments!!.getString("careType")!!)
                }.getOrDefault(CareType.WATER)
                val vm: AddCareLogViewModel = viewModel(
                    factory = AddCareLogViewModel.Factory(
                        app.careLogRepository,
                        app.plantRepository,
                        plantId,
                        careLogId,
                        app.settingsDataStore,
                        app.wateringAdjustmentRepository,
                        app::schedulePostWateringReminder
                    )
                )
                LaunchedEffect(initialCareType) {
                    vm.preselectCareType(initialCareType)
                }
                AddCareLogScreen(
                    viewModel = vm,
                    onNavigateBack = { suggestedInterval, suggestedBaseInterval ->
                        suggestedInterval?.let { interval ->
                            navController.previousBackStackEntry
                                ?.savedStateHandle
                                ?.set("suggestedWateringInterval", interval)
                            suggestedBaseInterval?.let { base ->
                                navController.previousBackStackEntry
                                    ?.savedStateHandle
                                    ?.set("suggestedWateringBaseInterval", base)
                            }
                        }
                        navController.popBackStackOnce(backStackEntry)
                    }
                )
            }

            composable(Screen.Settings.route) {
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
                    onRestoreSuccess = navController::showRestoredPlantList,
                    onShowWhatsNew = { showWhatsNew = true },
                    onNavigateToGraveyard = { navController.navigate(Screen.Graveyard.route) },
                    onBackupInProgressChanged = { backupInProgress = it }
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

            composable(Screen.Today.route) {
                val vm: TodayViewModel = viewModel(
                    factory = TodayViewModel.Factory(
                        app,
                        app.todayCareRepository,
                        app.quickLogUseCase,
                        app.plantRepository
                    )
                )
                TodayScreen(
                    viewModel = vm,
                    onNavigateToPlant = { plantId, tab ->
                        navController.navigate(Screen.PlantDetail.createRoute(plantId, tab))
                    },
                    onNavigateToAdd = { navController.navigate(Screen.AddPlant.route) }
                )
            }

            composable(Screen.Calendar.route) {
                val vm: CalendarViewModel = viewModel(
                    factory = CalendarViewModel.Factory(
                        app,
                        app.plantRepository,
                        app.careLogRepository,
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
