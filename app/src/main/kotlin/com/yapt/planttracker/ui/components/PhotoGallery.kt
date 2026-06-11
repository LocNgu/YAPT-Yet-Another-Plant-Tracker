package com.yapt.planttracker.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.yapt.planttracker.R

@Composable
fun PhotoGallery(
    photoUris: List<String>,
    onPhotoClick: (String) -> Unit,
    onPhotoLongPress: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    if (photoUris.isEmpty()) return

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(photoUris, key = { it }) { uri ->
            AsyncImage(
                model = uri,
                contentDescription = stringResource(R.string.cd_care_log_photo),
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .combinedClickable(
                        onClick = { onPhotoClick(uri) },
                        onLongClick = { onPhotoLongPress?.invoke(uri) }
                    )
            )
        }
    }
}
