package com.yapt.planttracker.util

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object ImageUtils {

    private const val CAMERA_IMAGE_MAX_DIMENSION = 1920
    private const val CAMERA_IMAGE_JPEG_QUALITY = 80
    private const val HALF_TURN_DEGREES = 180f
    private const val QUARTER_TURN_DEGREES = 90f

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

    /**
     * Bounds and re-encodes an in-app camera capture before its URI is persisted.
     *
     * The replacement is written beside the source and moved into place only after encoding succeeds, so a
     * processing failure leaves the camera's original file usable. Gallery-picker images are never copied or changed.
     */
    fun compressCameraImage(file: File): Boolean = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Camera image could not be decoded" }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxDimension = CAMERA_IMAGE_MAX_DIMENSION
            )
        }
        val decoded = requireNotNull(BitmapFactory.decodeFile(file.path, options)) {
            "Camera image could not be decoded"
        }
        val scaled = decoded.scaleToFit(CAMERA_IMAGE_MAX_DIMENSION)
        val oriented = scaled.applyExifOrientation(file)
        val temporary = File(file.parentFile, "${file.name}.processing")

        try {
            temporary.outputStream().buffered().use { output ->
                check(oriented.compress(Bitmap.CompressFormat.JPEG, CAMERA_IMAGE_JPEG_QUALITY, output)) {
                    "Camera image could not be encoded"
                }
            }
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } finally {
            temporary.delete()
            if (oriented !== scaled) oriented.recycle()
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
    }.onFailure { error ->
        Log.w("ImageUtils", "Could not compress camera image ${file.name}; keeping the original", error)
    }.isSuccess

    internal fun calculateInSampleSize(width: Int, height: Int, maxDimension: Int): Int {
        var sampleSize = 1
        while (width / (sampleSize * 2) >= maxDimension || height / (sampleSize * 2) >= maxDimension) {
            sampleSize *= 2
        }
        return sampleSize
    }

    @Suppress("CyclomaticComplexMethod") // EXIF defines eight independent orientation transforms.
    private fun Bitmap.applyExifOrientation(file: File): Bitmap {
        val orientation = ExifInterface(file.path).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL
        )
        val matrix = when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> Matrix().apply { setScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_180 -> Matrix().apply { setRotate(HALF_TURN_DEGREES) }
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> Matrix().apply { setScale(1f, -1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> Matrix().apply {
                setRotate(QUARTER_TURN_DEGREES)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> Matrix().apply { setRotate(QUARTER_TURN_DEGREES) }
            ExifInterface.ORIENTATION_TRANSVERSE -> Matrix().apply {
                setRotate(-QUARTER_TURN_DEGREES)
                postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> Matrix().apply { setRotate(-QUARTER_TURN_DEGREES) }
            else -> return this
        }
        return Bitmap.createBitmap(this, 0, 0, width, height, matrix, true)
    }

    private fun Bitmap.scaleToFit(maxDimension: Int): Bitmap {
        val longestSide = maxOf(width, height)
        if (longestSide <= maxDimension) return this
        val scale = maxDimension.toFloat() / longestSide
        return Bitmap.createScaledBitmap(this, (width * scale).toInt(), (height * scale).toInt(), true)
    }
}
