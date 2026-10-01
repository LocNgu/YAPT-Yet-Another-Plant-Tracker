package com.yapt.planttracker.ui.screens.repotting

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yapt.planttracker.R

internal enum class RepottingMenuAction {
    REPOT,
    PLAN_REPOT,
    CHANGE_PLAN,
    CLEAR_PLAN
}

internal fun repottingMenuActions(isPlanned: Boolean): List<RepottingMenuAction> =
    if (isPlanned) {
        listOf(RepottingMenuAction.REPOT, RepottingMenuAction.CHANGE_PLAN, RepottingMenuAction.CLEAR_PLAN)
    } else {
        listOf(RepottingMenuAction.REPOT, RepottingMenuAction.PLAN_REPOT)
    }

@Composable
internal fun repottingMenuActionLabel(action: RepottingMenuAction): String = when (action) {
    RepottingMenuAction.REPOT -> stringResource(R.string.repotting_overview_action_repot)
    RepottingMenuAction.PLAN_REPOT -> stringResource(R.string.repotting_overview_action_plan)
    RepottingMenuAction.CHANGE_PLAN -> stringResource(R.string.repotting_overview_action_change_plan)
    RepottingMenuAction.CLEAR_PLAN -> stringResource(R.string.repotting_overview_action_clear_plan)
}
