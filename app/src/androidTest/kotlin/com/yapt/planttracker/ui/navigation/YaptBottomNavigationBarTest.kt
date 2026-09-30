package com.yapt.planttracker.ui.navigation

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class YaptBottomNavigationBarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val tabLabels = listOf("Care", "Plants", "Calendar", "Settings")

    private fun tab(label: String) = hasText(label).and(hasClickAction())

    @Test
    fun barOffersSettingsAsATabAndMarksTheCurrentOneSelected() {
        composeTestRule.setContent {
            YaptBottomNavigationBar(currentRoute = Screen.Settings.route, enabled = true, onNavigate = {})
        }

        tabLabels.forEach { composeTestRule.onNode(tab(it)).assertIsEnabled() }
        composeTestRule.onNode(tab("Settings")).assertIsSelected()
    }

    @Test
    fun clickingATabReportsItsRoute() {
        val navigated = mutableListOf<String>()
        composeTestRule.setContent {
            YaptBottomNavigationBar(
                currentRoute = Screen.Today.route,
                enabled = true,
                onNavigate = { navigated += it }
            )
        }

        composeTestRule.onNode(tab("Settings")).performClick()
        composeTestRule.onNode(tab("Plants")).performClick()

        assertEquals(listOf(Screen.Settings.route, Screen.PlantList.createRoute()), navigated)
    }

    @Test
    fun whileDisabledEveryTabIsNotEnabledAndClicksDoNotNavigate() {
        val navigated = mutableListOf<String>()
        composeTestRule.setContent {
            YaptBottomNavigationBar(
                currentRoute = Screen.Settings.route,
                enabled = false,
                onNavigate = { navigated += it }
            )
        }

        tabLabels.forEach { label ->
            composeTestRule.onNode(tab(label)).assertIsNotEnabled().performClick()
        }

        assertTrue(navigated.isEmpty())
    }
}
