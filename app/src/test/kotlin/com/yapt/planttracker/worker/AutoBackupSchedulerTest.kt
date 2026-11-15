package com.yapt.planttracker.worker

import com.yapt.planttracker.domain.autobackup.AutoBackupFrequency
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AutoBackupSchedulerTest {

    @Test
    fun `request requires battery and storage not low and no network`() {
        val spec = AutoBackupScheduler.buildRequest(AutoBackupFrequency.WEEKLY).workSpec
        assertTrue(spec.constraints.requiresBatteryNotLow())
        assertTrue(spec.constraints.requiresStorageNotLow())
        assertFalse(spec.constraints.requiredNetworkType != androidx.work.NetworkType.NOT_REQUIRED)
        assertEquals(TimeUnit.DAYS.toMillis(7), spec.intervalDuration)
    }

    @Test
    fun `daily request repeats every day`() {
        val spec = AutoBackupScheduler.buildRequest(AutoBackupFrequency.DAILY).workSpec
        assertEquals(TimeUnit.DAYS.toMillis(1), spec.intervalDuration)
    }
}
