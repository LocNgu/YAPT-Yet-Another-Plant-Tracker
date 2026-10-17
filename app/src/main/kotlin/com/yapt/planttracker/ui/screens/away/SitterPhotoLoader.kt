package com.yapt.planttracker.ui.screens.away

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.net.Uri
import androidx.core.net.toUri
import com.yapt.planttracker.util.ImageUtils
import java.io.File
import java.io.InputStream

/** Reads a plant's cover photo for the sitter PDF, downscaled while decoding so 70-odd photos stay cheap. */
internal object SitterPhotoLoader {

    private const val PHOTO_MAX_DIMENSION = 480
    private const val DEGREES_90 = 90f
    private const val DEGREES_180 = 180f
    private const val DEGREES_270 = 270f

    /** A downscaled, upright copy of the photo at [uriString], or `null` when it can't be read. */
    fun load(context: Context, uriString: String): Bitmap? = runCatching {
        val uri = photoUri(uriString)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageUtils.calculateInSampleSize(bounds.outWidth, bounds.outHeight, PHOTO_MAX_DIMENSION)
        }
        val decoded = context.contentResolver.openInputStream(uri)
            ?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: return@runCatching null
        val degrees = context.contentResolver.openInputStream(uri)?.use { rotationDegrees(it) } ?: 0f
        if (degrees == 0f) decoded else rotated(decoded, degrees)
    }.getOrNull()

    /**
     * The stored cover reference as a URI `ContentResolver` can open. A `.yapt` restore stores a bare absolute path
     * (no scheme), which only a `file://` URI reaches; camera and gallery covers are already `content://`.
     */
    fun photoUri(ref: String): Uri = if (ref.contains("://")) ref.toUri() else Uri.fromFile(File(ref))

    /** The source rectangle that fills [box] from the middle of [bitmap] without distortion. */
    fun centerCrop(bitmap: Bitmap, box: RectF): Rect {
        val scale = maxOf(box.width() / bitmap.width, box.height() / bitmap.height)
        val srcWidth = (box.width() / scale).toInt().coerceAtMost(bitmap.width)
        val srcHeight = (box.height() / scale).toInt().coerceAtMost(bitmap.height)
        val left = (bitmap.width - srcWidth) / 2
        val top = (bitmap.height - srcHeight) / 2
        return Rect(left, top, left + srcWidth, top + srcHeight)
    }

    private fun rotationDegrees(stream: InputStream): Float = when (
        ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    ) {
        ExifInterface.ORIENTATION_ROTATE_90 -> DEGREES_90
        ExifInterface.ORIENTATION_ROTATE_180 -> DEGREES_180
        ExifInterface.ORIENTATION_ROTATE_270 -> DEGREES_270
        else -> 0f
    }

    private fun rotated(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix().apply { setRotate(degrees) }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }
}
