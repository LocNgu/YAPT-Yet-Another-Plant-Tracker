package com.yapt.planttracker.domain.model

/** Plant facts shown on Plant Detail (#1003). They never feed `CareSchedule` or adaptive watering. */
enum class LightLevel {
    LOW,
    MEDIUM,
    BRIGHT_INDIRECT,
    DIRECT;

    companion object {
        fun fromStorage(value: String?): LightLevel? =
            value?.let { runCatching { valueOf(it) }.getOrDefault(null) }
    }
}

enum class PotMaterial {
    PLASTIC,
    TERRACOTTA,
    CERAMIC,
    OTHER;

    companion object {
        fun fromStorage(value: String?): PotMaterial? =
            value?.let { runCatching { valueOf(it) }.getOrDefault(null) }
    }
}

object PotDiameter {
    const val MIN_CM = 1
    const val MAX_CM = 200

    /** The diameter a text field holds: `null` for blank or out of range. */
    fun parseOrNull(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in MIN_CM..MAX_CM }

    fun isValidInput(text: String): Boolean = text.isBlank() || parseOrNull(text) != null
}
