package com.yapt.planttracker.ui.util

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Shower
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.ui.graphics.vector.ImageVector
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.WateringFeedback

@StringRes
fun CareType.labelRes(): Int = when (this) {
    CareType.WATER -> R.string.care_type_watered
    CareType.FERTILIZE -> R.string.care_type_fertilized
    CareType.PRUNE -> R.string.care_type_pruned
    CareType.MIST -> R.string.care_type_misted
    CareType.REPOT -> R.string.care_type_repotted
    CareType.NOTE -> R.string.care_type_note
    CareType.PHOTO -> R.string.care_type_photo
}

fun CareType.icon(): ImageVector = when (this) {
    CareType.WATER -> Icons.Filled.WaterDrop
    CareType.FERTILIZE -> Icons.Filled.Spa
    CareType.PRUNE -> Icons.Filled.ContentCut
    CareType.MIST -> Icons.Filled.Shower
    CareType.REPOT -> Icons.Filled.LocalFlorist
    CareType.NOTE -> Icons.AutoMirrored.Filled.Notes
    CareType.PHOTO -> Icons.Filled.AutoAwesome
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
