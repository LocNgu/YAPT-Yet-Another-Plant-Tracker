@file:Suppress("TooManyFunctions")

package com.yapt.planttracker.ui.screens.today

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareSection
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayCareTypeSection
import com.yapt.planttracker.domain.today.TodayWateringGroup
import com.yapt.planttracker.domain.today.careTypeSections
import com.yapt.planttracker.ui.components.PlantPhoto
import com.yapt.planttracker.ui.screens.plantdetail.PlantDetailTab

const val TODAY_TASK_LIST_TAG = "today_task_list"

private val CARE_TILE_MIN_WIDTH = 140.dp
private val CARE_GRID_SPACING = 12.dp
private val CARE_TILE_SHAPE = RoundedCornerShape(12.dp)
private val CARE_HEADER_MIN_HEIGHT = 48.dp
private const val CARE_GRID_MIN_COLUMNS = 2
private const val CARE_GRID_MAX_COLUMNS = 4
private const val CARE_NAME_MAX_LINES = 2

internal data class TodayTaskActions(
    val onOpen: (Long, PlantDetailTab?) -> Unit,
    val onComplete: (TodayCareTask) -> Unit,
    val onReschedule: (TodayCareTask) -> Unit,
    val onRepot: (TodayCareTask) -> Unit,
    val onPhoto: (TodayCareTask) -> Unit
)

internal fun careGridColumnCount(availablePx: Int, spacingPx: Int, minTilePx: Int): Int =
    ((availablePx + spacingPx) / (minTilePx + spacingPx)).coerceIn(CARE_GRID_MIN_COLUMNS, CARE_GRID_MAX_COLUMNS)

// GridCells.Adaptive has no upper bound, so a tablet or landscape phone would pack 5+ small tiles.
private object CareGridCells : GridCells {
    override fun Density.calculateCrossAxisCellSizes(availableSize: Int, spacing: Int): List<Int> {
        val columns = careGridColumnCount(availableSize, spacing, CARE_TILE_MIN_WIDTH.roundToPx())
        val cellSpace = availableSize - spacing * (columns - 1)
        val base = cellSpace / columns
        val remainder = cellSpace % columns
        return List(columns) { base + if (it < remainder) 1 else 0 }
    }
}

internal fun careTileTag(taskId: String): String = "care_tile_$taskId"

internal fun careTilePhotoTag(taskId: String): String = "care_tile_photo_$taskId"

internal fun sectionCollapseKey(section: TodayCareSection): String = "section:${section.name}"

internal fun wateringGroupCollapseKey(group: TodayWateringGroup): String = "watering:${group.name}"

@Composable
internal fun TodayCareGrid(
    tasks: List<TodayCareTask>,
    gridState: LazyGridState,
    collapsedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    actions: TodayTaskActions
) {
    val sections = remember(tasks) { careTypeSections(tasks) }
    LazyVerticalGrid(
        columns = CareGridCells,
        state = gridState,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(CARE_GRID_SPACING),
        verticalArrangement = Arrangement.spacedBy(CARE_GRID_SPACING),
        modifier = Modifier.fillMaxSize().testTag(TODAY_TASK_LIST_TAG)
    ) {
        for (section in sections) {
            careSection(section, collapsedGroups, onToggleGroup, actions)
        }
    }
}

private fun LazyGridScope.careSection(
    section: TodayCareTypeSection,
    collapsedGroups: Set<String>,
    onToggleGroup: (String) -> Unit,
    actions: TodayTaskActions
) {
    val sectionKey = sectionCollapseKey(section.section)
    val sectionCollapsed = sectionKey in collapsedGroups
    item(key = sectionKey, span = { GridItemSpan(maxLineSpan) }) {
        CareGroupHeader(
            title = careSectionTitle(section.section),
            plantCount = section.plantCount,
            collapsed = sectionCollapsed,
            isSubGroup = false,
            onToggle = { onToggleGroup(sectionKey) }
        )
    }
    if (sectionCollapsed) return
    if (section.subGroups.isEmpty()) {
        careTiles(section.tasks, actions)
        return
    }
    for (subGroup in section.subGroups) {
        val groupKey = wateringGroupCollapseKey(subGroup.group)
        val groupCollapsed = groupKey in collapsedGroups
        item(key = groupKey, span = { GridItemSpan(maxLineSpan) }) {
            CareGroupHeader(
                title = wateringGroupTitle(subGroup.group),
                plantCount = subGroup.plantCount,
                collapsed = groupCollapsed,
                isSubGroup = true,
                onToggle = { onToggleGroup(groupKey) }
            )
        }
        if (!groupCollapsed) careTiles(subGroup.tasks, actions)
    }
}

private fun LazyGridScope.careTiles(tasks: List<TodayCareTask>, actions: TodayTaskActions) {
    items(tasks, key = { it.id }) { task -> CareTaskTile(task, actions) }
}

@Composable
private fun CareGroupHeader(
    title: String,
    plantCount: Int,
    collapsed: Boolean,
    isSubGroup: Boolean,
    onToggle: () -> Unit
) {
    val label = stringResource(R.string.care_group_header, title, plantCount)
    val description = pluralStringResource(R.plurals.care_group_header_cd, plantCount, title, plantCount)
    val state = stringResource(
        if (collapsed) R.string.care_group_state_collapsed else R.string.care_group_state_expanded
    )
    val action = stringResource(
        if (collapsed) R.string.care_group_expand_action else R.string.care_group_collapse_action
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CARE_HEADER_MIN_HEIGHT)
            .clickable(onClickLabel = action, role = Role.Button, onClick = onToggle)
            .semantics {
                heading()
                contentDescription = description
                stateDescription = state
            }
            .padding(top = if (isSubGroup) 0.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (isSubGroup) FontWeight.Medium else FontWeight.Bold,
            color = if (isSubGroup) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        Icon(
            imageVector = if (collapsed) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CareTaskTile(task: TodayCareTask, actions: TodayTaskActions) {
    var menuOpen by remember { mutableStateOf(false) }
    val description = tileDescription(task)
    val menuActions = careMenuActions(task.kind)
    val menuLabels = menuActions.map { careMenuActionLabel(it) }
    val customActions = menuActions.mapIndexed { index, action ->
        CustomAccessibilityAction(menuLabels[index]) {
            actions.perform(action, task)
            true
        }
    }
    Box {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(CARE_TILE_SHAPE)
                .combinedClickable(
                    onClick = { actions.onOpen(task.plant.id, task.kind.plantDetailTab()) },
                    onLongClick = { menuOpen = true },
                    onLongClickLabel = stringResource(R.string.care_tile_actions_label),
                    role = Role.Button
                )
                .semantics {
                    contentDescription = description
                    this.customActions = customActions
                }
                .testTag(careTileTag(task.id))
        ) {
            CareTilePhoto(task)
            CareTileLabels(task)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            menuActions.forEachIndexed { index, action ->
                DropdownMenuItem(
                    text = { Text(menuLabels[index]) },
                    onClick = {
                        menuOpen = false
                        actions.perform(action, task)
                    }
                )
            }
        }
    }
}

@Composable
private fun CareTileLabels(task: TodayCareTask) {
    Text(
        text = task.plant.name,
        style = MaterialTheme.typography.titleSmall,
        maxLines = CARE_NAME_MAX_LINES,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 6.dp)
    )
    tileSecondLine(task)?.let {
        Text(
            text = it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = CARE_NAME_MAX_LINES,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CareTilePhoto(task: TodayCareTask) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().clearAndSetSemantics { testTag = careTilePhotoTag(task.id) }
    ) {
        PlantPhoto(
            uri = task.plant.coverPhotoUri,
            size = maxWidth,
            rounded = false
        )
    }
}

@Composable
private fun tileDescription(task: TodayCareTask): String {
    val plantName = task.plant.name
    return when (task.kind) {
        TodayCareKind.WATER -> stringResource(R.string.care_tile_water_cd, plantName)
        TodayCareKind.FERTILIZE -> stringResource(R.string.care_tile_fertilize_cd, plantName)
        TodayCareKind.WATER_AND_FERTILIZE -> stringResource(R.string.care_tile_water_fertilize_cd, plantName)
        TodayCareKind.REPOT -> stringResource(R.string.care_tile_repot_cd, plantName)
        TodayCareKind.PHOTO -> stringResource(R.string.care_tile_photo_cd, plantName)
        TodayCareKind.CUSTOM_REMINDER,
        TodayCareKind.ISSUE_TREATMENT ->
            stringResource(R.string.care_tile_reminder_cd, plantName, tileSecondLine(task).orEmpty())
    }
}

@Composable
private fun tileSecondLine(task: TodayCareTask): String? = when (task.kind) {
    TodayCareKind.CUSTOM_REMINDER -> task.customReminder?.name.orEmpty()
    TodayCareKind.ISSUE_TREATMENT -> stringResource(
        R.string.today_task_treatment,
        task.issueName ?: task.customReminder?.name.orEmpty()
    )
    TodayCareKind.WATER_AND_FERTILIZE -> stringResource(R.string.today_action_water_fertilize)
    TodayCareKind.WATER,
    TodayCareKind.FERTILIZE,
    TodayCareKind.REPOT,
    TodayCareKind.PHOTO -> null
}

@Composable
private fun careSectionTitle(section: TodayCareSection): String = when (section) {
    TodayCareSection.WATERING -> stringResource(R.string.care_section_watering)
    TodayCareSection.ISSUE_TREATMENTS -> stringResource(R.string.care_section_issue_treatments)
    TodayCareSection.FERTILIZING -> stringResource(R.string.care_section_fertilizing)
    TodayCareSection.CUSTOM_REMINDERS -> stringResource(R.string.care_section_custom_reminders)
    TodayCareSection.REPOTTING -> stringResource(R.string.care_section_repotting)
    TodayCareSection.PHOTOS -> stringResource(R.string.care_section_photos)
}

@Composable
private fun wateringGroupTitle(group: TodayWateringGroup): String = when (group) {
    TodayWateringGroup.OVERDUE -> stringResource(R.string.date_group_overdue)
    TodayWateringGroup.TODAY -> stringResource(R.string.date_group_today)
    TodayWateringGroup.NEXT_THREE_DAYS -> stringResource(R.string.date_group_next_three_days)
}
