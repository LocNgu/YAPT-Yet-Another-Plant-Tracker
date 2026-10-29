package com.yapt.planttracker.ui.screens.whatsnew

import org.junit.Assert.assertEquals
import org.junit.Test

/** Which releases the What's New sheet opens expanded (product ADR-0072): every unseen one, else the newest. */
class WhatsNewInitialExpandedTest {

    private val releases = listOf(30, 10, 40, 20).map { ReleaseNotes(versionCode = it, versionName = "v$it") }

    @Test
    fun nothingSeenBefore_expandsOnlyTheNewest() {
        assertEquals(setOf(40), initialExpandedReleases(releases, lastSeenVersionCode = null))
    }

    @Test
    fun freshInstall_expandsOnlyTheNewest_notTheWholeHistory() {
        assertEquals(setOf(40), initialExpandedReleases(releases, lastSeenVersionCode = 0))
    }

    @Test
    fun skippedUpdates_expandEveryReleaseNewerThanTheLastSeen() {
        assertEquals(setOf(30, 40), initialExpandedReleases(releases, lastSeenVersionCode = 20))
    }

    @Test
    fun lastSeenBetweenReleaseCodes_expandsOnlyTheNewerOnes() {
        assertEquals(setOf(30, 40), initialExpandedReleases(releases, lastSeenVersionCode = 25))
    }

    @Test
    fun noReleaseNewerThanTheLastSeen_fallsBackToTheNewest() {
        assertEquals(setOf(40), initialExpandedReleases(releases, lastSeenVersionCode = 40))
    }

    @Test
    fun noReleases_expandsNothing() {
        assertEquals(emptySet<Int>(), initialExpandedReleases(emptyList(), lastSeenVersionCode = 20))
    }
}
