package com.yapt.planttracker.ui.screens.away

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Every stored cover-photo format resolves to a URI the sitter PDF can open (#899). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SitterPhotoLoaderTest {

    @Test
    fun aRestoredBarePathBecomesAFileUri() {
        val uri = SitterPhotoLoader.photoUri("/data/user/0/com.yapt.planttracker/files/restored_photos/basil.jpg")

        assertEquals("file", uri.scheme)
        assertEquals("/data/user/0/com.yapt.planttracker/files/restored_photos/basil.jpg", uri.path)
    }

    @Test
    fun cameraAndGalleryContentUrisAreKeptAsIs() {
        val camera = "content://com.yapt.planttracker.fileprovider/plant_images/plant_photo_1.jpg"
        val gallery = "content://media/picker/0/com.android.providers.media.photopicker/media/42"

        assertEquals(camera, SitterPhotoLoader.photoUri(camera).toString())
        assertEquals(gallery, SitterPhotoLoader.photoUri(gallery).toString())
    }

    @Test
    fun aFileUriIsKeptAsIs() {
        assertEquals("file:///sdcard/basil.jpg", SitterPhotoLoader.photoUri("file:///sdcard/basil.jpg").toString())
    }
}
