package com.yapt.planttracker.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.ui.util.relativeDateText
import com.yapt.planttracker.util.toStartOfDayMillis
import java.time.LocalDate

private val PLANT_DUE_ROW_PHOTO_SIZE = 64.dp

/**
 * A slim Plant List-style row (photo, name, room, due date). [checked] `null` is the read-only variant; otherwise
 * the whole row is one checkbox, so the plant name, room, due date and tick state are read together.
 */
@Composable
fun PlantDueRow(
    plant: Plant,
    dueDate: LocalDate,
    modifier: Modifier = Modifier,
    checked: Boolean? = null,
    onCheckedChange: (Boolean) -> Unit = {}
) {
    val rowModifier = if (checked != null) {
        modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange)
    } else {
        modifier.semantics(mergeDescendants = true) {}
    }
    Card(
        modifier = rowModifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (checked != null) Checkbox(checked = checked, onCheckedChange = null)
            PlantPhoto(
                uri = plant.coverPhotoUri,
                size = PLANT_DUE_ROW_PHOTO_SIZE,
                rounded = false,
                modifier = Modifier.padding(start = if (checked != null) 8.dp else 0.dp).clearAndSetSemantics {}
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp, end = 4.dp)) {
                Text(
                    text = plant.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                plant.room?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = relativeDateText(dueDate.toStartOfDayMillis()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
