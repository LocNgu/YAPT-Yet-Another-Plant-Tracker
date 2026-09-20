package com.yapt.planttracker

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Asserts the outcome #757's fix depends on: Robolectric hands unit tests an application whose
 * app-start work is suppressed, so the #702 fixup can never race a test's fixtures. It deliberately
 * declares no `application=` of its own, taking whatever the module-wide configuration resolves to.
 *
 * **What this does and does not catch.** Two independent routes select [TestYaptApplication] — the
 * `robolectric.properties` registration, and Robolectric's own `Test` + <application simple name>
 * naming convention, which applies in the same package regardless of that file. So this test does
 * *not* fail if the properties file alone is moved, renamed, or lost: the convention still supplies
 * the class, and the fix genuinely still holds, which is why passing is the correct result there. It
 * does fail if the effective configuration is pointed back at the production [YaptApplication] (a
 * module-wide `@Config` change, or an edited properties file), and it fails loudly if that file names
 * a class that does not exist. Treat it as a guard on the end state, not on any one wiring mechanism.
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
