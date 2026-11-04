package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.schedule.WateringConfidenceLevel
import com.yapt.planttracker.ui.util.labelRes

internal const val WATERING_CONFIDENCE_ROW_TEST_TAG = "watering_confidence_row"

private const val CONFIDENCE_BAR_COUNT = 5

/**
 * The Water tab's confidence row inside the watering-interval card (#510): a decorative five-bar
 * meter, the level label (the accessible content, "Confidence: Getting there"), a one-line caption,
 * and — while [onWhyThisDate] is non-null — a "Why?" affordance that opens the "Why this date?"
 * sheet, like the seasonal-adjustment line above it. Renders nothing for a never-adapted plant
 * ([score] `null`), never a false "Still learning".
 */
@Composable
internal fun WateringConfidenceRow(
    score: Int?,
    onWhyThisDate: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val level = WateringConfidenceLevel.fromScoreOrNull(score) ?: return
    val levelLabel = stringResource(level.labelRes())
    val levelDescription = stringResource(R.string.cd_watering_confidence_level, levelLabel)
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(
            modifier = Modifier.padding(top = 4.dp),
            color = MaterialTheme.colorScheme.outlineVariant
        )
        val rowModifier = Modifier
            .fillMaxWidth()
            .testTag(WATERING_CONFIDENCE_ROW_TEST_TAG)
            .let {
                if (onWhyThisDate != null) {
                    it.clickable(
                        onClickLabel = stringResource(R.string.why_this_date_button),
                        role = Role.Button,
                        onClick = onWhyThisDate
                    )
                } else {
                    it
                }
            }
            .padding(vertical = 8.dp)
        Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
            ConfidenceBars(filled = (score ?: 0).coerceIn(0, CONFIDENCE_BAR_COUNT))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = levelLabel,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.semantics { contentDescription = levelDescription }
                )
                Text(
                    text = stringResource(R.string.watering_confidence_row_caption),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onWhyThisDate != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.watering_confidence_row_why),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** Decorative only — the level label carries the meaning (#420, never colour or bar count alone). */
@Composable
private fun ConfidenceBars(filled: Int) {
    val filledColor = MaterialTheme.colorScheme.onSurfaceVariant
    val emptyColor = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier.clearAndSetSemantics {},
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(CONFIDENCE_BAR_COUNT) { index ->
            Box(
                modifier = Modifier
                    .size(width = 6.dp, height = 16.dp)
                    .background(
                        if (index < filled) filledColor else emptyColor,
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}
