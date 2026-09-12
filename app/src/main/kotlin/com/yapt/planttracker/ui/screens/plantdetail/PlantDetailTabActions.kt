package com.yapt.planttracker.ui.screens.plantdetail

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

internal const val REPOT_TAB_ACTION_BUTTON_TEST_TAG = "repot_tab_action_button"
internal const val PHOTO_TAB_ACTION_BUTTON_TEST_TAG = "photo_tab_action_button"

/**
 * Primary, always-visible action for a Plant Detail tab (#658). Repot and Photo use the same filled
 * button, leading-icon, and 16dp horizontal-padding treatment as the Water tab's primary action.
 */
@Composable
internal fun PlantDetailTabActionRow(
    @StringRes labelRes: Int,
    icon: ImageVector,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        Button(
            onClick = onClick,
            modifier = Modifier.weight(1f).testTag(testTag)
        ) {
            Icon(icon, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(labelRes))
        }
    }
}
