package com.yapt.planttracker.ui.navigation

import org.junit.Assert.assertEquals
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
        assertEquals("plant_detail/{plantId}", Screen.PlantDetail.route)
    }

    @Test
    fun `addCareLog route template declares plantId path and optional careLogId argument`() {
        assertEquals("add_care_log/{plantId}?careLogId={careLogId}", Screen.AddCareLog.route)
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
    fun `addCareLog createRoute defaults careLogId to 0`() {
        assertEquals("add_care_log/3?careLogId=0", Screen.AddCareLog.createRoute(3L))
    }

    @Test
    fun `addCareLog createRoute substitutes both plantId and careLogId`() {
        assertEquals("add_care_log/3?careLogId=9", Screen.AddCareLog.createRoute(3L, 9L))
    }

    // --- createRoute output structurally matches its template's fixed prefix ---

    @Test
    fun `createRoute results share the fixed prefix of their route template`() {
        assertEquals(
            Screen.EditPlant.route.substringBefore("{"),
            Screen.EditPlant.createRoute(1L).substringBeforeLast("1")
        )
        assertEquals(
            Screen.PlantDetail.route.substringBefore("{"),
            Screen.PlantDetail.createRoute(1L).substringBeforeLast("1")
        )
    }
}
