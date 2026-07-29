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
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w("ImageUtils", "Could not take persistable permission for $uri", e)
        }
    }

    fun createCameraImageFile(context: Context): File {
        val imagesDir = File(context.filesDir, "images").also { it.mkdirs() }
        return File(imagesDir, "plant_photo_${System.currentTimeMillis()}.jpg")
    }

    fun createCameraImageUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
