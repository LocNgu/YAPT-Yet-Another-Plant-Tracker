package com.yapt.planttracker.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards `values-de/strings.xml` against the runtime failures lint can't see (#1005): a dropped or
 * mistyped format placeholder crashes `getString(id, args)` or silently loses a value. Missing and
 * extra keys are lint's job (`MissingTranslation`/`ExtraTranslation` are errors in `lintDebug`), but
 * this test also checks them so a failure points straight at the key.
 */
class GermanTranslationParityTest {

    private val english = parse(File("src/main/res/values/strings.xml"))
    private val german = parse(File("src/main/res/values-de/strings.xml"))

    @Test
    fun `every translatable English resource has a German entry and vice versa`() {
        val missing = english.translatableKeys() - german.allKeys()
        val extra = german.allKeys() - english.translatableKeys()
        assertTrue("Missing German translations: $missing", missing.isEmpty())
        assertTrue("German keys absent from values/: $extra", extra.isEmpty())
    }

    @Test
    fun `strings keep exactly the English placeholders`() {
        val mismatches = german.strings.mapNotNull { (name, de) ->
            val en = english.strings[name] ?: return@mapNotNull null
            if (placeholders(en) == placeholders(de)) null else "$name: '$en' vs '$de'"
        }
        assertEquals("Placeholder mismatches", emptyList<String>(), mismatches)
    }

    @Test
    fun `plurals use one and other with the English placeholders`() {
        val problems = mutableListOf<String>()
        german.plurals.forEach { (name, deItems) ->
            val enOther = english.plurals[name]?.get("other") ?: return@forEach
            if (deItems.keys != setOf("one", "other")) problems += "$name: quantities ${deItems.keys}"
            val deOther = deItems["other"]
            if (deOther != null && placeholders(deOther) != placeholders(enOther)) {
                problems += "$name/other: '$enOther' vs '$deOther'"
            }
            // German `one` only ever renders count == 1, so it may spell the number out, but it must
            // never introduce a placeholder English `other` lacks.
            val deOne = deItems["one"]
            if (deOne != null && !placeholders(enOther).containsAll(placeholders(deOne))) {
                problems += "$name/one: '$deOne' has placeholders not in '$enOther'"
            }
        }
        assertEquals("Plural problems", emptyList<String>(), problems)
    }

    @Test
    fun `string arrays keep the English item count`() {
        val mismatches = german.arrays.mapNotNull { (name, items) ->
            val en = english.arrays[name] ?: return@mapNotNull null
            if (en.size == items.size) null else "$name: ${en.size} vs ${items.size}"
        }
        assertEquals("Array size mismatches", emptyList<String>(), mismatches)
    }

    private class Resources(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
        val arrays: Map<String, List<String>>,
        val untranslatable: Set<String>,
    ) {
        fun allKeys(): Set<String> = strings.keys + plurals.keys + arrays.keys
        fun translatableKeys(): Set<String> = allKeys() - untranslatable
    }

    private fun parse(file: File): Resources {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val strings = mutableMapOf<String, String>()
        val plurals = mutableMapOf<String, Map<String, String>>()
        val arrays = mutableMapOf<String, List<String>>()
        val untranslatable = mutableSetOf<String>()
        root.childElements().forEach { el ->
            val name = el.getAttribute("name")
            if (el.getAttribute("translatable") == "false") untranslatable += name
            when (el.tagName) {
                "string" -> strings[name] = el.textContent
                "plurals" -> plurals[name] = el.childElements().associate {
                    it.getAttribute("quantity") to it.textContent
                }
                "string-array" -> arrays[name] = el.childElements().map { it.textContent }
            }
        }
        return Resources(strings, plurals, arrays, untranslatable)
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }

    private fun placeholders(text: String): List<String> =
        PLACEHOLDER.findAll(text.replace("%%", "")).map { it.value }.sorted().toList()

    private companion object {
        val PLACEHOLDER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[a-zA-Z]""")
    }
}
