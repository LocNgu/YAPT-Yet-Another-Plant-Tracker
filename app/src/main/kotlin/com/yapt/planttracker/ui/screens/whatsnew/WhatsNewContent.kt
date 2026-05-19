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
        added = listOf(
            "Keep screen on toggle in Settings — screen stays awake while you water your plants"
        ),
        fixed = listOf(
            "Keyboard no longer covers the Notes field when editing a plant or care log"
        )
    )
}
