package com.yapt.planttracker.ui.widget

import android.content.Context
import android.content.Intent
import com.yapt.planttracker.MainActivity
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab
import com.yapt.planttracker.worker.ReminderWorker

/** Extras the Care widget (#923, product ADR-0088) adds to its [MainActivity] intents. */
object CareWidgetIntents {
    const val EXTRA_OPEN_CARE = "openCare"
    const val EXTRA_PLANT_DETAIL_TAB = "plantDetailTab"

    /** Header, count and empty/first-use states: open the Care tab. */
    fun openCare(context: Context): Intent =
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_OPEN_CARE, true)

    /** A row: open that plant's Plant Detail on [tab]. */
    fun openPlantDetail(context: Context, plantId: Long, tab: PlantDetailTab): Intent =
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(ReminderWorker.EXTRA_PLANT_ID, plantId)
            .putExtra(EXTRA_PLANT_DETAIL_TAB, tab.name)
}
