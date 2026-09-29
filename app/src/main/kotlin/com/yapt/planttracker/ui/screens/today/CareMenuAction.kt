package com.yapt.planttracker.ui.screens.today

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask

internal enum class CareMenuAction {
    WATER,
    WATER_AND_FERTILIZE,
    FERTILIZE,
    RESCHEDULE,
    REPOT,
    MARK_DONE,
    TAKE_PHOTO
}

internal fun careMenuActions(kind: TodayCareKind): List<CareMenuAction> = when (kind) {
    TodayCareKind.WATER -> listOf(CareMenuAction.WATER, CareMenuAction.RESCHEDULE)
    TodayCareKind.WATER_AND_FERTILIZE -> listOf(CareMenuAction.WATER_AND_FERTILIZE, CareMenuAction.RESCHEDULE)
    TodayCareKind.FERTILIZE -> listOf(CareMenuAction.FERTILIZE)
    TodayCareKind.REPOT -> listOf(CareMenuAction.REPOT)
    TodayCareKind.CUSTOM_REMINDER,
    TodayCareKind.ISSUE_TREATMENT -> listOf(CareMenuAction.MARK_DONE)
    TodayCareKind.PHOTO -> listOf(CareMenuAction.TAKE_PHOTO)
}

// Every entry routes to the handler the removed inline button used, so the reason prompt, suggestion
// dialog, reschedule dialog, repot date picker and camera path are reused rather than reimplemented.
internal fun TodayTaskActions.perform(action: CareMenuAction, task: TodayCareTask) {
    when (action) {
        CareMenuAction.WATER,
        CareMenuAction.WATER_AND_FERTILIZE,
        CareMenuAction.FERTILIZE,
        CareMenuAction.MARK_DONE -> onComplete(task)
        CareMenuAction.RESCHEDULE -> onReschedule(task)
        CareMenuAction.REPOT -> onRepot(task)
        CareMenuAction.TAKE_PHOTO -> onPhoto(task)
    }
}

@Composable
internal fun careMenuActionLabel(action: CareMenuAction): String = when (action) {
    CareMenuAction.WATER -> stringResource(R.string.today_action_water)
    CareMenuAction.WATER_AND_FERTILIZE -> stringResource(R.string.today_action_water_fertilize)
    CareMenuAction.FERTILIZE -> stringResource(R.string.today_action_fertilize)
    CareMenuAction.RESCHEDULE -> stringResource(R.string.today_action_reschedule)
    CareMenuAction.REPOT -> stringResource(R.string.today_action_repot)
    CareMenuAction.MARK_DONE -> stringResource(R.string.today_action_mark_done)
    CareMenuAction.TAKE_PHOTO -> stringResource(R.string.today_action_photo)
}
