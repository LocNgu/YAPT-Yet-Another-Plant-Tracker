package com.yapt.planttracker.notification

import android.Manifest
import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NotificationPermissionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `isGranted returns false when POST_NOTIFICATIONS is denied`() {
        assertFalse(NotificationPermission.isGranted(context))
    }

    @Test
    fun `isGranted returns true when POST_NOTIFICATIONS is granted`() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(NotificationPermission.isGranted(context))
    }
}
