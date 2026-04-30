package com.yapt.planttracker.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColorScheme = lightColorScheme(
    primary = SageGreen,
    onPrimary = OnSageGreen,
    primaryContainer = SageGreenContainer,
    onPrimaryContainer = OnSageGreenContainer,
    secondary = EarthBrown,
    onSecondary = OnEarthBrown,
    secondaryContainer = EarthBrownContainer,
    onSecondaryContainer = DarkBark,
    tertiary = MossGrey,
    background = WarmCream,
    onBackground = DarkBark,
    surface = Color.White,
    onSurface = DarkBark,
    surfaceVariant = SoftMoss,
    onSurfaceVariant = SageGreenDark,
    outline = MossGrey
)

private val DarkColorScheme = darkColorScheme(
    primary = SageGreenLight,
    onPrimary = SageGreenDark,
    primaryContainer = SageGreenContainerDark,
    onPrimaryContainer = SageGreenLight,
    secondary = EarthBrownLight,
    onSecondary = EarthBrownDark,
    background = BackgroundDark,
    onBackground = Color(0xFFE8F5E9),
    surface = SurfaceDark,
    onSurface = Color(0xFFE8F5E9),
    surfaceVariant = Color(0xFF2C3B2E),
    onSurfaceVariant = SageGreenLight
)

@Composable
fun YaptTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = YaptTypography,
        content = content
    )
}
