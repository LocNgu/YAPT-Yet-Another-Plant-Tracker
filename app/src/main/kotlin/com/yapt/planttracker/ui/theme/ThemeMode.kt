package com.yapt.planttracker.ui.theme

/**
 * User-selectable app theme. [SYSTEM] follows the device's light/dark setting (the previous
 * always-on behaviour and the default); [LIGHT]/[DARK] force that theme regardless of the system.
 * Stored as its [name] String in DataStore — read with `runCatching { valueOf(...) }.getOrDefault(SYSTEM)`.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}
