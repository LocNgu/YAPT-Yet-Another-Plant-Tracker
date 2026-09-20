package com.yapt.planttracker

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Guards the registration itself (#757): `robolectric.properties` is the only thing wiring
 * [TestYaptApplication] in, and if it is moved, renamed, or lost in a resource-merge change, every
 * Robolectric test silently goes back to racing production's app-start work — reintroducing the
 * intermittent `SkipWateringReceiverTest` failure with nothing pointing at the cause. This class
 * deliberately declares no `application=` of its own, so it fails the moment that file stops applying.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TestYaptApplicationTest {

    @Test
    fun `robolectric runs unit tests against TestYaptApplication, not the production application`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(TestYaptApplication::class.java, app.javaClass)
    }
}
