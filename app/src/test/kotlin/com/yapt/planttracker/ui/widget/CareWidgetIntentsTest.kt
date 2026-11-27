package com.yapt.planttracker.ui.widget

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.MainActivity
import com.yapt.planttracker.notification.PostWateringReminderPresentation
import com.yapt.planttracker.ui.navigation.AppDeepLink
import com.yapt.planttracker.ui.navigation.Screen
import com.yapt.planttracker.ui.navigation.appDeepLink
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab
import com.yapt.planttracker.worker.ReminderWorker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The widget's launch intents and how `MainActivity` reads them back (#923). */
@RunWith(RobolectricTestRunner::class)
class CareWidgetIntentsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `header intent opens Care`() {
        val intent = CareWidgetIntents.openCare(context)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(AppDeepLink(Screen.Today.CARE_DEEP_LINK_ID), intent.appDeepLink())
    }

    @Test
    fun `row intent opens the plant on the given tab`() {
        val intent = CareWidgetIntents.openPlantDetail(context, plantId = 42L, tab = PlantDetailTab.FERTILIZE)

        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(AppDeepLink(42L, PlantDetailTab.FERTILIZE), intent.appDeepLink())
    }

    @Test
    fun `reminder notification intents keep their meaning`() {
        assertEquals(
            AppDeepLink(7L),
            Intent().putExtra(ReminderWorker.EXTRA_PLANT_ID, 7L).appDeepLink()
        )
        assertEquals(
            AppDeepLink(Screen.PlantList.CARED_TODAY_DEEP_LINK_ID),
            Intent().putExtra(PostWateringReminderPresentation.EXTRA_SHOW_CARED_TODAY, true).appDeepLink()
        )
    }

    @Test
    fun `no extras, a sentinel-looking id or an unknown tab never mislead`() {
        assertNull(Intent().appDeepLink())
        assertNull(Intent().putExtra(ReminderWorker.EXTRA_PLANT_ID, Screen.Today.CARE_DEEP_LINK_ID).appDeepLink())
        assertEquals(
            AppDeepLink(7L, tab = null),
            Intent()
                .putExtra(ReminderWorker.EXTRA_PLANT_ID, 7L)
                .putExtra(CareWidgetIntents.EXTRA_PLANT_DETAIL_TAB, "NOT_A_TAB")
                .appDeepLink()
        )
    }
}
