package com.yapt.planttracker.ui.screens.whatsnew

import com.yapt.planttracker.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WhatsNewContentTest {

    @Test
    fun topEntryMatchesCurrentVersionName() {
        assertEquals(BuildConfig.VERSION_NAME, WhatsNewContent.all.first().versionName)
    }

    @Test
    fun everyEntryIsWithinTheLengthCap() {
        val tooLong = allEntries().filter { !isWithinCap(it.text) }

        assertTrue(
            "What's New entries must be at most $MAX_ENTRY_LENGTH characters (aim for ~100); shorten:\n" +
                tooLong.joinToString("\n") { "  ${it.where()} (${it.text.length}): ${it.text}" },
            tooLong.isEmpty()
        )
    }

    @Test
    fun everyEntryIsOneTrimmedSingleLineSentence() {
        val violations = allEntries().filter { shapeViolation(it.text) != null }

        assertTrue(
            "What's New entries must be one non-blank, trimmed, single-line sentence:\n" +
                violations.joinToString("\n") { "  ${it.where()} (${shapeViolation(it.text)}): ${it.text}" },
            violations.isEmpty()
        )
    }

    @Test
    fun noEntryUsesInternalVocabulary() {
        val violations = allEntries().filter { denylistHit(it.text) != null }

        assertTrue(
            "What's New entries must be plain language; rephrase these (details belong in CHANGELOG.md):\n" +
                violations.joinToString("\n") { "  ${it.where()} (matches ${denylistHit(it.text)}): ${it.text}" },
            violations.isEmpty()
        )
    }

    @Test
    fun everyReleasedVersionHasEntriesAndAUniqueVersionCode() {
        val empty = WhatsNewContent.all.filter { it.added.isEmpty() && it.fixed.isEmpty() && it.changed.isEmpty() }
        val duplicateCodes = WhatsNewContent.all.groupBy { it.versionCode }.filterValues { it.size > 1 }.keys

        assertTrue("Releases with no entries: ${empty.map { it.versionName }}", empty.isEmpty())
        assertTrue("Duplicate versionCodes: $duplicateCodes", duplicateCodes.isEmpty())
    }

    @Test
    fun lengthCapAllowsExactlyTheLimitAndRejectsOneMore() {
        assertTrue(isWithinCap("a".repeat(MAX_ENTRY_LENGTH)))
        assertTrue(!isWithinCap("a".repeat(MAX_ENTRY_LENGTH + 1)))
        assertTrue(!isWithinCap("a".repeat(MAX_ENTRY_LENGTH) + "b"))
    }

    @Test
    fun shapeCheckRejectsBlankUntrimmedMultilineAndMultiSentenceEntries() {
        assertEquals(null, shapeViolation("Away mode pauses reminders while you're gone"))
        assertEquals("blank", shapeViolation(""))
        assertEquals("blank", shapeViolation("   "))
        assertEquals("not trimmed", shapeViolation(" Leading space"))
        assertEquals("not trimmed", shapeViolation("Trailing space "))
        assertEquals("not a single line", shapeViolation("First line\nSecond line"))
        assertEquals("more than one sentence", shapeViolation("First sentence. Second sentence"))
    }

    @Test
    fun denylistCatchesInternalVocabularyCaseInsensitively() {
        assertEquals(null, denylistHit("Away mode pauses care reminders while you're gone"))
        assertEquals("#\\d+", denylistHit("Fixes the sheet (#958)"))
        assertEquals("ADR", denylistHit("See product adr-0072"))
        assertEquals("base interval", denylistHit("Shows the Base Interval next to it"))
        assertEquals("horizon", denylistHit("A wider Horizon for sitters"))
        assertEquals("override", denylistHit("Clears the due-date OVERRIDE"))
    }

    private data class Entry(val versionName: String, val section: String, val text: String) {
        fun where() = "$versionName $section"
    }

    private fun allEntries(): List<Entry> =
        (listOf(WhatsNewContent.unreleased) + WhatsNewContent.all).flatMap { release ->
            release.added.map { Entry(release.versionName, "Added", it) } +
                release.fixed.map { Entry(release.versionName, "Fixed", it) } +
                release.changed.map { Entry(release.versionName, "Changed", it) }
        }

    private fun isWithinCap(text: String): Boolean = text.length <= MAX_ENTRY_LENGTH

    private fun shapeViolation(text: String): String? = when {
        text.isBlank() -> "blank"
        text != text.trim() -> "not trimmed"
        text.contains('\n') || text.contains('\r') -> "not a single line"
        text.contains(". ") -> "more than one sentence"
        else -> null
    }

    private fun denylistHit(text: String): String? =
        DENYLIST.firstOrNull { Regex(it, RegexOption.IGNORE_CASE).containsMatchIn(text) }

    private companion object {
        const val MAX_ENTRY_LENGTH = 120
        val DENYLIST = listOf("#\\d+", "ADR", "base interval", "horizon", "override")
    }
}
