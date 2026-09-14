package com.yapt.planttracker.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.yapt.planttracker.data.preferences.SettingsKeys
import kotlinx.coroutines.flow.first
import java.io.File

/** Compresses camera captures created before #309 exactly once after the app is updated. */
object ExistingCameraPhotoCompression {

    suspend fun runIfNeeded(
        context: Context,
        dataStore: DataStore<Preferences>,
        imagesDir: File = context.filesDir.resolve("images")
    ): Int {
        val alreadyDone = dataStore.data.first()[SettingsKeys.CAMERA_PHOTO_COMPRESSION_FIXUP_DONE] ?: false
        if (alreadyDone) return 0

        val images = imagesDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("jpg", ignoreCase = true) }
            .orEmpty()
        val compressed = images.count(ImageUtils::compressCameraImage)
        dataStore.edit { it[SettingsKeys.CAMERA_PHOTO_COMPRESSION_FIXUP_DONE] = true }
        return compressed
    }
}
