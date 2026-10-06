package com.yapt.planttracker.ui.navigation

import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [Screen] route templates and `createRoute` argument interpolation.
 *
 * These guard against silent navigation breakage: a changed route string or a
 * malformed argument placeholder would desync the `createRoute` call sites from the
 * `NavHost` `composable(route = …)` declarations, which the per-screen Compose tests
 * don't catch (they render screens directly, not through the graph). Pure JVM — no
 * Android dependencies.
 */
class ScreenTest {

    // --- Static route templates ---

    @Test
    fun `static routes are stable`() {
        assertEquals("add_plant", Screen.AddPlant.route)
        assertEquals("settings", Screen.Settings.route)
        assertEquals("graveyard", Screen.Graveyard.route)
        assertEquals("repotting_overview", Screen.RepottingOverview.route)
        assertEquals("today", Screen.Today.route)
        assertEquals("calendar", Screen.Calendar.route)
    }

    // --- Parameterized route templates carry the expected placeholders ---

    @Test
    fun `plantList route template declares optional restoreMessage argument`() {
        assertEquals("plant_list?restoreMessage={restoreMessage}", Screen.PlantList.route)
    }

    @Test
    fun `editPlant route template declares plantId path argument`() {
        assertEquals("edit_plant/{plantId}", Screen.EditPlant.route)
    }

    @Test
    fun `plantDetail route template declares plantId path argument`() {
        assertEquals("plant_detail/{plantId}?tab={tab}", Screen.PlantDetail.route)
    }

    @Test
    fun `addCareLog route template requires both plantId and careLogId path arguments`() {
        assertEquals("add_care_log/{plantId}/{careLogId}", Screen.AddCareLog.route)
    }

    // --- createRoute argument interpolation ---

    @Test
    fun `plantList createRoute omits query when restoreMessage is null`() {
        assertEquals("plant_list", Screen.PlantList.createRoute(null))
    }

    @Test
    fun `plantList createRoute appends restoreMessage when present`() {
        assertEquals("plant_list?restoreMessage=Restored", Screen.PlantList.createRoute("Restored"))
    }

    @Test
    fun `editPlant createRoute substitutes plantId`() {
        assertEquals("edit_plant/42", Screen.EditPlant.createRoute(42L))
    }

    @Test
    fun `plantDetail createRoute substitutes plantId`() {
        assertEquals("plant_detail/7", Screen.PlantDetail.createRoute(7L))
    }

    @Test
    fun `plantDetail createRoute appends tab only when given`() {
        assertEquals("plant_detail/7?tab=PHOTO", Screen.PlantDetail.createRoute(7L, PlantDetailTab.PHOTO))
        assertEquals("plant_detail/7?tab=ISSUES", Screen.PlantDetail.createRoute(7L, PlantDetailTab.ISSUES))
    }

    @Test
    fun `plantDetail tab arg parses defensively`() {
        assertEquals(null, PlantDetailTab.fromRouteArg(null))
        assertEquals(PlantDetailTab.FERTILIZE, PlantDetailTab.fromRouteArg("FERTILIZE"))
        assertEquals(PlantDetailTab.PRUNE, PlantDetailTab.fromRouteArg("PRUNE"))
        assertEquals(PlantDetailTab.DEFAULT, PlantDetailTab.fromRouteArg("bogus"))
    }

    @Test
    fun `only the second-row tabs need the tab row expanded`() {
        assertEquals(
            setOf(
                PlantDetailTab.REPOT,
                PlantDetailTab.PRUNE,
                PlantDetailTab.CUSTOM_REMINDERS,
                PlantDetailTab.ISSUES
            ),
            PlantDetailTab.entries.filter { it.isInCollapsedRow }.toSet()
        )
    }

    @Test
    fun `home is the first tab and the default landing tab`() {
        assertEquals(PlantDetailTab.HOME, PlantDetailTab.DEFAULT)
        assertEquals(PlantDetailTab.HOME, PlantDetailTab.entries.first())
        assertEquals(
            listOf(
                PlantDetailTab.HOME,
                PlantDetailTab.WATER,
                PlantDetailTab.FERTILIZE,
                PlantDetailTab.PHOTO,
                PlantDetailTab.REPOT,
                PlantDetailTab.PRUNE,
                PlantDetailTab.CUSTOM_REMINDERS,
                PlantDetailTab.ISSUES
            ),
            PlantDetailTab.entries
        )
    }

    @Test
    fun `addCareLog createRoute substitutes both plantId and careLogId`() {
        assertEquals("add_care_log/3/9", Screen.AddCareLog.createRoute(3L, 9L))
    }

    @Test
    fun `bottom navigation is visible on every root tab when nothing is selected`() {
        assertTrue(shouldShowBottomNavigation(Screen.Today.route, false))
        assertTrue(shouldShowBottomNavigation(Screen.PlantList.route, false))
        assertTrue(shouldShowBottomNavigation(Screen.Calendar.route, false))
        assertTrue(shouldShowBottomNavigation(Screen.Settings.route, false))
    }

    @Test
    fun `bottom navigation hides only for the Plant List selection mode`() {
        assertFalse(shouldShowBottomNavigation(Screen.PlantList.route, true))
        assertTrue(shouldShowBottomNavigation(Screen.Today.route, true))
        assertTrue(shouldShowBottomNavigation(Screen.Calendar.route, true))
        assertTrue(shouldShowBottomNavigation(Screen.Settings.route, true))
    }

    @Test
    fun `bottom navigation hides for nested destinations`() {
        assertFalse(shouldShowBottomNavigation(Screen.PlantDetail.route, false))
        assertFalse(shouldShowBottomNavigation(Screen.Graveyard.route, false))
        assertFalse(shouldShowBottomNavigation(Screen.RepottingOverview.route, false))
        assertFalse(shouldShowBottomNavigation(null, false))
    }
}
