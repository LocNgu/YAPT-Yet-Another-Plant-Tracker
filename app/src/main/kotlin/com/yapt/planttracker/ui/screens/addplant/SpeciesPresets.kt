package com.yapt.planttracker.ui.screens.addplant

import androidx.annotation.StringRes
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import java.text.Normalizer
import java.util.Locale

/** Dormancy months are written for the northern hemisphere; the ViewModel shifts them by six months in the south. */
data class PresetDormancy(val startMonth: Int, val endMonth: Int, val dormantWateringIntervalDays: Int?)

/**
 * A rough starting point for a common houseplant (product ADR-0088): copied into the Add wizard form once
 * the user picks it, never stored on the plant. [wateringBaseIntervalDays] is season-neutral.
 */
data class SpeciesPreset(
    val id: String,
    @StringRes val nameRes: Int,
    val botanicalName: String,
    val wateringBaseIntervalDays: Int,
    val fertilizingIntervalDays: Int?,
    val fertilizingSeasons: Set<FertilizingSeason> = GROWING_SEASONS,
    val dormancy: PresetDormancy? = null
)

private val GROWING_SEASONS = setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER)
private val WINTER_REST = PresetDormancy(startMonth = 11, endMonth = 2, dormantWateringIntervalDays = 28)

object SpeciesPresets {
    val all: List<SpeciesPreset> = listOf(
        SpeciesPreset("monstera", R.string.preset_monstera, "Monstera deliciosa", 7, 30),
        SpeciesPreset("pothos", R.string.preset_pothos, "Epipremnum aureum", 8, 30),
        SpeciesPreset("snake_plant", R.string.preset_snake_plant, "Dracaena trifasciata", 18, 60),
        SpeciesPreset("zz_plant", R.string.preset_zz_plant, "Zamioculcas zamiifolia", 18, 60),
        SpeciesPreset("peace_lily", R.string.preset_peace_lily, "Spathiphyllum wallisii", 7, 30),
        SpeciesPreset("spider_plant", R.string.preset_spider_plant, "Chlorophytum comosum", 8, 30),
        SpeciesPreset("fiddle_leaf_fig", R.string.preset_fiddle_leaf_fig, "Ficus lyrata", 8, 30),
        SpeciesPreset("rubber_plant", R.string.preset_rubber_plant, "Ficus elastica", 9, 30),
        SpeciesPreset("aloe_vera", R.string.preset_aloe_vera, "Aloe vera", 18, 60, dormancy = WINTER_REST),
        SpeciesPreset("jade_plant", R.string.preset_jade_plant, "Crassula ovata", 16, 60),
        SpeciesPreset("calathea", R.string.preset_calathea, "Goeppertia", 6, 30),
        SpeciesPreset("boston_fern", R.string.preset_boston_fern, "Nephrolepis exaltata", 4, 30),
        SpeciesPreset(
            "heartleaf_philodendron",
            R.string.preset_heartleaf_philodendron,
            "Philodendron hederaceum",
            8,
            30
        ),
        SpeciesPreset("chinese_evergreen", R.string.preset_chinese_evergreen, "Aglaonema commutatum", 10, 45),
        SpeciesPreset("dragon_tree", R.string.preset_dragon_tree, "Dracaena marginata", 12, 45),
        SpeciesPreset("moth_orchid", R.string.preset_moth_orchid, "Phalaenopsis", 8, 30),
        SpeciesPreset("christmas_cactus", R.string.preset_christmas_cactus, "Schlumbergera", 10, 30),
        SpeciesPreset(
            "string_of_pearls",
            R.string.preset_string_of_pearls,
            "Curio rowleyanus",
            14,
            60,
            dormancy = WINTER_REST
        ),
        SpeciesPreset("wax_plant", R.string.preset_wax_plant, "Hoya carnosa", 12, 45),
        SpeciesPreset("english_ivy", R.string.preset_english_ivy, "Hedera helix", 7, 30),
        SpeciesPreset("areca_palm", R.string.preset_areca_palm, "Dypsis lutescens", 7, 30)
    )

    fun byId(id: String?): SpeciesPreset? = all.firstOrNull { it.id == id }
}

private const val MIN_QUERY_LENGTH = 2
private const val MAX_SUGGESTIONS = 5
private val COMBINING_MARKS = Regex("\\p{Mn}+")
private val WHITESPACE = Regex("\\s+")

/** Lower-cased, accent-stripped, whitespace-collapsed text used to compare what the user typed with a name. */
internal fun normalizeForPresetMatch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(COMBINING_MARKS, "")
        .lowercase(Locale.ROOT)
        .trim()
        .replace(WHITESPACE, " ")

/**
 * Presets whose common or botanical name contains [query] (case- and accent-insensitive), names starting with
 * the query first. Nothing for a query under two characters, or once the query is exactly a botanical name
 * (the user already picked it, or typed it out).
 */
fun matchSpeciesPresets(
    query: String,
    presets: List<SpeciesPreset> = SpeciesPresets.all,
    commonName: (SpeciesPreset) -> String
): List<SpeciesPreset> {
    val q = normalizeForPresetMatch(query)
    val alreadyBotanical = presets.any { normalizeForPresetMatch(it.botanicalName) == q }
    if (q.length < MIN_QUERY_LENGTH || alreadyBotanical) return emptyList()
    val matches = presets.mapNotNull { preset ->
        val names = listOf(normalizeForPresetMatch(commonName(preset)), normalizeForPresetMatch(preset.botanicalName))
        if (names.none { q in it }) null else preset to names.any { it.startsWith(q) }
    }
    return (matches.filter { it.second } + matches.filterNot { it.second }).map { it.first }.take(MAX_SUGGESTIONS)
}
