package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.insights.CareInsights
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.ui.components.CareLogItem
import com.yapt.planttracker.ui.components.EmptyStateView
import com.yapt.planttracker.ui.util.relativeDateText
import kotlinx.coroutines.flow.StateFlow

internal const val PROPAGATE_TAB_ACTION_BUTTON_TEST_TAG = "propagate_tab_action_button"

/** Locates the Propagate tab's date-picker sheet ([CareDatePickerBottomSheet], #960) in Compose UI tests. */
internal const val PROPAGATE_DATE_PICKER_TEST_TAG = "propagate_date_picker_dialog"
internal const val FAMILY_LINK_PARENT_BUTTON_TEST_TAG = "family_link_parent_button"
internal const val FAMILY_LINK_EXISTING_BUTTON_TEST_TAG = "family_link_existing_button"
internal const val FAMILY_ADD_NEW_BUTTON_TEST_TAG = "family_add_new_button"
internal const val FAMILY_PICKER_TEST_TAG = "family_picker_sheet"
internal const val FAMILY_PICKER_SEARCH_TEST_TAG = "family_picker_search"
internal const val PROPAGATE_OFFER_DIALOG_TEST_TAG = "propagate_offer_dialog"

/** How Plant Detail reaches a relative's page or Add Plant for a new child (#960); bundled for `LongParameterList`. */
data class FamilyNavigation(
    val onOpenPlant: (plantId: Long) -> Unit = {},
    val onAddChild: (parentId: Long) -> Unit = {}
)

/** The Family card's data (#960, product ADR-0073): this plant's parent, if any, and its direct children. */
internal data class FamilyCardState(val parent: Plant?, val children: List<Plant>)

/** The Family card's callbacks, bundled for Detekt's `LongParameterList`. */
internal data class FamilyCardActions(
    val onOpenPlant: (plantId: Long) -> Unit,
    val onLinkParent: () -> Unit,
    val onLinkExisting: () -> Unit,
    val onAddNew: () -> Unit,
    val onUnlinkParent: () -> Unit,
    val onUnlinkChild: (childId: Long) -> Unit
)

/** The Propagate tab's callbacks, bundled for Detekt's `LongParameterList`. */
internal data class PropagateTabActions(
    val family: FamilyCardActions,
    val onPropagateClick: () -> Unit,
    val onEdit: (CareLog) -> Unit,
    val onDelete: (CareLog) -> Unit
)

/**
 * The Propagate tab (#960, product ADR-0073), mirroring the Prune tab: a filled "Propagate" button that
 * opens the shared date sheet, then the Family card, a summary card and the plant's own PROPAGATE logs
 * (keys `"propagate-${id}"`, edit and delete). The family flows are collected inside the Family item, so
 * they only run while this tab is on screen. There is no notes field here; notes and a photo are added
 * by editing the log in Add Care Log.
 */
internal fun LazyListScope.propagateTabItems(
    careLogs: List<CareLog>,
    parentPlant: StateFlow<Plant?>,
    childPlants: StateFlow<List<Plant>>,
    actions: PropagateTabActions
) {
    item {
        PlantDetailTabActionRow(
            labelRes = R.string.propagate_action,
            icon = Icons.AutoMirrored.Filled.CallSplit,
            testTag = PROPAGATE_TAB_ACTION_BUTTON_TEST_TAG,
            onClick = actions.onPropagateClick
        )
        Spacer(Modifier.height(16.dp))
    }
    item {
        val parent by parentPlant.collectAsStateWithLifecycle()
        val children by childPlants.collectAsStateWithLifecycle()
        FamilyCard(FamilyCardState(parent, children), actions.family)
        Spacer(Modifier.height(16.dp))
    }
    val propagateLogs = careLogs.filter { it.careType == CareType.PROPAGATE }
    item {
        val summary = CareInsights.summarize(careLogs, CareType.PROPAGATE)
        val lastAt = summary.lastAt
        if (summary.count > 0 && lastAt != null) {
            TabInsightsCard(
                listOf(
                    stringResource(R.string.insight_propagations) to summary.count.toString(),
                    stringResource(R.string.insight_last_propagated) to relativeDateText(lastAt)
                )
            )
            Spacer(Modifier.height(16.dp))
        }
    }
    if (propagateLogs.isEmpty()) {
        item {
            Box(modifier = Modifier.height(160.dp)) {
                EmptyStateView(
                    message = stringResource(R.string.plant_detail_tab_propagate_empty),
                    icon = Icons.AutoMirrored.Filled.CallSplit
                )
            }
        }
    } else {
        items(propagateLogs, key = { "propagate-${it.id}" }) { log ->
            CareLogItem(log = log, onEdit = { actions.onEdit(log) }, onDelete = { actions.onDelete(log) })
        }
    }
}

@Composable
internal fun FamilyCard(state: FamilyCardState, actions: FamilyCardActions) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(text = stringResource(R.string.family_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            FamilySectionLabel(R.string.family_propagated_from)
            val parent = state.parent
            if (parent != null) {
                FamilyRelativeRow(
                    plant = parent,
                    onOpen = { actions.onOpenPlant(parent.id) },
                    onUnlink = actions.onUnlinkParent
                )
            } else {
                OutlinedButton(
                    onClick = actions.onLinkParent,
                    modifier = Modifier.testTag(FAMILY_LINK_PARENT_BUTTON_TEST_TAG)
                ) { Text(stringResource(R.string.family_link_parent)) }
            }
            Spacer(Modifier.height(12.dp))
            FamilySectionLabel(R.string.family_children)
            if (state.children.isEmpty()) {
                Text(
                    text = stringResource(R.string.family_no_children),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            } else {
                state.children.forEach { child ->
                    FamilyRelativeRow(
                        plant = child,
                        onOpen = { actions.onOpenPlant(child.id) },
                        onUnlink = { actions.onUnlinkChild(child.id) }
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = actions.onLinkExisting,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(FAMILY_LINK_EXISTING_BUTTON_TEST_TAG)
                ) { Text(stringResource(R.string.family_link_existing)) }
                OutlinedButton(
                    onClick = actions.onAddNew,
                    modifier = Modifier
                        .weight(1f)
                        .testTag(FAMILY_ADD_NEW_BUTTON_TEST_TAG)
                ) { Text(stringResource(R.string.family_add_new)) }
            }
        }
    }
}

@Composable
private fun FamilySectionLabel(labelRes: Int) {
    Text(
        text = stringResource(labelRes),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** The relative's name, with a status suffix for an archived or hidden plant (#960). */
@Composable
private fun relativeLabel(plant: Plant): String = when {
    plant.archivedAt != null -> stringResource(R.string.family_relative_archived, plant.name)
    plant.hiddenAt != null -> stringResource(R.string.family_relative_hidden, plant.name)
    else -> plant.name
}

/**
 * A parent or child row: tap opens that plant, long-press (or the "Unlink" custom accessibility action)
 * unlinks it. Mirrors Care's tile menu pattern (product ADR-0056).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FamilyRelativeRow(plant: Plant, onOpen: () -> Unit, onUnlink: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    val unlinkLabel = stringResource(R.string.family_unlink)
    Box {
        Text(
            text = relativeLabel(plant),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onOpen,
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = stringResource(R.string.family_row_actions_label),
                    role = Role.Button
                )
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(unlinkLabel) {
                            onUnlink()
                            true
                        }
                    )
                }
                .padding(vertical = 12.dp)
        )
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(unlinkLabel) },
                onClick = {
                    menuOpen = false
                    onUnlink()
                }
            )
        }
    }
}

/**
 * The searchable plant picker behind "Link parent…" and "Link existing plant…" (#960). [candidates] is
 * `null` while loading; the caller has already applied the cycle-safe exclusions
 * ([PlantDetailViewModel.familyCandidates]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FamilyPlantPickerSheet(
    mode: FamilyPickerMode,
    candidates: List<FamilyCandidate>?,
    onSelect: (plantId: Long) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(candidates, query) {
        val needle = query.trim()
        candidates.orEmpty().filter { candidate ->
            needle.isEmpty() || listOfNotNull(candidate.plant.name, candidate.plant.species, candidate.plant.room)
                .any { it.contains(needle, ignoreCase = true) }
        }
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(FAMILY_PICKER_TEST_TAG)
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = stringResource(
                    if (mode == FamilyPickerMode.PARENT) {
                        R.string.family_picker_title_parent
                    } else {
                        R.string.family_picker_title_child
                    }
                ),
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.family_picker_search)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(FAMILY_PICKER_SEARCH_TEST_TAG)
            )
            Spacer(Modifier.height(8.dp))
            when {
                candidates == null -> Unit
                candidates.isEmpty() -> PickerMessage(R.string.family_picker_empty)
                shown.isEmpty() -> PickerMessage(R.string.family_picker_no_match)
                else -> LazyColumn(modifier = Modifier.heightIn(max = PICKER_LIST_MAX_HEIGHT)) {
                    items(shown, key = { it.plant.id }) { candidate ->
                        FamilyCandidateRow(candidate, onClick = { onSelect(candidate.plant.id) })
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private val PICKER_LIST_MAX_HEIGHT = 360.dp

@Composable
private fun PickerMessage(messageRes: Int) {
    Text(
        text = stringResource(messageRes),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(vertical = 16.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FamilyCandidateRow(candidate: FamilyCandidate, onClick: () -> Unit) {
    val plant = candidate.plant
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, role = Role.Button)
            .padding(vertical = 10.dp)
    ) {
        Text(text = relativeLabel(plant), style = MaterialTheme.typography.bodyLarge)
        val detail = listOfNotNull(plant.species, plant.room).joinToString(" · ")
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        candidate.currentParent?.let {
            Text(
                text = stringResource(R.string.family_picker_currently_from, it.name),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** The post-log offer (#960): add a new plant, link an existing one, or skip. */
@Composable
internal fun PropagateOfferDialog(
    plantName: String,
    onNewPlant: () -> Unit,
    onExistingPlant: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(PROPAGATE_OFFER_DIALOG_TEST_TAG),
        title = { Text(stringResource(R.string.propagate_offer_title)) },
        text = {
            Column {
                Text(stringResource(R.string.propagate_offer_message, plantName))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onExistingPlant) { Text(stringResource(R.string.propagate_offer_existing)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onNewPlant) { Text(stringResource(R.string.propagate_offer_new)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.propagate_offer_not_now)) } }
    )
}
