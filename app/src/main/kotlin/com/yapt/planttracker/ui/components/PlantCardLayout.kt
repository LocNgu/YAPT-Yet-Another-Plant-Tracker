package com.yapt.planttracker.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yapt.planttracker.R

// Shared plant-card shell (photo, selection checkbox, name, species, three slots). It never takes a
// PlantCareStatus: callers with real due status (PlantCard) and callers with projected dates
// (sitter picker) fill the slots themselves. The slots sit inside a Row(IntrinsicSize.Max), so their
// content must not be a subcompose layout (BoxWithConstraints, lazy lists).
@OptIn(ExperimentalFoundationApi::class)
@Suppress("LongParameterList")
@Composable
fun PlantCardLayout(
    name: String,
    species: String?,
    photoUri: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectionMode: Boolean = false,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onToggleSelect: () -> Unit = {},
    badge: @Composable BoxScope.() -> Unit = {},
    statusLine: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelect() else onClick() },
                onLongClick = onLongClick,
                onLongClickLabel = if (onLongClick != null) {
                    stringResource(R.string.cd_bulk_select_plant)
                } else {
                    null
                },
                role = Role.Button
            )
            // In selection mode, expose the checked state so TalkBack announces
            // "selected" / "not selected" as the user moves between cards.
            .then(
                if (selectionMode) {
                    // `this.` targets the SemanticsPropertyReceiver.selected extension, not the
                    // composable's `selected` parameter (which would otherwise shadow it).
                    Modifier.semantics { this.selected = selected }
                } else {
                    Modifier
                }
            ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        // The card is one merged clickable node, so TalkBack reads descendants in composition
        // order: photo (checkbox, badge), name, species, status line, trailing.
        Row(
            modifier = Modifier.height(IntrinsicSize.Max),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PlantCardPhoto(
                photoUri = photoUri,
                selectionMode = selectionMode,
                selected = selected,
                badge = badge
            )

            Spacer(Modifier.width(12.dp))

            PlantCardText(name = name, species = species, statusLine = statusLine)

            trailing?.invoke(this)
        }
    }
}

@Composable
private fun RowScope.PlantCardText(
    name: String,
    species: String?,
    statusLine: (@Composable () -> Unit)?
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .padding(top = 12.dp, bottom = 12.dp, end = 8.dp)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )

        species?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (statusLine != null) {
            Spacer(Modifier.height(6.dp))
            statusLine()
        }
    }
}

@Composable
private fun PlantCardPhoto(
    photoUri: String?,
    selectionMode: Boolean,
    selected: Boolean,
    badge: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(90.dp)
            .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
    ) {
        if (photoUri != null) {
            AsyncImage(
                model = photoUri,
                contentDescription = stringResource(R.string.cd_plant_photo),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.LocalFlorist,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        if (selectionMode) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
            ) {
                Checkbox(checked = selected, onCheckedChange = null)
            }
        }

        badge()
    }
}
