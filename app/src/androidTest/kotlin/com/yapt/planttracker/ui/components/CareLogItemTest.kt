package com.yapt.planttracker.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CareLogItemTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun genericCustomCareLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.care_type_custom)

    /**
     * Regression test for #232: [CareLog.customReminderId] is a deliberately unenforced FK (technical
     * ADR-0019) — deleting a [com.yapt.planttracker.domain.model.CustomReminder] leaves any journal
     * entries with a dangling id. The caller resolves the name lookup and passes `null` when the
     * reminder no longer exists; [CareLogItem] must fall back to the generic label rather than
     * crashing or rendering blank text.
     */
    @Test
    fun customCareLog_withDanglingCustomReminderId_showsGenericFallbackLabel() {
        val log = CareLog(
            id = 1L,
            plantId = 1L,
            careType = CareType.CUSTOM,
            loggedAt = 0L,
            customReminderId = 999L
        )

        composeTestRule.setContent {
            CareLogItem(log = log, customReminderName = null)
        }

        composeTestRule.onNodeWithText(genericCustomCareLabel()).assertIsDisplayed()
    }
}
