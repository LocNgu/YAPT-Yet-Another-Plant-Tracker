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
}
