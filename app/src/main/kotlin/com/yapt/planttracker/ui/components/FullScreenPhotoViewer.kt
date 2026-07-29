package com.yapt.planttracker.ui.components

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.GalleryPhoto
import com.yapt.planttracker.util.DateUtils

@Composable
fun FullScreenPhotoViewer(
    photos: List<GalleryPhoto>,
    initialIndex: Int = 0,
    onDismiss: () -> Unit,
    onDelete: ((uri: String) -> Unit)? = null
) {
    if (photos.isEmpty()) return
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(0, photos.lastIndex)) { photos.size }
    val photoDateCd = stringResource(R.string.cd_photo_viewer_date)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        // The dialog window cannot reliably extend behind the status bar on all devices, so any
        // screen area the window doesn't cover would reveal the underlying PlantDetail content
        // (#444). Instead of fighting the window bounds, paint everything behind the dialog fully
        // black via the window dim (a dialog's dim layer spans the whole screen, including the
        // status-bar strip) so the viewer reads as one deliberate full-dark overlay. Light
        // status-bar icons keep the bar legible over black; `statusBarsPadding()` below keeps the
        // action buttons clear of the status bar.
        val view = LocalView.current
        val dialogWindow = remember(view) { (view.parent as? DialogWindowProvider)?.window }
        if (dialogWindow != null) {
            SideEffect {
                dialogWindow.setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                WindowCompat.setDecorFitsSystemWindows(dialogWindow, false)
                dialogWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                dialogWindow.setDimAmount(1f)
                WindowCompat.getInsetsController(dialogWindow, dialogWindow.decorView)
                    .isAppearanceLightStatusBars = false
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
                    model = photos[page].uri,
                    contentDescription = stringResource(R.string.cd_plant_photo_fullscreen),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            // The date label is shown for every photo (even a single one, when the "N / M"
            // indicator is hidden); the page indicator sits above it when there's more than one.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
            ) {
                if (photos.size > 1) {
                    Text(
                        text = stringResource(
                            R.string.photo_viewer_page_indicator,
                            pagerState.currentPage + 1,
                            photos.size
                        ),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier
                            .background(Color.Black.copy(alpha = 0.55f), shape = MaterialTheme.shapes.small)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
                val photoDate = DateUtils.formatDate(photos[pagerState.currentPage].timestamp)
                Text(
                    text = photoDate,
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .semantics {
                            contentDescription = photoDateCd.format(photoDate)
                        }
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
                        onClick = { onDelete(photos[pagerState.currentPage].uri) },
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
