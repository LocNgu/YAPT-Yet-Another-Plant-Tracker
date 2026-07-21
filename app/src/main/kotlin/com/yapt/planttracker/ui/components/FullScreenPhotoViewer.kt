package com.yapt.planttracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import com.yapt.planttracker.R

@Composable
fun FullScreenPhotoViewer(
    uris: List<String>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
    onDelete: ((uri: String) -> Unit)? = null
) {
    if (uris.isEmpty()) return
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, uris.lastIndex)) { uris.size }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // Make the dialog window fill the entire screen and draw edge-to-edge behind the
        // status/navigation bars so the black background fully covers the screen instead of
        // leaving a strip that reveals the underlying PlantDetail content (#444).
        // `usePlatformDefaultWidth = false` only stretches the width, so without forcing the
        // window layout to MATCH_PARENT the window stays below the status bar. `statusBarsPadding()`
        // below still keeps the action buttons clear of the status bar.
        val view = LocalView.current
        val dialogWindow = remember(view) { (view.parent as? DialogWindowProvider)?.window }
        if (dialogWindow != null) {
            SideEffect {
                dialogWindow.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                AsyncImage(
                    model = uris[page],
                    contentDescription = stringResource(R.string.cd_plant_photo_fullscreen),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (uris.size > 1) {
                Text(
                    text = stringResource(
                        R.string.photo_viewer_page_indicator,
                        pagerState.currentPage + 1,
                        uris.size
                    ),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp)
                        .background(Color.Black.copy(alpha = 0.55f), shape = MaterialTheme.shapes.small)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
            ) {
                if (onDelete != null) {
                    IconButton(
                        onClick = { onDelete(uris[pagerState.currentPage]) },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.60f)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.cd_delete_photo),
                            tint = Color.White
                        )
                    }
                }
                IconButton(
                    onClick = onDismiss,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = Color.Black.copy(alpha = 0.60f)
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_close_photo_viewer),
                        tint = Color.White
                    )
                }
            }
        }
    }
}
