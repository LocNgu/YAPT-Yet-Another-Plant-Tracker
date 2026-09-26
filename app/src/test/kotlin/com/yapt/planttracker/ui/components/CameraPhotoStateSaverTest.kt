package com.yapt.planttracker.ui.components

import android.net.Uri
import androidx.compose.runtime.saveable.SaverScope
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #706: the in-flight camera capture target must survive Activity recreation, since `TakePicture` returns only
 * a `Boolean` and the result callback has no URI of its own. Round-trips [CameraPhotoState.Saver] the way
 * `rememberSaveable` does across a recreation, then finishes the capture on the restored instance.
 */
@RunWith(RobolectricTestRunner::class)
class CameraPhotoStateSaverTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val saverScope = SaverScope { true }

    private fun recreate(state: CameraPhotoState): CameraPhotoState = with(CameraPhotoState.Saver) {
        val saved = requireNotNull(saverScope.save(state))
        requireNotNull(restore(saved))
    }

    private fun pendingState(file: java.io.File, uri: Uri) = CameraPhotoState().apply {
        pendingCameraFile = file
        pendingCameraUri = uri
    }

    @Test
    fun successfulCaptureAfterRecreationStillDeliversUri() {
        val file = tempFolder.newFile("plant_photo_1.jpg")
        val uri = Uri.parse("content://com.yapt.planttracker.fileprovider/images/plant_photo_1.jpg")
        val restored = recreate(pendingState(file, uri))

        var delivered: Uri? = null
        restored.finishCapture(success = true, scope = TestScope()) { delivered = it }

        assertEquals(uri, delivered)
        assertTrue(file.exists())
        assertNull(restored.pendingCameraFile)
        assertNull(restored.pendingCameraUri)
    }

    @Test
    fun cancelledCaptureAfterRecreationStillDeletesTempFile() {
        val file = tempFolder.newFile("plant_photo_2.jpg")
        val uri = Uri.parse("content://com.yapt.planttracker.fileprovider/images/plant_photo_2.jpg")
        val restored = recreate(pendingState(file, uri))

        var delivered: Uri? = null
        restored.finishCapture(success = false, scope = TestScope()) { delivered = it }

        assertNull(delivered)
        assertFalse(file.exists())
    }

    @Test
    fun idleStateRoundTripsWithNoPendingCapture() {
        val restored = recreate(CameraPhotoState())

        assertNull(restored.pendingCameraFile)
        assertNull(restored.pendingCameraUri)
    }
}
