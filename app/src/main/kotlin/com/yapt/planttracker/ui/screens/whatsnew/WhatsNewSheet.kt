package com.yapt.planttracker.ui.screens.whatsnew

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R

private val RELEASE_HEADER_MIN_HEIGHT = 48.dp

private val ExpandedReleasesSaver = listSaver<Set<Int>, Int>(
    save = { it.toList() },
    restore = { it.toSet() }
)

/**
 * The releases a sheet opens expanded: every release newer than [lastSeenVersionCode], so a user who skipped
 * updates sees all of them, or just the newest when nothing was seen before (fresh install, Settings) or none
 * is newer.
 */
internal fun initialExpandedReleases(releases: List<ReleaseNotes>, lastSeenVersionCode: Int?): Set<Int> {
    val newest = releases.maxOfOrNull { it.versionCode } ?: return emptySet()
    val unseen = lastSeenVersionCode
        ?.takeIf { it > 0 }
        ?.let { seen -> releases.map { it.versionCode }.filter { it > seen }.toSet() }
        .orEmpty()
    return unseen.ifEmpty { setOf(newest) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewSheet(
    onDismiss: () -> Unit,
    releases: List<ReleaseNotes> = WhatsNewContent.all,
    lastSeenVersionCode: Int? = null
) {
    val sorted = remember(releases) { releases.sortedByDescending { it.versionCode } }
    var expanded by rememberSaveable(stateSaver = ExpandedReleasesSaver) {
        mutableStateOf(initialExpandedReleases(releases, lastSeenVersionCode))
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.settings_whats_new_title),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 8.dp)
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(sorted, key = { it.versionCode }) { notes ->
                val isExpanded = notes.versionCode in expanded
                ReleaseSection(
                    notes = notes,
                    expanded = isExpanded,
                    onToggle = {
                        expanded = if (isExpanded) expanded - notes.versionCode else expanded + notes.versionCode
                    }
                )
            }
        }
        Button(
            onClick = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 32.dp)
        ) {
            Text(stringResource(R.string.whats_new_got_it))
        }
    }
}

@Composable
private fun ReleaseSection(notes: ReleaseNotes, expanded: Boolean, onToggle: () -> Unit) {
    Column {
        ReleaseHeader(notes.versionName, expanded, onToggle)
        if (expanded) {
            Column(
                modifier = Modifier.padding(bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (notes.added.isNotEmpty()) NoteSection(stringResource(R.string.whats_new_section_added), notes.added)
                if (notes.fixed.isNotEmpty()) NoteSection(stringResource(R.string.whats_new_section_fixed), notes.fixed)
                if (notes.changed.isNotEmpty()) {
                    NoteSection(stringResource(R.string.whats_new_section_changed), notes.changed)
                }
            }
        }
    }
}

@Composable
private fun ReleaseHeader(versionName: String, expanded: Boolean, onToggle: () -> Unit) {
    val state = stringResource(
        if (expanded) R.string.whats_new_release_state_expanded else R.string.whats_new_release_state_collapsed
    )
    val action = stringResource(
        if (expanded) R.string.whats_new_release_collapse_action else R.string.whats_new_release_expand_action
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = RELEASE_HEADER_MIN_HEIGHT)
            .clickable(onClickLabel = action, role = Role.Button, onClick = onToggle)
            .semantics {
                heading()
                stateDescription = state
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = versionName,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun NoteSection(heading: String, entries: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(heading, style = MaterialTheme.typography.labelLarge)
        entries.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
    }
}
