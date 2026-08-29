package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.schedule.WateringExplanation
import com.yapt.planttracker.ui.util.labelRes
import com.yapt.planttracker.util.DateUtils

/**
 * "Why this date?" (#572) — every row is a stored value or a single multiplication from
 * [WateringExplanation], never re-derived here. Degrades to just the next-watering-date and plain
 * interval rows when [WateringExplanation.adaptiveWateringEnabled] is false.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WateringExplanationSheet(explanation: WateringExplanation, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("watering_explanation_sheet")
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)
        ) {
            Text(stringResource(R.string.watering_explanation_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(16.dp))

            WateringExplanationIntervalRows(explanation)
            WateringExplanationAdaptiveSection(explanation)
        }
    }
}

@Composable
private fun WateringExplanationIntervalRows(explanation: WateringExplanation) {
    ExplanationRow(
        label = stringResource(R.string.watering_explanation_next_watering),
        value = explanation.nextWateringDueAt?.let { DateUtils.formatDate(it) } ?: "—"
    )

    if (explanation.adaptiveWateringEnabled && explanation.baseIntervalDays != null) {
        ExplanationRow(
            label = stringResource(R.string.watering_explanation_base_interval),
            value = pluralStringResource(
                R.plurals.insight_interval_days,
                explanation.baseIntervalDays,
                explanation.baseIntervalDays
            ),
            caption = pluralStringResource(
                R.plurals.watering_explanation_learned_from,
                explanation.waterLogCount,
                explanation.waterLogCount
            )
        )
    }

    explanation.season?.let { season ->
        ExplanationRow(
            label = stringResource(season.band.labelRes()),
            value = stringResource(R.string.watering_explanation_multiplier, season.multiplier)
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

    ExplanationRow(
        label = pluralStringResource(
            R.plurals.watering_explanation_effective_interval,
            explanation.effectiveIntervalDays,
            explanation.effectiveIntervalDays
        ),
        value = ""
    )

    ExplanationRow(
        label = stringResource(R.string.watering_explanation_last_watered),
        value = explanation.lastWateredAt?.let { DateUtils.formatRelative(it) }
            ?: stringResource(R.string.water_label_never_watered)
    )
}

@Composable
private fun WateringExplanationAdaptiveSection(explanation: WateringExplanation) {
    explanation.confidenceLevel?.let { level ->
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.watering_explanation_confidence),
            style = MaterialTheme.typography.bodyMedium
        )
        Row(
            modifier = Modifier.testTag("watering_confidence_row"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ConfidenceDots(filled = explanation.confidenceScore)
            Text(text = stringResource(level.labelRes()), style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (explanation.adaptiveWateringEnabled) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.watering_explanation_recent_adjustments),
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.height(4.dp))
        if (explanation.recentAdjustments.isEmpty()) {
            Text(
                text = stringResource(R.string.watering_explanation_no_adjustments),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            explanation.recentAdjustments.forEach { adjustment -> AdjustmentRow(adjustment) }
        }
    }
}

@Composable
private fun ExplanationRow(label: String, value: String, caption: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            caption?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (value.isNotEmpty()) {
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun AdjustmentRow(adjustment: WateringAdjustment) {
    val change = if (adjustment.beforeIntervalDays == adjustment.afterIntervalDays) {
        stringResource(R.string.watering_explanation_adjustment_unchanged)
    } else {
        stringResource(
            R.string.watering_explanation_adjustment_change,
            adjustment.beforeIntervalDays,
            adjustment.afterIntervalDays
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column {
            Text(text = DateUtils.formatRelative(adjustment.triggeredAt), style = MaterialTheme.typography.bodySmall)
            Text(text = stringResource(adjustment.trigger.labelRes()), style = MaterialTheme.typography.bodyMedium)
        }
        Text(text = change, style = MaterialTheme.typography.bodyMedium)
    }
}

private const val CONFIDENCE_DOT_COUNT = 5

/** Decorative dots — [filled] carries no accessible content of its own; the caller's label Text does (#420). */
@Composable
private fun ConfidenceDots(filled: Int) {
    val filledColor = MaterialTheme.colorScheme.primary
    val emptyColor = MaterialTheme.colorScheme.outlineVariant
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(CONFIDENCE_DOT_COUNT) { index ->
            val color = if (index < filled) filledColor else emptyColor
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(color, CircleShape)
            )
        }
    }
}
