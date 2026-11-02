package com.yapt.planttracker.domain.away

/**
 * The page plan of the sitter PDF (product ADR-0065), in PDF points on A4 portrait. Pure: it decides which
 * room headers and tile rows land on which page, and `SitterListPdfRenderer` only draws the result, so page
 * breaks are testable on the JVM. Page 1 also carries the watering-tips box, so its body is shorter than the rest.
 */
object SitterPdfLayout {

    const val PAGE_WIDTH = 595
    const val PAGE_HEIGHT = 842
    const val MARGIN = 36
    const val BAND_HEIGHT = 32
    const val HEADER_HEIGHT = 96
    const val FOOTER_HEIGHT = 50
    const val TIPS_HEIGHT = 86
    const val COLUMNS = 3
    const val TILE_GAP = 12
    const val TILE_HEIGHT = 190
    const val ROW_GAP = 8
    const val ROOM_HEADER_HEIGHT = 30

    const val BODY_HEIGHT = PAGE_HEIGHT - 2 * MARGIN - HEADER_HEIGHT - FOOTER_HEIGHT
    const val TILE_WIDTH = (PAGE_WIDTH - 2 * MARGIN - (COLUMNS - 1) * TILE_GAP) / COLUMNS

    const val FIRST_PAGE_BODY_HEIGHT = BODY_HEIGHT - TIPS_HEIGHT

    private const val ROW_HEIGHT = TILE_HEIGHT + ROW_GAP

    enum class PageKind { VISIT, RESTING }

    sealed interface Block {
        /** [continued] marks a room whose plants carry over from the previous page. */
        data class RoomHeader(val room: String?, val continued: Boolean) : Block

        /** Up to [COLUMNS] plants side by side. */
        data class TileRow(val plants: List<SitterListPlant>) : Block
    }

    /** [showsTips] is true only on page 1, whose body is [TIPS_HEIGHT] shorter. */
    data class Page(val kind: PageKind, val blocks: List<Block>, val showsTips: Boolean = false)

    /** Visit pages first, then the "Please don't water these" pages; none of the latter when nothing rests. */
    fun paginate(list: SitterList): List<Page> =
        paginate(list.rooms, PageKind.VISIT) + paginate(list.restingRooms, PageKind.RESTING)

    private fun paginate(rooms: List<SitterListRoom>, kind: PageKind): List<Page> {
        val pages = mutableListOf<Page>()
        var blocks = mutableListOf<Block>()
        var used = 0

        fun showsTips() = kind == PageKind.VISIT && pages.isEmpty()

        fun capacity() = if (showsTips()) FIRST_PAGE_BODY_HEIGHT else BODY_HEIGHT

        fun breakPage() {
            if (blocks.isNotEmpty()) pages += Page(kind, blocks, showsTips())
            blocks = mutableListOf()
            used = 0
        }

        for (room in rooms) {
            val rows = room.plants.chunked(COLUMNS)
            if (used + ROOM_HEADER_HEIGHT + ROW_HEIGHT > capacity()) breakPage()
            blocks += Block.RoomHeader(room.room, continued = false)
            used += ROOM_HEADER_HEIGHT
            rows.forEachIndexed { index, row ->
                if (index > 0 && used + ROW_HEIGHT > capacity()) {
                    breakPage()
                    blocks += Block.RoomHeader(room.room, continued = true)
                    used += ROOM_HEADER_HEIGHT
                }
                blocks += Block.TileRow(row)
                used += ROW_HEIGHT
            }
        }
        breakPage()
        return pages
    }
}
