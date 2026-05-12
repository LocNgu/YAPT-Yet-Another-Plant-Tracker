package com.yapt.planttracker.domain.model

enum class WateringFeedback(val displayName: String, val emoji: String) {
    TOO_SOON("Too soon", "💦"),
    JUST_RIGHT("Just right", "✅"),
    TOO_LATE("Too late", "🏜️")
}
