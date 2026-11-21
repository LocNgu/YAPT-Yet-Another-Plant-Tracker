package com.yapt.planttracker.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.roborazziSystemPropertyOutputDirectory
import com.yapt.planttracker.ui.theme.YaptTheme

/**
 * Shared screenshot (golden-image) harness (#862, technical ADR-0045).
 *
 * Width and height come from the test's Robolectric `@Config(qualifiers = …)` (e.g. `w320dp-h1200dp-xhdpi`);
 * [ScreenshotConfig.fontScale] and [ScreenshotConfig.dark] are applied here, so one golden is one
 * (surface, width, font scale, theme) combination.
 */
data class ScreenshotConfig(val widthDp: Int, val fontScale: Float, val dark: Boolean) {
    /** File-name suffix, e.g. `320dp_2.0x_dark`. */
    val suffix: String get() = "${widthDp}dp_${fontScale}x_${if (dark) "dark" else "light"}"
}

/**
 * Up to 1% of pixels may differ: absorbs anti-aliasing noise between machines, never a layout shift (which moves
 * whole text runs or tiles and changes far more than 1% of the image). CI's rendering is authoritative.
 */
const val SCREENSHOT_CHANGE_THRESHOLD = 0.01f

private val screenshotOptions = RoborazziOptions(
    compareOptions = RoborazziOptions.CompareOptions(changeThreshold = SCREENSHOT_CHANGE_THRESHOLD)
)

/** Renders [content] in [YaptTheme] at [config]'s font scale and theme, on the theme background. */
fun ComposeContentTestRule.setScreenshotContent(config: ScreenshotConfig, content: @Composable () -> Unit) {
    setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, config.fontScale)) {
            YaptTheme(darkTheme = config.dark) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
            }
        }
    }
}

/** Captures the whole window as `<surface>_<config suffix>.png` in the committed golden directory. */
fun ComposeContentTestRule.captureGolden(surface: String, config: ScreenshotConfig) {
    waitForIdle()
    onRoot().captureRoboImage(
        filePath = "${roborazziSystemPropertyOutputDirectory()}/${surface}_${config.suffix}.png",
        roborazziOptions = screenshotOptions
    )
}
