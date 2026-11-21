package com.yapt.planttracker.ui.navigation

import android.content.Intent
import com.yapt.planttracker.notification.PostWateringReminderPresentation
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab
import com.yapt.planttracker.ui.widget.CareWidgetIntents
import com.yapt.planttracker.worker.ReminderWorker

/**
 * Where an intent that launches `MainActivity` asks to land. [plantId] is a plant id or one of the
 * sentinels [Screen.PlantList.CARED_TODAY_DEEP_LINK_ID] / [Screen.Today.CARE_DEEP_LINK_ID]; [tab] only
 * applies to a real plant id.
 */
data class AppDeepLink(val plantId: Long, val tab: PlantDetailTab? = null)

/**
 * Reads the deep link of an intent that launched `MainActivity`: the post-watering reminder's
 * "cared for today" view, the widget's "open Care", or a plant (from a reminder notification or a widget
 * row, the latter with a tab). `null` when the intent asks for nothing.
 */
fun Intent.appDeepLink(): AppDeepLink? = when {
    getBooleanExtra(PostWateringReminderPresentation.EXTRA_SHOW_CARED_TODAY, false) ->
        AppDeepLink(Screen.PlantList.CARED_TODAY_DEEP_LINK_ID)
    getBooleanExtra(CareWidgetIntents.EXTRA_OPEN_CARE, false) ->
        AppDeepLink(Screen.Today.CARE_DEEP_LINK_ID)
    else -> getLongExtra(ReminderWorker.EXTRA_PLANT_ID, 0L).takeIf { it > 0L }?.let { plantId ->
        val tab = getStringExtra(CareWidgetIntents.EXTRA_PLANT_DETAIL_TAB)?.let { name ->
            runCatching { PlantDetailTab.valueOf(name) }.getOrNull()
        }
        AppDeepLink(plantId, tab)
    }
}
