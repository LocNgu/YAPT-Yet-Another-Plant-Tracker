package com.yapt.planttracker.ui.screens.whatsnew

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.ui.theme.YaptTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The What's New sheet opens with only the newest release expanded (or, after skipped updates, every
 * unseen one) and folds older ones under their version header (#958, product ADR-0072). Assertions are on
 * the header's state description and on whether entry text exists — never on tree structure (#420). The fixtures are declared out of order on purpose.
 */
@RunWith(AndroidJUnit4::class)
class WhatsNewSheetTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val oldest = ReleaseNotes(
        versionCode = OLDEST_CODE,
        versionName = OLDEST_NAME,
        added = listOf(OLDEST_ENTRY)
    )
    private val newest = ReleaseNotes(
        versionCode = NEWEST_CODE,
        versionName = NEWEST_NAME,
        added = listOf(NEWEST_ADDED),
        fixed = listOf(NEWEST_FIXED),
        changed = listOf(NEWEST_CHANGED)
    )
    private val middle = ReleaseNotes(
        versionCode = MIDDLE_CODE,
        versionName = MIDDLE_NAME,
        fixed = listOf(MIDDLE_ENTRY)
    )

    private fun string(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val expanded get() = string(R.string.whats_new_release_state_expanded)
    private val collapsed get() = string(R.string.whats_new_release_state_collapsed)

    private fun setSheet(
        releases: List<ReleaseNotes> = listOf(oldest, newest, middle),
        lastSeenVersionCode: Int? = null,
        onDismiss: () -> Unit = {}
    ) {
        composeTestRule.setContent {
            YaptTheme {
                WhatsNewSheet(onDismiss = onDismiss, releases = releases, lastSeenVersionCode = lastSeenVersionCode)
            }
        }
    }

    private fun header(versionName: String) = hasText(versionName) and isHeading()

    private fun hasState(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private fun entry(text: String) = hasText(text, substring = true)

    @Test
    fun newestRelease_startsExpanded_whateverOrderTheListIsIn() {
        setSheet()

        composeTestRule.onNode(header(NEWEST_NAME)).assert(hasState(expanded))
        composeTestRule.onNode(entry(NEWEST_ADDED)).assertIsDisplayed()
    }

    @Test
    fun olderRelease_startsCollapsed_withoutItsEntries() {
        setSheet()

        composeTestRule.onNode(header(MIDDLE_NAME)).assert(hasState(collapsed))
        composeTestRule.onNode(header(OLDEST_NAME)).assert(hasState(collapsed))
        composeTestRule.onNode(entry(MIDDLE_ENTRY)).assertDoesNotExist()
        composeTestRule.onNode(entry(OLDEST_ENTRY)).assertDoesNotExist()
    }

    @Test
    fun afterSkippedUpdates_everyUnseenReleaseStartsExpanded_andSeenOnesStayCollapsed() {
        setSheet(lastSeenVersionCode = OLDEST_CODE)

        composeTestRule.onNode(header(NEWEST_NAME)).assert(hasState(expanded))
        composeTestRule.onNode(header(MIDDLE_NAME)).assert(hasState(expanded))
        // Below the half-expanded sheet's fold; a collapsed release composes nothing, so existence is the contract.
        composeTestRule.onNode(entry(MIDDLE_ENTRY)).assertExists()
        composeTestRule.onNode(header(OLDEST_NAME)).assert(hasState(collapsed))
        composeTestRule.onNode(entry(OLDEST_ENTRY)).assertDoesNotExist()
    }

    @Test
    fun tappingAnOlderHeader_expandsIt_andKeepsTheNewestExpanded() {
        setSheet()

        composeTestRule.onNode(header(MIDDLE_NAME)).performClick()

        composeTestRule.onNode(header(MIDDLE_NAME)).assert(hasState(expanded))
        // The sheet opens half-expanded, so this entry is composed below the fold; a collapsed release
        // composes nothing, so existence is the expand contract here.
        composeTestRule.onNode(entry(MIDDLE_ENTRY)).assertExists()
        composeTestRule.onNode(header(NEWEST_NAME)).assert(hasState(expanded))
        composeTestRule.onNode(entry(NEWEST_ADDED)).assertIsDisplayed()
        composeTestRule.onNode(header(OLDEST_NAME)).assert(hasState(collapsed))
    }

    @Test
    fun tappingAnExpandedHeader_collapsesIt_theNewestIncluded() {
        setSheet()

        composeTestRule.onNode(header(NEWEST_NAME)).performClick()

        composeTestRule.onNode(header(NEWEST_NAME)).assert(hasState(collapsed))
        composeTestRule.onNode(entry(NEWEST_ADDED)).assertDoesNotExist()

        composeTestRule.onNode(header(NEWEST_NAME)).performClick()

        composeTestRule.onNode(header(NEWEST_NAME)).assert(hasState(expanded))
        composeTestRule.onNode(entry(NEWEST_ADDED)).assertIsDisplayed()
    }

    @Test
    fun gotIt_invokesOnDismiss() {
        var dismissed = 0
        setSheet(onDismiss = { dismissed++ })

        composeTestRule.onNodeWithText(string(R.string.whats_new_got_it)).performClick()

        assertEquals(1, dismissed)
    }

    @Test
    fun sectionHeadings_showOnlyForExpandedReleases() {
        setSheet()

        composeTestRule.onNodeWithText(string(R.string.whats_new_section_added)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.whats_new_section_fixed)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.whats_new_section_changed)).assertIsDisplayed()

        composeTestRule.onNode(header(NEWEST_NAME)).performClick()

        composeTestRule.onNodeWithText(string(R.string.whats_new_section_added)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.whats_new_section_fixed)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.whats_new_section_changed)).assertDoesNotExist()

        composeTestRule.onNode(header(MIDDLE_NAME)).performClick()

        composeTestRule.onNodeWithText(string(R.string.whats_new_section_fixed)).assertIsDisplayed()
        composeTestRule.onNodeWithText(string(R.string.whats_new_section_added)).assertDoesNotExist()
        composeTestRule.onNodeWithText(string(R.string.whats_new_section_changed)).assertDoesNotExist()
    }

    @Test
    fun noReleases_stillShowsTheTitleAndGotIt() {
        setSheet(releases = emptyList())

        composeTestRule.onNodeWithText(string(R.string.settings_whats_new_title)).assertIsDisplayed()
        // The list fills the sheet's height and the sheet opens half-expanded, so "Got it" is composed below
        // the fold even with no releases.
        composeTestRule.onNodeWithText(string(R.string.whats_new_got_it)).assertExists()
    }

    private companion object {
        const val OLDEST_CODE = 10
        const val OLDEST_NAME = "0.1.0"
        const val OLDEST_ENTRY = "Oldest release entry"
        const val MIDDLE_CODE = 20
        const val MIDDLE_NAME = "0.2.0"
        const val MIDDLE_ENTRY = "Middle release entry"
        const val NEWEST_CODE = 30
        const val NEWEST_NAME = "0.3.0"
        const val NEWEST_ADDED = "Newest release added entry"
        const val NEWEST_FIXED = "Newest release fixed entry"
        const val NEWEST_CHANGED = "Newest release changed entry"
    }
}
