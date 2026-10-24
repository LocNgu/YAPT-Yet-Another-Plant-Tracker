package com.yapt.planttracker.ui.screens.today

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.today.TodayWateredPlant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Watered today row (#939) lays out six equal slots per line. Slot widths are whole pixels, so a row whose
 * pixel width is not a multiple of six must not round them up past the row, or the sixth slot wraps.
 */
@RunWith(AndroidJUnit4::class)
class WateredTodayRowTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun watered(count: Int) = (1..count).map {
        TodayWateredPlant(Plant(id = ID_BASE + it, name = "Plant $it", createdAt = 0L, updatedAt = 0L), WATERED_AT - it)
    }

    private fun setRow(rowWidthPx: Int, plantCount: Int, expanded: Boolean) {
        composeTestRule.setContent {
            val density = Density(DENSITY, LocalDensity.current.fontScale)
            CompositionLocalProvider(LocalDensity provides density) {
                Box(Modifier.requiredWidth(with(density) { rowWidthPx.toDp() })) {
                    WateredTodayRow(
                        plants = watered(plantCount),
                        expanded = expanded,
                        onToggleExpanded = {},
                        onOpen = {}
                    )
                }
            }
        }
    }

    private fun topOf(contentDescription: String) =
        composeTestRule.onNodeWithContentDescription(contentDescription).fetchSemanticsNode().positionInRoot.y

    private fun photo(index: Int) = "Plant $index, watered today"

    @Test
    fun plusCircleSharesALineWithTheFivePhotosWhenTheRowWidthLeavesARemainderOfFour() {
        setRow(rowWidthPx = WIDTH_REMAINDER_FOUR, plantCount = 7, expanded = false)

        assertEquals(topOf(photo(1)), topOf(photo(5)), 0f)
        assertEquals(topOf(photo(1)), topOf(MORE_TWO), 0f)
    }

    @Test
    fun plusCircleSharesALineWithTheFivePhotosWhenTheRowWidthLeavesARemainderOfFive() {
        setRow(rowWidthPx = WIDTH_REMAINDER_FIVE, plantCount = 7, expanded = false)

        assertEquals(topOf(photo(1)), topOf(photo(5)), 0f)
        assertEquals(topOf(photo(1)), topOf(MORE_TWO), 0f)
    }

    @Test
    fun sixPhotosFillTheFirstLineWhenExpandedAtAWidthThatLeavesARemainder() {
        setRow(rowWidthPx = WIDTH_REMAINDER_FOUR, plantCount = 12, expanded = true)

        assertEquals(topOf(photo(1)), topOf(photo(6)), 0f)
        assertTrue(topOf(photo(7)) > topOf(photo(1)))
    }

    private companion object {
        const val DENSITY = 2.8125f
        const val WIDTH_REMAINDER_FOUR = 1012
        const val WIDTH_REMAINDER_FIVE = 1013
        const val ID_BASE = 100L
        const val WATERED_AT = 1_700_000_000_000L
        const val MORE_TWO = "Show 2 more watered plants"
    }
}
