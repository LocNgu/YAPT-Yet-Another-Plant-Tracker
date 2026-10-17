package com.yapt.planttracker.ui.navigation

import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.settingsDataStore
import com.yapt.planttracker.ui.screens.away.AwayModeNavigation
import com.yapt.planttracker.ui.screens.away.AwayModeViewModel

@Composable
internal fun awayModeViewModel(app: YaptApplication): AwayModeViewModel = viewModel(
    factory = AwayModeViewModel.Factory(app, app.awayPeriodRepository, app.settingsDataStore)
)

/** Where the Settings section and the Care banner send the owner: the sitter rhythm, Before you leave, plan a visit. */
internal fun NavController.awayModeNavigation() = AwayModeNavigation(
    onSitterRhythm = { navigate(Screen.SitterRhythm.route) },
    onBeforeYouLeave = { navigate(Screen.BeforeYouLeave.route) },
    onPlanVisit = { navigate(Screen.PlanSitterVisit.route) }
)
