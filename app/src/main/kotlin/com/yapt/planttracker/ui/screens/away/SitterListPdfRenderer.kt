package com.yapt.planttracker.ui.screens.away

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import android.text.TextUtils
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.away.SitterList
import com.yapt.planttracker.domain.away.SitterListPlant
import com.yapt.planttracker.domain.away.SitterPdfLayout
import com.yapt.planttracker.domain.away.SitterPdfLayout.Block
import com.yapt.planttracker.domain.away.SitterPdfLayout.Page
import com.yapt.planttracker.domain.away.SitterPdfLayout.PageKind
import java.io.File
import java.io.IOException
import java.time.LocalDate

/**
 * Draws the sitter PDF (A4 portrait, `android.graphics.pdf.PdfDocument`) from [SitterPdfLayout]'s page plan and
 * the string resources, offline. A plant without a (readable) photo gets a dashed "No photo" box.
 */
internal object SitterListPdfRenderer {

    @Throws(IOException::class)
    fun write(context: Context, list: SitterList, file: File) {
        val pages = SitterPdfLayout.paginate(list)
        val pens = Pens()
        val document = PdfDocument()
        try {
            pages.forEachIndexed { index, page ->
                val info = PdfDocument.PageInfo
                    .Builder(SitterPdfLayout.PAGE_WIDTH, SitterPdfLayout.PAGE_HEIGHT, index + 1)
                    .create()
                val pdfPage = document.startPage(info)
                PagePainter(context, pdfPage.canvas, pens, list, pages.size).draw(page, index + 1)
                document.finishPage(pdfPage)
            }
            file.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }
}

private const val TITLE_SIZE = 20f
private const val SUBTITLE_SIZE = 13f
private const val ROOM_SIZE = 14f
private const val NAME_SIZE = 11f
private const val SMALL_SIZE = 9f
private const val TAG_SIZE = 8f
private const val FOOTER_SIZE = 10f
private const val DASH = 6f
private val INK = Color.rgb(33, 37, 41)
private val MUTED = Color.rgb(108, 117, 125)
private val ACCENT = Color.rgb(121, 85, 72)
private val TAG_FILL = Color.rgb(255, 224, 178)

private class Pens {
    private fun text(size: Float, tint: Int, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        color = tint
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun stroke(pattern: DashPathEffect? = null) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = MUTED
        pathEffect = pattern
    }

    val title = text(TITLE_SIZE, INK, bold = true)
    val subtitle = text(SUBTITLE_SIZE, MUTED)
    val room = text(ROOM_SIZE, ACCENT, bold = true)
    val name = text(NAME_SIZE, INK, bold = true)
    val species = text(SMALL_SIZE, MUTED)
    val tag = text(TAG_SIZE, INK, bold = true)
    val footer = text(FOOTER_SIZE, MUTED)
    val outline = stroke()
    val dashed = stroke(DashPathEffect(floatArrayOf(DASH, DASH), 0f))
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = TAG_FILL
    }
    val bitmap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
}

private class PagePainter(
    private val context: Context,
    private val canvas: Canvas,
    private val pens: Pens,
    private val list: SitterList,
    private val pageCount: Int
) {
    private val contentWidth = (SitterPdfLayout.PAGE_WIDTH - 2 * SitterPdfLayout.MARGIN).toFloat()
    private val left = SitterPdfLayout.MARGIN.toFloat()

    fun draw(page: Page, pageNumber: Int) {
        drawHeader(page.kind)
        var y = (SitterPdfLayout.MARGIN + SitterPdfLayout.HEADER_HEIGHT).toFloat()
        for (block in page.blocks) {
            when (block) {
                is Block.RoomHeader -> {
                    drawRoomHeader(block, y)
                    y += SitterPdfLayout.ROOM_HEADER_HEIGHT
                }
                is Block.TileRow -> {
                    block.plants.forEachIndexed { column, plant ->
                        val x = left + column * (SitterPdfLayout.TILE_WIDTH + SitterPdfLayout.TILE_GAP)
                        drawTile(plant, x, y, page.kind)
                    }
                    y += SitterPdfLayout.TILE_HEIGHT + SitterPdfLayout.ROW_GAP
                }
            }
        }
        drawFooter(page.kind, pageNumber)
    }

    private fun drawHeader(kind: PageKind) {
        val resources = context.resources
        val (title, subtitle) = when (kind) {
            PageKind.VISIT -> context.getString(
                R.string.sitter_visit_title,
                awayWeekdayDayText(list.visitDay, LocalDate.now())
            ) to resources.getQuantityString(R.plurals.sitter_water_count, list.plantCount, list.plantCount)
            PageKind.RESTING -> context.getString(R.string.sitter_resting_title) to
                resources.getQuantityString(R.plurals.sitter_resting_subtitle, list.restingCount, list.restingCount)
        }
        val top = SitterPdfLayout.MARGIN.toFloat()
        canvas.drawText(ellipsized(title, pens.title, contentWidth), left, top + pens.title.textSize, pens.title)
        canvas.drawText(subtitle, left, top + pens.title.textSize + pens.subtitle.textSize + 6f, pens.subtitle)
    }

    private fun drawRoomHeader(block: Block.RoomHeader, y: Float) {
        val name = block.room ?: context.getString(R.string.sitter_room_none)
        val text = if (block.continued) context.getString(R.string.sitter_room_continued, name) else name
        canvas.drawText(ellipsized(text, pens.room, contentWidth), left, y + pens.room.textSize + 4f, pens.room)
    }

    private fun drawTile(plant: SitterListPlant, x: Float, y: Float, kind: PageKind) {
        val width = SitterPdfLayout.TILE_WIDTH.toFloat()
        val photoBox = RectF(x, y, x + width, y + PHOTO_HEIGHT)
        drawPhoto(plant, photoBox)
        val textWidth = width - TILE_PADDING * 2
        var textY = photoBox.bottom + pens.name.textSize + TILE_PADDING
        canvas.drawText(ellipsized(plant.name, pens.name, textWidth), x + TILE_PADDING, textY, pens.name)
        plant.species?.let {
            textY += pens.species.textSize + 2f
            canvas.drawText(ellipsized(it, pens.species, textWidth), x + TILE_PADDING, textY, pens.species)
        }
        if (kind == PageKind.VISIT) {
            val boxLeft = x + TILE_PADDING
            val boxTop = y + SitterPdfLayout.TILE_HEIGHT - CHECKBOX_SIZE - TILE_PADDING
            canvas.drawRect(boxLeft, boxTop, boxLeft + CHECKBOX_SIZE, boxTop + CHECKBOX_SIZE, pens.outline)
            if (plant.missedLastTime) drawMissedTag(boxLeft + CHECKBOX_SIZE + TILE_PADDING, boxTop)
        }
    }

    private fun drawPhoto(plant: SitterListPlant, box: RectF) {
        val bitmap = plant.coverPhotoUri?.let { SitterPhotoLoader.load(context, it) }
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, SitterPhotoLoader.centerCrop(bitmap, box), box, pens.bitmap)
            bitmap.recycle()
            return
        }
        canvas.drawRoundRect(box, CORNER, CORNER, pens.dashed)
        val label = context.getString(R.string.sitter_no_photo)
        canvas.drawText(
            label,
            box.centerX() - pens.species.measureText(label) / 2,
            box.centerY() + pens.species.textSize / 2,
            pens.species
        )
    }

    private fun drawMissedTag(tagLeft: Float, rowTop: Float) {
        val label = context.getString(R.string.sitter_missed_tag)
        val tagTop = rowTop - 1f
        val box = RectF(
            tagLeft,
            tagTop,
            tagLeft + pens.tag.measureText(label) + TAG_PADDING * 2,
            tagTop + TAG_HEIGHT
        )
        canvas.drawRoundRect(box, CORNER, CORNER, pens.fill)
        canvas.drawText(label, box.left + TAG_PADDING, box.bottom - 4f, pens.tag)
    }

    private fun drawFooter(kind: PageKind, pageNumber: Int) {
        val baseline = (SitterPdfLayout.PAGE_HEIGHT - SitterPdfLayout.MARGIN).toFloat()
        if (kind == PageKind.VISIT) {
            canvas.drawText(context.getString(R.string.sitter_footer_only_water), left, baseline, pens.footer)
        }
        val pageText = context.getString(R.string.sitter_footer_page, pageNumber, pageCount)
        canvas.drawText(pageText, left + contentWidth - pens.footer.measureText(pageText), baseline, pens.footer)
    }

    private fun ellipsized(text: String, paint: TextPaint, width: Float): String =
        TextUtils.ellipsize(text, paint, width, TextUtils.TruncateAt.END).toString()

    private companion object {
        const val PHOTO_HEIGHT = 120f
        const val TILE_PADDING = 4f
        const val CHECKBOX_SIZE = 12f
        const val TAG_HEIGHT = 14f
        const val TAG_PADDING = 5f
        const val CORNER = 4f
    }
}
