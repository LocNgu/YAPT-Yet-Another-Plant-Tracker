package com.yapt.planttracker.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.BuildConfig
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.settingsDataStore
import com.yapt.planttracker.ui.screens.today.TODAY_TASK_LIST_TAG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TodayRootNavigationTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val application = ApplicationProvider.getApplicationContext<YaptApplication>()

    @Before
    fun setUp() = runBlocking(Dispatchers.IO) {
        application.database.clearAllTables()
        application.settingsDataStore.edit { preferences ->
            preferences.clear()
            preferences[SettingsKeys.LAST_SEEN_VERSION_CODE] = BuildConfig.VERSION_CODE
        }
        repeat(PLANT_COUNT) { index ->
            application.plantRepository.addPlant(
                Plant(
                    name = "Plant ${index.toString().padStart(2, '0')}",
                    wateringIntervalDays = 1,
                    pinIntervalToBase = true
                )
            )
        }
    }

    @After
    fun tearDown() = runBlocking(Dispatchers.IO) {
        application.database.clearAllTables()
    }

    @Test
    fun todayScrollAndSelectionRestoreAcrossCalendarRootSwitch() {
        composeTestRule.setContent { YaptNavGraph(application) }

        composeTestRule.onAllNodesWithText("Plants")[0].assertIsDisplayed()
        composeTestRule.onNode(hasText("Today").and(hasClickAction())).performClick()
        composeTestRule.onNodeWithTag(TODAY_TASK_LIST_TAG).performScrollToNode(hasText("Plant 23"))
        composeTestRule.onNodeWithText("Plant 23").assertIsDisplayed()
        composeTestRule.onNodeWithText("Plant 23").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()

        composeTestRule.onNode(hasText("Calendar").and(hasClickAction())).performClick()
        composeTestRule.onNode(hasText("Today").and(hasClickAction())).performClick()

        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeTestRule.onNodeWithText("Plant 23").assertIsDisplayed()
    }

    private companion object {
        const val PLANT_COUNT = 24
    }
}
