package com.yapt.planttracker.ui.screens.away

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.away.SitterList
import com.yapt.planttracker.domain.away.SitterPdfLayout
import com.yapt.planttracker.domain.away.SitterPdfLayout.PageKind
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * Hands the sitter list to the Android share sheet (product ADR-0065). The PDF is a cache file shared through
 * the app's `FileProvider` (`cache-path` `sitter_lists/`), never a photo, so `OrphanPhotoSweeper` doesn't look
 * at it; the next share deletes the previous one.
 */
object SitterShare {

    const val CACHE_SUBDIR = "sitter_lists"
    const val PDF_MIME_TYPE = "application/pdf"
    const val TEXT_MIME_TYPE = "text/plain"

    /** A fresh file in the share cache for [day]'s list, after clearing whatever an earlier share left there. */
    @Throws(IOException::class)
    fun newPdfFile(context: Context, day: LocalDate): File {
        val directory = File(context.cacheDir, CACHE_SUBDIR)
        directory.listFiles()?.forEach { it.delete() }
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create $directory")
        return File(directory, "plant-list-$day.pdf")
    }

    fun uriFor(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** The `ACTION_SEND` intent for [file]: `application/pdf`, a read grant on the URI, and an editable caption. */
    fun pdfIntent(context: Context, file: File, caption: String): Intent {
        val uri = uriFor(context, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = PDF_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, caption)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun textIntent(text: String): Intent = Intent(Intent.ACTION_SEND).apply {
        type = TEXT_MIME_TYPE
        putExtra(Intent.EXTRA_TEXT, text)
    }

    fun sharePdf(context: Context, file: File, caption: String) = launch(context, pdfIntent(context, file, caption))

    fun shareText(context: Context, text: String) = launch(context, textIntent(text))

    private fun launch(context: Context, send: Intent) {
        val chooser = Intent.createChooser(send, context.getString(R.string.sitter_share_chooser_title))
        if (context !is android.app.Activity) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}

/** The Android-backed [SitterListOutput]: the PDF and text renderers, and the caption the owner can edit. */
class AndroidSitterListOutput(private val application: Application) : SitterListOutput {

    override fun writePdf(list: SitterList): File {
        val file = SitterShare.newPdfFile(application, list.visitDay)
        SitterListPdfRenderer.write(application, list, file)
        return file
    }

    override fun text(list: SitterList): String = SitterListTextRenderer.render(application, list)

    override fun caption(list: SitterList): String {
        val pages = SitterPdfLayout.paginate(list)
        val visitPages = pages.count { it.kind == PageKind.VISIT }
        val restingFrom = visitPages + 1
        val hasResting = pages.any { it.kind == PageKind.RESTING }
        val day = awayWeekdayDayText(list.visitDay, LocalDate.now())
        return when {
            visitPages == 1 && !hasResting -> application.getString(R.string.sitter_caption_single, day)
            visitPages == 1 -> application.getString(R.string.sitter_caption_single_resting, day, restingFrom)
            !hasResting -> application.getString(R.string.sitter_caption_multi, day, visitPages)
            else -> application.getString(R.string.sitter_caption_multi_resting, day, visitPages, restingFrom)
        }
    }
}
