package com.yapt.planttracker.ui.screens.away

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R

@Composable
internal fun PlanVisitShareBar(state: PlanVisitUiState.Plan, actions: PlanVisitActions) {
    val canShare = state.selected.listed.isNotEmpty() && !state.sharing
    Surface(modifier = Modifier.fillMaxWidth(), tonalElevation = 3.dp, shadowElevation = 8.dp) {
        Column(
            modifier = Modifier.navigationBarsPadding().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = actions.onSharePdf, enabled = canShare, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sitter_plan_share_pdf))
            }
            OutlinedButton(onClick = actions.onShareText, enabled = canShare, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.sitter_plan_share_text))
            }
        }
    }
}
