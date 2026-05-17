package com.yapt.planttracker.ui.screens.whatsnew

import com.yapt.planttracker.BuildConfig

data class ReleaseNotes(
    val versionName: String,
    val added: List<String> = emptyList(),
    val fixed: List<String> = emptyList(),
    val changed: List<String> = emptyList()
)

object WhatsNewContent {
    val current = ReleaseNotes(
        versionName = BuildConfig.VERSION_NAME,
        fixed = listOf(
            "Keyboard no longer covers the Notes field when editing a plant or care log"
        )
    )
}
