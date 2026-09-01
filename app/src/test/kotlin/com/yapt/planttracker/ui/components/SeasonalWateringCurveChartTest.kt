package com.yapt.planttracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ResolveMonthLabelStrategyTest {

    private val abbreviatedLabels = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    private val normalSp = 12f
    private val floorSp = 9f

    // Every glyph is 1px wide per sp — makes text width == text.length * fontSizeSp, a simple,
    // deterministic stand-in for a real font's glyph metrics.
    private val perGlyphMeasurer: (String, Float) -> Float = { text, fontSizeSp -> text.length * fontSizeSp }

    private fun resolve(availableWidthPx: Float) = resolveMonthLabelStrategy(
        availableWidthPx = availableWidthPx,
        monthLabels = abbreviatedLabels,
        measureTextWidthPx = perGlyphMeasurer,
        normalFontSizeSp = normalSp,
        floorFontSizeSp = floorSp,
    )

    @Test
    fun wideAvailableWidth_usesAbbreviatedAtNormalSize() {
        // "Jan" at 12sp = 3 * 12 = 36px per slot; give each of the 12 slots ample room.
        val strategy = resolve(availableWidthPx = 12 * 100f)
        assertEquals(MonthLabelTextCase.ABBREVIATED, strategy.textCase)
        assertEquals(normalSp, strategy.fontSizeSp, 0.001f)
        assertFalse(strategy.thinned)
    }

    @Test
    fun exactBoundary_abbreviatedFitsAtNormalSize() {
        // Slot width == widest label width exactly at normal size (3 * 12 = 36px per slot).
        val slotWidthPx = 3 * normalSp
        val strategy = resolve(availableWidthPx = slotWidthPx * 12)
        assertEquals(MonthLabelTextCase.ABBREVIATED, strategy.textCase)
        assertEquals(normalSp, strategy.fontSizeSp, 0.001f)
    }

    @Test
    fun justBelowNormalBoundary_shrinksAbbreviatedTowardFloor() {
        val slotWidthPx = 3 * normalSp - 1f
        val strategy = resolve(availableWidthPx = slotWidthPx * 12)
        assertEquals(MonthLabelTextCase.ABBREVIATED, strategy.textCase)
        assertTrue(strategy.fontSizeSp < normalSp)
        assertTrue(strategy.fontSizeSp >= floorSp)
        assertFalse(strategy.thinned)
    }

    @Test
    fun abbreviatedFitsExactlyAtFloor_usesFloorSize() {
        val slotWidthPx = 3 * floorSp
        val strategy = resolve(availableWidthPx = slotWidthPx * 12)
        assertEquals(MonthLabelTextCase.ABBREVIATED, strategy.textCase)
        assertEquals(floorSp, strategy.fontSizeSp, 0.001f)
    }

    @Test
    fun abbreviatedDoesNotFitAtFloor_fallsBackToSingleLetterAtNormalSize() {
        // Just below the abbreviated floor boundary (3 * 9 = 27px), but plenty of room for a
        // single letter at normal size (1 * 12 = 12px).
        val slotWidthPx = 3 * floorSp - 1f
        val strategy = resolve(availableWidthPx = slotWidthPx * 12)
        assertEquals(MonthLabelTextCase.SINGLE_LETTER, strategy.textCase)
        assertEquals(normalSp, strategy.fontSizeSp, 0.001f)
        assertFalse(strategy.thinned)
    }

    @Test
    fun singleLetterBoundary_fitsExactlyAtNormalSize() {
        val slotWidthPx = 1 * normalSp
        val strategy = resolve(availableWidthPx = slotWidthPx * 12)
        assertEquals(MonthLabelTextCase.SINGLE_LETTER, strategy.textCase)
        assertEquals(normalSp, strategy.fontSizeSp, 0.001f)
    }

    @Test
    fun singleLetterDoesNotFitAtNormal_shrinksTowardFloor() {
        val slotWidthPx = 1 * normalSp - 0.5f
        val strategy = resolve(availableWidthPx = slotWidthPx * 12)
        assertEquals(MonthLabelTextCase.SINGLE_LETTER, strategy.textCase)
        assertTrue(strategy.fontSizeSp < normalSp)
        assertTrue(strategy.fontSizeSp >= floorSp)
        assertFalse(strategy.thinned)
    }

    @Test
    fun singleLetterFitsExactlyAtFloor_usesFloorSize() {
        val slotWidthPx = 1 * floorSp
        val strategy = resolve(availableWidthPx = slotWidthPx * 12)
        assertEquals(MonthLabelTextCase.SINGLE_LETTER, strategy.textCase)
        assertEquals(floorSp, strategy.fontSizeSp, 0.001f)
        assertFalse(strategy.thinned)
    }

    @Test
    fun singleLetterDoesNotFitAtFloorWithAllTwelve_thinsToAlternatingMonths() {
        // Just below the single-letter floor boundary for 12 slots (1 * 9 = 9px), but plenty of
        // room once only 6 slots need a label (thinned).
        val slotWidthPx = 1 * floorSp - 0.5f
        val strategy = resolve(availableWidthPx = slotWidthPx * 12)
        assertEquals(MonthLabelTextCase.SINGLE_LETTER, strategy.textCase)
        assertTrue(strategy.thinned)
        assertTrue(strategy.fontSizeSp in floorSp..normalSp)
    }

    @Test
    fun thinnedBoundary_fitsExactlyAtFloorWithSixSlots() {
        val slotWidthPx = 1 * floorSp
        // 6 effective slots once thinned: total width = slotWidthPx * 6, but only 9px per slot at
        // the un-thinned 12-slot layout so the 12-slot floor check fails first.
        val strategy = resolve(availableWidthPx = slotWidthPx * 6)
        assertEquals(MonthLabelTextCase.SINGLE_LETTER, strategy.textCase)
        assertTrue(strategy.thinned)
        assertEquals(floorSp, strategy.fontSizeSp, 0.001f)
    }

    @Test
    fun extremelyNarrowWidth_stillReturnsThinnedAtFloorSize() {
        val strategy = resolve(availableWidthPx = 1f)
        assertEquals(MonthLabelTextCase.SINGLE_LETTER, strategy.textCase)
        assertTrue(strategy.thinned)
        assertEquals(floorSp, strategy.fontSizeSp, 0.001f)
    }

    @Test
    fun zeroWidth_stillReturnsThinnedAtFloorSize() {
        val strategy = resolve(availableWidthPx = 0f)
        assertEquals(MonthLabelTextCase.SINGLE_LETTER, strategy.textCase)
        assertTrue(strategy.thinned)
        assertEquals(floorSp, strategy.fontSizeSp, 0.001f)
    }

    @Test
    fun wrongLabelCount_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            resolveMonthLabelStrategy(
                availableWidthPx = 1000f,
                monthLabels = listOf("Jan", "Feb"),
                measureTextWidthPx = perGlyphMeasurer,
            )
        }
    }
}

class BuildMonthLabelMapTest {

    private val baseLabels = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    @Test
    fun abbreviated_notThinned_allTwelveLabeled() {
        val strategy = MonthLabelStrategy(MonthLabelTextCase.ABBREVIATED, fontSizeSp = 12f, thinned = false)
        val result = buildMonthLabelMap(baseLabels, strategy)
        assertEquals("Jan", result[0])
        assertEquals("Dec", result[11])
        assertEquals(12, result.values.count { it.isNotBlank() })
    }

    @Test
    fun singleLetter_notThinned_usesFirstLetter() {
        val strategy = MonthLabelStrategy(MonthLabelTextCase.SINGLE_LETTER, fontSizeSp = 9f, thinned = false)
        val result = buildMonthLabelMap(baseLabels, strategy)
        assertEquals("J", result[0])
        assertEquals("D", result[11])
    }

    @Test
    fun thinned_onlyEvenIndicesLabeled() {
        val strategy = MonthLabelStrategy(MonthLabelTextCase.SINGLE_LETTER, fontSizeSp = 9f, thinned = true)
        val result = buildMonthLabelMap(baseLabels, strategy)
        assertEquals("J", result[0])
        assertEquals(" ", result[1])
        assertEquals("M", result[2])
        assertEquals(" ", result[3])
    }
}
