package com.yapt.planttracker.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

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

class SeasonalCurveYAxisTicksTest {

    @Test
    fun fixedFiveTicks_halfRangeAndStep() {
        assertEquals(listOf(0.5, 0.75, 1.0, 1.25, 1.5), seasonalCurveYAxisTicks())
    }
}

class SeasonalCurveDayTickLabelsTest {

    @Test
    fun noDuplicates_everyTickLabeled() {
        // baseIntervalDays = 20 → 10, 15, 20, 25, 30 — all distinct.
        val labels = seasonalCurveDayTickLabels(20.0)
        assertEquals(listOf("10d", "15d", "20d", "25d", "30d"), labels)
    }

    @Test
    fun adjacentDuplicate_laterTickBlanked() {
        // baseIntervalDays = 2 → ticks round to 1, 2, 2, 3, 3 — two adjacent-pair collisions;
        // each pair's later tick is blanked, its earlier tick keeps the label.
        val labels = seasonalCurveDayTickLabels(2.0)
        val roundedDays = seasonalCurveYAxisTicks().map { Math.round(it * 2.0).toInt() }
        assertEquals(listOf(1, 2, 2, 3, 3), roundedDays)
        assertEquals(listOf("1d", "2d", "", "3d", ""), labels)
    }

    @Test
    fun firstTickNeverBlanked_evenWithDegenerateBase() {
        val labels = seasonalCurveDayTickLabels(0.0)
        assertEquals(listOf("0d", "", "", "", ""), labels)
    }

    @Test
    fun customTicks_walkedInGivenOrder() {
        // 10, 10.2, 10.4, 20 → rounds to 10, 10, 10, 20: two interior duplicates blanked in a row,
        // the run's first tick keeps its label, and the fresh value after the run is never blanked.
        val labels = seasonalCurveDayTickLabels(10.0, ticks = listOf(1.0, 1.02, 1.04, 2.0))
        assertEquals(listOf("10d", "", "", "20d"), labels)
    }

    @Test
    fun roundingMatchesRoundHalfUp() {
        // 0.75 * 10 = 7.5 → roundToInt() rounds half-up to 8.
        val labels = seasonalCurveDayTickLabels(10.0)
        assertEquals("8d", labels[1])
    }
}

// #638 regression: a pure test on seasonalCurveDayTickLabels() alone would still pass even though
// the app crashes on-device, since that function's "" result is by design. These tests exercise
// seasonalCurveLabeledTicks() instead — the exact function wired into the real Vico ItemPlacer
// (DayLabelItemPlacer) — asserting the set of tick *values* actually handed to Vico for labeling
// never includes one whose seasonalCurveDayTickLabels() entry is "".
class SeasonalCurveLabeledTicksTest {

    @Test
    fun noDuplicates_allTicksLabeled() {
        assertEquals(seasonalCurveYAxisTicks(), seasonalCurveLabeledTicks(20.0))
    }

    @Test
    fun adjacentDuplicates_blankedTicksExcluded() {
        // baseIntervalDays = 2 → ticks round to 1, 2, 2, 3, 3 (see SeasonalCurveDayTickLabelsTest);
        // indices 2 and 4 (1.0× and 1.5×) are blanked and must be excluded here.
        val labeled = seasonalCurveLabeledTicks(2.0)
        assertEquals(listOf(0.5, 0.75, 1.25), labeled)
    }

    @Test
    fun degenerateBase_onlyFirstTickLabeled() {
        assertEquals(listOf(0.5), seasonalCurveLabeledTicks(0.0))
    }

    @Test
    fun neverIncludesATickWhoseLabelIsBlank_acrossManyBaseIntervals() {
        for (baseIntervalDays in listOf(0.0, 1.0, 2.0, 2.5, 3.0, 4.0, 5.0, 7.0, 10.0, 14.0, 20.0, 30.0, 45.0, 90.0)) {
            val ticks = seasonalCurveYAxisTicks()
            val labels = seasonalCurveDayTickLabels(baseIntervalDays, ticks)
            val blankedTicks = ticks.filterIndexed { index, _ -> labels[index].isEmpty() }
            val labeled = seasonalCurveLabeledTicks(baseIntervalDays, ticks)
            blankedTicks.forEach { blanked ->
                assertFalse(
                    "baseIntervalDays=$baseIntervalDays: blanked tick $blanked must not be labeled",
                    labeled.contains(blanked),
                )
            }
        }
    }

    @Test
    fun dayFormatterFormula_neverProducesBlankForAnyLabeledTick() {
        // Mirrors rememberSeasonalCurveYAxisFormatter()'s day-label branch exactly: "${(y *
        // baseIntervalDays).roundToInt()}d" for any y handed to it. Since the ItemPlacer now
        // guarantees the formatter only ever sees a labeled tick's value, this must never be blank.
        for (baseIntervalDays in listOf(0.0, 1.0, 2.0, 2.5, 3.0, 4.0, 5.0, 7.0, 10.0, 14.0, 20.0, 30.0, 45.0, 90.0)) {
            val labeled = seasonalCurveLabeledTicks(baseIntervalDays)
            labeled.forEach { y ->
                val formatted = "${(y * baseIntervalDays).roundToInt()}d"
                assertTrue(
                    "baseIntervalDays=$baseIntervalDays, y=$y produced blank label",
                    formatted.isNotEmpty(),
                )
            }
        }
    }
}
