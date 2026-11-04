package com.yapt.planttracker.ui.components

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.domain.model.GalleryPhoto
import com.yapt.planttracker.domain.model.GalleryPhotoSource
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Set-as-cover action and cover badge semantics (#511, product ADR-0080). */
@RunWith(AndroidJUnit4::class)
class CoverPhotoSemanticsTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private val photos = listOf(
        GalleryPhoto("file:///a.jpg", 2L, GalleryPhotoSource.FromCareLog(1L)),
        GalleryPhoto("file:///b.jpg", 1L, GalleryPhotoSource.FromCareLog(2L))
    )

    @Test
    fun viewerOnNonCover_offersEnabledSetCoverAction_thatReportsTheUri() {
        var picked: String? = null
        composeTestRule.setContent {
            FullScreenPhotoViewer(
                photos = photos,
                initialIndex = 1,
                coverUri = "file:///a.jpg",
                onSetCover = { picked = it },
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithContentDescription(str(R.string.cd_set_cover_photo))
            .assertIsEnabled()
            .performClick()
        assertEquals("file:///b.jpg", picked)
    }

    @Test
    fun viewerOnCurrentCover_showsSelectedDisabledAction() {
        composeTestRule.setContent {
            FullScreenPhotoViewer(
                photos = photos,
                initialIndex = 0,
                coverUri = "file:///a.jpg",
                onSetCover = {},
                onDismiss = {}
            )
        }

        composeTestRule.onNodeWithContentDescription(str(R.string.cd_cover_photo_current))
            .assertIsNotEnabled()
            .assertIsSelected()
    }

    @Test
    fun galleryThumbnail_ofCover_exposesCoverPhotoDescription() {
        composeTestRule.setContent {
            PhotoGallery(
                photoUris = listOf("file:///a.jpg", "file:///b.jpg"),
                coverUri = "file:///a.jpg",
                onPhotoClick = {}
            )
        }

        composeTestRule.onNodeWithContentDescription(str(R.string.cd_cover_photo)).assertExists()
    }
}
