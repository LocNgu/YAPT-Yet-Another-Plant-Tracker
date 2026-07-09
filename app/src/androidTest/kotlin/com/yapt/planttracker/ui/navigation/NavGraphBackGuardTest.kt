package com.yapt.planttracker.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val ROUTE_A = "a"
private const val ROUTE_B = "b"
private const val ROUTE_C = "c"
private const val BACK_CD = "back"

// Regression coverage for #408: a rapid double-tap on a back arrow used to fire
// popBackStack() twice before Navigation demoted the current entry's lifecycle,
// popping both the current entry and its parent and leaving NavHost with no
// destination (a blank white screen). popBackStackOnce() in NavGraph.kt guards
// against this by short-circuiting a second call once the entry has dropped
// below RESUMED. These tests exercise the real internal popBackStackOnce
// against a real ComposeNavigator-backed NavHost, not a copy of the guard logic.
@RunWith(AndroidJUnit4::class)
class NavGraphBackGuardTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var navController: NavHostController

    private fun setUpThreeLevelStack() {
        composeTestRule.setContent {
            navController = rememberNavController()
            NavHost(navController = navController, startDestination = ROUTE_A) {
                composable(ROUTE_A) { Text("Screen A") }
                composable(ROUTE_B) { Text("Screen B") }
                composable(ROUTE_C) { backStackEntry ->
                    Text("Screen C")
                    IconButton(
                        onClick = { navController.popBackStackOnce(backStackEntry) }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = BACK_CD
                        )
                    }
                }
            }
        }

        composeTestRule.runOnUiThread {
            navController.navigate(ROUTE_B)
            navController.navigate(ROUTE_C)
        }
        composeTestRule.waitForIdle()
    }

    @Test
    fun popBackStackOnce_secondSynchronousCallOnSameEntry_isNoOp() {
        setUpThreeLevelStack()

        var firstPopSucceeded = false
        var secondPopSucceeded = false
        composeTestRule.runOnUiThread {
            val entry = navController.currentBackStackEntry!!
            firstPopSucceeded = navController.popBackStackOnce(entry)
            // Same entry, same synchronous block — mirrors two back-arrow clicks
            // firing before the first pop's lifecycle demotion is observed by the UI.
            secondPopSucceeded = navController.popBackStackOnce(entry)
        }
        composeTestRule.waitForIdle()

        assertTrue("first pop should succeed", firstPopSucceeded)
        assertFalse("second pop on the same entry must be blocked by the guard", secondPopSucceeded)
        assertEquals(
            "a regressed bare popBackStack() would skip past B all the way to A",
            ROUTE_B,
            navController.currentDestination?.route
        )
        composeTestRule.onNodeWithText("Screen B").assertExists()
    }

    @Test
    fun rapidDoubleTapOnBackButton_landsOnImmediateParent_notBlank() {
        setUpThreeLevelStack()

        // Freeze recomposition so both taps land on the same still-composed
        // back button before Navigation has a chance to swap in Screen B,
        // faithfully reproducing a same-frame double-tap.
        composeTestRule.mainClock.autoAdvance = false
        val backButton = composeTestRule.onNodeWithContentDescription(BACK_CD)
        backButton.performClick()
        backButton.performClick()
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitForIdle()

        assertEquals(ROUTE_B, navController.currentDestination?.route)
        composeTestRule.onNodeWithText("Screen B").assertExists()
    }
}
