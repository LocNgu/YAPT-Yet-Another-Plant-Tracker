package com.yapt.planttracker.worker

import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.yapt.planttracker.YaptApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OrphanPhotoCleanupWorkerTest {

    @Test
    fun `worker sweeps orphaned files from the images directory and succeeds`() = runBlocking {
        val app: YaptApplication = ApplicationProvider.getApplicationContext()
        val imagesDir = File(app.filesDir, "images").apply { mkdirs() }
        val orphan = File(imagesDir, "orphan.jpg").apply {
            writeText("data")
            setLastModified(System.currentTimeMillis() - OrphanPhotoCleanupWorkerTestConstants.OLD_ENOUGH_MILLIS)
        }

        val result = TestListenableWorkerBuilder<OrphanPhotoCleanupWorker>(app).build().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertFalse(orphan.exists())
    }
}

private object OrphanPhotoCleanupWorkerTestConstants {
    const val OLD_ENOUGH_MILLIS = 25L * 60 * 60 * 1000
}
