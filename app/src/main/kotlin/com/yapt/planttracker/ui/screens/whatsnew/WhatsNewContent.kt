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
            "Restoring a large backup with many photos no longer crashes the app (photos are now streamed instead of loaded into memory)",
            "Exporting a backup to cloud destinations (e.g. Google Drive) no longer produces a broken empty ZIP file",
            "Temporary files are cleaned up correctly when you cancel the schema-warning dialog during a restore"
        )
    )
}
