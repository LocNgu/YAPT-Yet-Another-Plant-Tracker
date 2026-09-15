package com.yapt.planttracker.notification

import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringFeedback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StillMoistReceiverTest {

    private lateinit var app: YaptApplication

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        clearDatabase()
    }

    @After
    fun tearDown() {
        clearDatabase()
    }

    private fun clearDatabase() = runBlocking {
        app.database.careLogDao().deleteAll()
        app.database.plantDao().deleteAll()
    }

    @Test
    fun `handleStillMoist writes a CHECK log with TOO_SOON feedback and defers the due date`() = runBlocking {
        val plantId = app.plantRepository.addPlant(
            Plant(name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        )

        StillMoistReceiver().handleStillMoist(app, plantId)

        val log = app.careLogRepository.getLastLogOfType(plantId, CareType.CHECK)
        assertTrue(log != null)
        assertEquals(WateringFeedback.TOO_SOON, log!!.wateringFeedback)
        val updated = app.plantRepository.getPlantById(plantId).first()!!
        assertTrue(updated.wateringDueDateOverride != null)
    }

    @Test
    fun `handleStillMoist ignores an unknown plant id without crashing`() = runBlocking {
        StillMoistReceiver().handleStillMoist(app, plantId = 999_999L)
        // No exception is the assertion here — mirrors SkipWateringReceiver's existing behavior.
    }

    // #714: the same-day CHECK dedupe guard must not also veto the reschedule. Before the fix, a
    // second same-day call silently left `wateringDueDateOverride` unchanged.
    //
    // Both calls derive their due date from `System.currentTimeMillis() + suggestedStillMoistDeferralDays()`
    // (no WATER log exists, so the deferral is always the same fallback constant) — comparing "before"
    // and "after" real-clock-derived timestamps directly is flaky, since two calls in the same test can
    // land in the same millisecond (#714 review round 1). Instead, a fixed sentinel override — a value
    // that could never be produced by "now + N days" — is written directly between the two
    // `handleStillMoist()` calls, so the assertion doesn't depend on the host clock at all.
    @Test
    fun `a second same-day handleStillMoist call still moves the due date override`() = runBlocking {
        val plantId = app.plantRepository.addPlant(
            Plant(name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        )

        StillMoistReceiver().handleStillMoist(app, plantId)

        val sentinelOverride = 1_000L
        val afterFirst = app.plantRepository.getPlantById(plantId).first()!!
        app.plantRepository.updatePlant(afterFirst.copy(wateringDueDateOverride = sentinelOverride))

        StillMoistReceiver().handleStillMoist(app, plantId)
        val overrideAfterSecond = app.plantRepository.getPlantById(plantId).first()!!.wateringDueDateOverride

        assertTrue(overrideAfterSecond != null)
        assertTrue(overrideAfterSecond != sentinelOverride)
        // No second CHECK log was written — the dedupe guard still holds.
        assertEquals(1, app.careLogRepository.getCareLogCount(plantId))
    }
}
