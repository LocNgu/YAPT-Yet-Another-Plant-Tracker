package com.yapt.planttracker.ui.util

import androidx.annotation.PluralsRes
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
import com.yapt.planttracker.domain.model.WateringReason
import com.yapt.planttracker.domain.repotting.RepottingOverviewThreshold
import com.yapt.planttracker.domain.schedule.FertilizingSeason
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
    // CareType.MIST is retained for historical data (#875, product ADR-0061) — no longer written,
    // but existing rows still need a label to render.
    CareType.MIST -> R.string.care_type_misted
    CareType.REPOT -> R.string.care_type_repotted
    CareType.NOTE -> R.string.care_type_note
    CareType.PHOTO -> R.string.care_type_photo
    CareType.CUSTOM -> R.string.care_type_custom
    // CareType.CHECK is retained for historical data (#738, product ADR-0039) — no longer written,
    // but existing rows still need a label to render.
    CareType.CHECK -> R.string.care_type_check
}

fun CareType.icon(): ImageVector = when (this) {
    CareType.WATER -> Icons.Filled.WaterDrop
    CareType.FERTILIZE -> Icons.Filled.Spa
    CareType.PRUNE -> Icons.Filled.ContentCut
    // CareType.MIST is retained for historical data (#875, product ADR-0061) — no longer written,
    // but existing rows still need an icon to render.
    CareType.MIST -> Icons.Filled.Shower
    CareType.REPOT -> Icons.Filled.LocalFlorist
    CareType.NOTE -> Icons.AutoMirrored.Filled.Notes
    CareType.PHOTO -> Icons.Filled.AutoAwesome
    CareType.CUSTOM -> Icons.Filled.Event
    // CareType.CHECK is retained for historical data (#738, product ADR-0039) — no longer written,
    // but existing rows still need an icon to render.
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

/**
 * Reason-prompt chip label for an off-schedule watering (#586, product ADR-0030; late-direction
 * option set amended by #649, product ADR-0033). [PLANT_NEEDED_IT] is early-only and
 * [SOIL_STILL_MOIST] is late-only (see [WateringReasonBottomSheet]'s direction-specific option
 * list), so neither actually branches on [gapRanLong] here — only [JUST_MY_TIMING] is offered in
 * both directions and needs the two wordings ("just my timing" implies a deliberate choice that
 * forgetting never involves).
 */
@StringRes
fun WateringReason.labelRes(gapRanLong: Boolean = false): Int = when (this) {
    WateringReason.PLANT_NEEDED_IT -> R.string.water_reason_plant_needed_it
    WateringReason.SOIL_STILL_MOIST -> R.string.water_reason_soil_still_moist_late
    WateringReason.JUST_MY_TIMING ->
        if (gapRanLong) R.string.water_reason_just_my_timing_late else R.string.water_reason_just_my_timing
}

@StringRes
@Suppress("CyclomaticComplexMethod")
fun WateringAdjustmentTrigger.labelRes(): Int = when (this) {
    WateringAdjustmentTrigger.WATER_TOO_SOON -> R.string.adjustment_trigger_water_too_soon
    WateringAdjustmentTrigger.WATER_TOO_LATE -> R.string.adjustment_trigger_water_too_late
    WateringAdjustmentTrigger.WATER_JUST_RIGHT -> R.string.adjustment_trigger_water_just_right
    WateringAdjustmentTrigger.WATER_NEUTRAL -> R.string.adjustment_trigger_water_neutral
    WateringAdjustmentTrigger.WATER_NOT_ATTRIBUTED -> R.string.adjustment_trigger_water_not_attributed
    // CHECK_STILL_MOIST is retained for historical data (#738, product ADR-0039) — no longer
    // written, but existing "Why this date?" rows still need a label to render.
    WateringAdjustmentTrigger.CHECK_STILL_MOIST -> R.string.adjustment_trigger_check_still_moist
    WateringAdjustmentTrigger.DIALOG_DISMISSAL -> R.string.adjustment_trigger_dialog_dismissal
    WateringAdjustmentTrigger.DIALOG_EDIT -> R.string.adjustment_trigger_dialog_edit
    WateringAdjustmentTrigger.MANUAL_EDIT -> R.string.adjustment_trigger_manual_edit
    WateringAdjustmentTrigger.SILENT_APPLY_UNDONE -> R.string.adjustment_trigger_silent_apply_undone
    WateringAdjustmentTrigger.REPOT_RESET -> R.string.adjustment_trigger_repot_reset
    WateringAdjustmentTrigger.ROOM_CHANGE_RESET -> R.string.adjustment_trigger_room_change_reset
    WateringAdjustmentTrigger.FROZEN_POST_REPOT -> R.string.adjustment_trigger_frozen_post_repot
    WateringAdjustmentTrigger.HISTORY_BOOTSTRAP -> R.string.adjustment_trigger_history_bootstrap
    WateringAdjustmentTrigger.SEASONAL_GRADUATION_FIXUP -> R.string.adjustment_trigger_seasonal_graduation_fixup
    WateringAdjustmentTrigger.DORMANCY_EXCLUDED -> R.string.adjustment_trigger_dormancy_excluded
    WateringAdjustmentTrigger.DORMANCY_EXIT -> R.string.adjustment_trigger_dormancy_exit
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

@StringRes
fun FertilizingSeason.labelRes(): Int = when (this) {
    FertilizingSeason.SPRING -> R.string.season_spring
    FertilizingSeason.SUMMER -> R.string.season_summer
    FertilizingSeason.AUTUMN -> R.string.season_autumn
    FertilizingSeason.WINTER -> R.string.season_winter
}

/**
 * Daily-notification line for a planned repot whose season is under way (#809, product ADR-0057). One
 * whole sentence per season rather than a season name spliced into a template, so a translation can
 * inflect or reorder it freely.
 */
@StringRes
fun FertilizingSeason.repotPlannedNotificationRes(): Int = when (this) {
    FertilizingSeason.SPRING -> R.string.notification_repotting_planned_spring
    FertilizingSeason.SUMMER -> R.string.notification_repotting_planned_summer
    FertilizingSeason.AUTUMN -> R.string.notification_repotting_planned_autumn
    FertilizingSeason.WINTER -> R.string.notification_repotting_planned_winter
}

/** Repot tab line naming a plan's season, taking the season's year (#809, product ADR-0057). One sentence per season for the same reason as [repotPlannedNotificationRes]. */
@StringRes
fun FertilizingSeason.repotPlanLabelRes(): Int = when (this) {
    FertilizingSeason.SPRING -> R.string.repot_plan_planned_spring
    FertilizingSeason.SUMMER -> R.string.repot_plan_planned_summer
    FertilizingSeason.AUTUMN -> R.string.repot_plan_planned_autumn
    FertilizingSeason.WINTER -> R.string.repot_plan_planned_winter
}

/** Care tile line for a planned repot (#809, product ADR-0057), in season or after it has ended. */
@StringRes
fun FertilizingSeason.repotPlannedTileRes(): Int = when (this) {
    FertilizingSeason.SPRING -> R.string.care_tile_repot_planned_spring
    FertilizingSeason.SUMMER -> R.string.care_tile_repot_planned_summer
    FertilizingSeason.AUTUMN -> R.string.care_tile_repot_planned_autumn
    FertilizingSeason.WINTER -> R.string.care_tile_repot_planned_winter
}

/** Chip label on the Repotting overview (#525, product ADR-0059). */
@StringRes
fun RepottingOverviewThreshold.chipLabelRes(): Int = when (this) {
    RepottingOverviewThreshold.NEVER -> R.string.repotting_overview_chip_never
    RepottingOverviewThreshold.ONE_YEAR -> R.string.repotting_overview_chip_one_year
    RepottingOverviewThreshold.TWO_YEARS -> R.string.repotting_overview_chip_two_years
    RepottingOverviewThreshold.THREE_YEARS -> R.string.repotting_overview_chip_three_years
}

/** Header over the threshold list, naming the selected chip. */
@StringRes
fun RepottingOverviewThreshold.headerRes(): Int = when (this) {
    RepottingOverviewThreshold.NEVER -> R.string.repotting_overview_header_never
    RepottingOverviewThreshold.ONE_YEAR -> R.string.repotting_overview_header_one_year
    RepottingOverviewThreshold.TWO_YEARS -> R.string.repotting_overview_header_two_years
    RepottingOverviewThreshold.THREE_YEARS -> R.string.repotting_overview_header_three_years
}

/** Settings row subtitle ("4 plants not repotted in 2+ years"), pluralised on the plant count. */
@PluralsRes
fun RepottingOverviewThreshold.settingsSubtitleRes(): Int = when (this) {
    RepottingOverviewThreshold.NEVER -> R.plurals.repotting_overview_settings_subtitle_never
    RepottingOverviewThreshold.ONE_YEAR -> R.plurals.repotting_overview_settings_subtitle_one_year
    RepottingOverviewThreshold.TWO_YEARS -> R.plurals.repotting_overview_settings_subtitle_two_years
    RepottingOverviewThreshold.THREE_YEARS -> R.plurals.repotting_overview_settings_subtitle_three_years
}
