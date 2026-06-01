package com.yapt.planttracker.domain.model

enum class WateringFeedback(val displayName: String, val emoji: String) {
    TOO_SOON("Still wet", "💦"),
    JUST_RIGHT("Just right", "✅"),
    TOO_LATE("Too dry", "🏜️")
}
