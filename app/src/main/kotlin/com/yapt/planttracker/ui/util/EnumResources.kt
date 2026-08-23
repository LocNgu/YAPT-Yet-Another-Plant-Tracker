package com.yapt.planttracker.ui.util

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.schedule.SeasonBand
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.WateringConfidenceLevel
import com.yapt.planttracker.ui.theme.ThemeMode

@StringRes
fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.SYSTEM -> R.string.theme_mode_system
    ThemeMode.LIGHT -> R.string.theme_mode_light
    ThemeMode.DARK -> R.string.theme_mode_dark
}

@StringRes
fun SeasonalAmplitude.labelRes(): Int = when (this) {
    SeasonalAmplitude.OFF -> R.string.seasonal_amplitude_off
    SeasonalAmplitude.MILD -> R.string.seasonal_amplitude_mild
    SeasonalAmplitude.STANDARD -> R.string.seasonal_amplitude_standard
    SeasonalAmplitude.STRONG -> R.string.seasonal_amplitude_strong
}

@StringRes
fun CareType.labelRes(): Int = when (this) {
    CareType.WATER -> R.string.care_type_watered
    CareType.FERTILIZE -> R.string.care_type_fertilized
    CareType.PRUNE -> R.string.care_type_pruned
    CareType.MIST -> R.string.care_type_misted
    CareType.REPOT -> R.string.care_type_repotted
    CareType.NOTE -> R.string.care_type_note
    CareType.PHOTO -> R.string.care_type_photo
    CareType.CUSTOM -> R.string.care_type_custom
    CareType.CHECK -> R.string.care_type_check
}

fun CareType.icon(): ImageVector = when (this) {
    CareType.WATER -> Icons.Filled.WaterDrop
    CareType.FERTILIZE -> Icons.Filled.Spa
    CareType.PRUNE -> Icons.Filled.ContentCut
    CareType.MIST -> Icons.Filled.Shower
    CareType.REPOT -> Icons.Filled.LocalFlorist
    CareType.NOTE -> Icons.AutoMirrored.Filled.Notes
    CareType.PHOTO -> Icons.Filled.AutoAwesome
    CareType.CUSTOM -> Icons.Filled.Event
    CareType.CHECK -> Icons.Filled.FactCheck
}

@StringRes
fun WateringFeedback.labelRes(): Int = when (this) {
    WateringFeedback.TOO_SOON -> R.string.feedback_still_wet
    WateringFeedback.JUST_RIGHT -> R.string.feedback_just_right
    WateringFeedback.TOO_LATE -> R.string.feedback_too_dry
}

@StringRes
fun WateringFeedback.emojiRes(): Int = when (this) {
    WateringFeedback.TOO_SOON -> R.string.feedback_emoji_still_wet
    WateringFeedback.JUST_RIGHT -> R.string.feedback_emoji_just_right
    WateringFeedback.TOO_LATE -> R.string.feedback_emoji_too_dry
}

@StringRes
fun WateringAdjustmentTrigger.labelRes(): Int = when (this) {
    WateringAdjustmentTrigger.WATER_TOO_SOON -> R.string.adjustment_trigger_water_too_soon
    WateringAdjustmentTrigger.WATER_TOO_LATE -> R.string.adjustment_trigger_water_too_late
    WateringAdjustmentTrigger.WATER_JUST_RIGHT -> R.string.adjustment_trigger_water_just_right
    WateringAdjustmentTrigger.WATER_NEUTRAL -> R.string.adjustment_trigger_water_neutral
    WateringAdjustmentTrigger.CHECK_STILL_MOIST -> R.string.adjustment_trigger_check_still_moist
    WateringAdjustmentTrigger.DIALOG_DISMISSAL -> R.string.adjustment_trigger_dialog_dismissal
    WateringAdjustmentTrigger.DIALOG_EDIT -> R.string.adjustment_trigger_dialog_edit
    WateringAdjustmentTrigger.MANUAL_EDIT -> R.string.adjustment_trigger_manual_edit
}

@StringRes
fun WateringConfidenceLevel.labelRes(): Int = when (this) {
    WateringConfidenceLevel.STILL_LEARNING -> R.string.confidence_still_learning
    WateringConfidenceLevel.GETTING_THERE -> R.string.confidence_getting_there
    WateringConfidenceLevel.DIALED_IN -> R.string.confidence_dialed_in
}

@StringRes
fun SeasonBand.labelRes(): Int = when (this) {
    SeasonBand.SLOWER_GROWTH -> R.string.watering_explanation_season_slower
    SeasonBand.FASTER_GROWTH -> R.string.watering_explanation_season_faster
    SeasonBand.TRANSITIONAL -> R.string.watering_explanation_season_transitional
}
