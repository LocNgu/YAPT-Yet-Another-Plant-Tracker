package com.yapt.planttracker.ui.screens.plantlist

import com.yapt.planttracker.domain.model.Plant
import java.text.Normalizer

private val COMBINING_MARKS_REGEX = Regex("\\p{Mn}+")

/**
 * True when the trimmed [query] is a case-insensitive, accent-folded substring of [Plant.name] or
 * [Plant.species] (#512) — either field independently, so a null [Plant.species] just can't match.
 * A blank/whitespace-only [query] matches every plant (no filter applied).
 */
fun matchesSearchQuery(plant: Plant, query: String): Boolean {
    val trimmedQuery = query.trim()
    if (trimmedQuery.isEmpty()) return true
    val foldedQuery = trimmedQuery.foldForSearch()
    return plant.name.foldForSearch().contains(foldedQuery) ||
        plant.species?.foldForSearch()?.contains(foldedQuery) == true
}

/**
 * NFD-decomposes accented characters into base + combining mark, then strips the marks, so e.g.
 * "grun" matches "Grünlilie". A character with no canonical decomposition (ß, ø, æ, đ) is left
 * exactly as-is — it is never expanded (ß does not become "ss") or otherwise special-cased.
 */
private fun String.foldForSearch(): String =
    Normalizer.normalize(this, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS_REGEX, "")
        .lowercase()
