package com.yapt.planttracker.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File

object ImageUtils {

    fun takePersistablePermission(context: Context, uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w("ImageUtils", "Could not take persistable permission for $uri", e)
        }
    }

    fun createCameraImageUri(context: Context): Uri {
        val imagesDir = File(context.filesDir, "images").also { it.mkdirs() }
        val imageFile = File(imagesDir, "plant_photo_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            imageFile
        )
    }
}
