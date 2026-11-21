package com.yapt.planttracker.ui.screens.today

import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayTaskBucket
import com.yapt.planttracker.screenshot.ScreenshotConfig
import com.yapt.planttracker.screenshot.captureGolden
import com.yapt.planttracker.screenshot.setScreenshotContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden images of the Care grid tile variants (#862, technical ADR-0045): plain watering, water + fertilize
 * badge, custom reminder and planned repot, all in one grid per (width, font scale, theme). Complements the
 * semantics tests in `TodayScreenTest` (#420), never replaces them.
 *
 * Fixtures are fixed: no photos (deterministic placeholder), fixed timestamps, and the grid reads no clock.
 * Re-record with `./gradlew recordRoborazziDebug` after a deliberate visual change.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class CareTileScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    @Config(qualifiers = "w320dp-h1400dp-xhdpi")
    fun narrowestPhoneAtLargestFontLight() = capture(ScreenshotConfig(320, 2.0f, dark = false))

    @Test
    @Config(qualifiers = "w320dp-h1400dp-xhdpi")
    fun narrowestPhoneAtLargestFontDark() = capture(ScreenshotConfig(320, 2.0f, dark = true))

    @Test
    @Config(qualifiers = "w468dp-h1000dp-xhdpi")
    fun threeMinimumWidthColumnsAtLargerFontLight() = capture(ScreenshotConfig(468, 1.3f, dark = false))

    @Test
    @Config(qualifiers = "w900dp-h700dp-xhdpi")
    fun tabletAtDefaultFontLight() = capture(ScreenshotConfig(900, 1.0f, dark = false))

    @Test
    @Config(qualifiers = "w900dp-h700dp-xhdpi")
    fun tabletAtDefaultFontDark() = capture(ScreenshotConfig(900, 1.0f, dark = true))

    private fun capture(config: ScreenshotConfig) {
        composeTestRule.setScreenshotContent(config) {
            TodayCareGrid(
                tasks = tileVariants(),
                gridState = rememberLazyGridState(),
                collapsedGroups = emptySet(),
                onToggleGroup = {},
                actions = TodayTaskActions(
                    onOpen = { _, _ -> },
                    onComplete = {},
                    onLogWatering = {},
                    onReschedule = {},
                    onRepot = {},
                    onPhoto = {}
                )
            )
        }
        composeTestRule.captureGolden("care_tiles", config)
    }

    private fun tileVariants(): List<TodayCareTask> {
        val fern = plant(1, "Boston fern")
        val monstera = plant(2, "Monstera deliciosa variegata")
        val pothos = plant(3, "Golden pothos")
        val ficus = plant(4, "Ficus lyrata (fiddle-leaf fig)")
        return listOf(
            task("water:1", fern, TodayCareKind.WATER),
            task("water:2", monstera, TodayCareKind.WATER_AND_FERTILIZE),
            task(
                "custom:3",
                pothos,
                TodayCareKind.CUSTOM_REMINDER,
                customReminder = CustomReminder(
                    id = 3,
                    plantId = pothos.id,
                    name = "Wipe the leaves",
                    intervalDays = 14,
                    createdAt = FIXED_TIME
                )
            ),
            task("repot:4", ficus, TodayCareKind.REPOT, repotPlanSeason = FertilizingSeason.SPRING)
        )
    }

    private fun plant(id: Long, name: String) = Plant(id = id, name = name, createdAt = FIXED_TIME, updatedAt = FIXED_TIME)

    private fun task(
        id: String,
        plant: Plant,
        kind: TodayCareKind,
        customReminder: CustomReminder? = null,
        repotPlanSeason: FertilizingSeason? = null
    ) = TodayCareTask(
        id = id,
        plant = plant,
        kind = kind,
        dueAt = FIXED_TIME,
        bucket = TodayTaskBucket.Today,
        customReminder = customReminder,
        repotPlanSeason = repotPlanSeason
    )

    private companion object {
        /** 2026-01-15T12:00:00Z; the grid renders no dates, this only keeps the fixtures fixed. */
        const val FIXED_TIME = 1_768_478_400_000L
    }
}
