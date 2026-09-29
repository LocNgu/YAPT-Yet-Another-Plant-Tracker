package com.yapt.planttracker.ui.screens.today

import org.junit.Assert.assertEquals
import org.junit.Test

class CareGridColumnsTest {

    // dp == px at density 1: a 320dp screen minus the grid's 12dp side padding leaves 296.
    private fun columns(availableDp: Int) =
        careGridColumnCount(availablePx = availableDp, spacingPx = SPACING, minTilePx = MIN_TILE)

    @Test
    fun `a 320dp phone gets two tiles per row`() {
        assertEquals(2, columns(availableDp = 296))
    }

    @Test
    fun `common phone widths stay at two and tablets grow to three then cap at four`() {
        assertEquals(2, columns(availableDp = 411 - 24))
        assertEquals(3, columns(availableDp = 600 - 24))
        assertEquals(4, columns(availableDp = 840 - 24))
    }

    @Test
    fun `column count never drops below two or exceeds four`() {
        assertEquals(2, columns(availableDp = 150))
        assertEquals(4, columns(availableDp = 2000))
    }

    private companion object {
        const val SPACING = 12
        const val MIN_TILE = 140
    }
}
