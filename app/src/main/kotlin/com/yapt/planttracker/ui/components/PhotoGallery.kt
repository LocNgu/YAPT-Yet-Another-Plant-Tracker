package com.yapt.planttracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
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
    coverUri: String? = null,
    modifier: Modifier = Modifier
) {
    if (photoUris.isEmpty()) return

    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(photoUris, key = { it }) { uri ->
            val isCover = uri == coverUri
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPhotoClick(uri) }
            ) {
                AsyncImage(
                    model = uri,
                    contentDescription = stringResource(
                        if (isCover) R.string.cd_cover_photo else R.string.cd_care_log_photo
                    ),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (isCover) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(4.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                            .padding(3.dp)
                            .size(14.dp)
                    )
                }
            }
        }
    }
}
