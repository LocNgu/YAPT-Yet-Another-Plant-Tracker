package com.yapt.planttracker.notification

import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.domain.model.Plant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SkipWateringReceiverTest {

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
    fun `skipWatering advances a fresh due date override by one day`() = runBlocking {
        val before = System.currentTimeMillis()
        val plantId = app.plantRepository.addPlant(
            Plant(name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        )

        SkipWateringReceiver().skipWatering(app, plantId)

        val updated = app.plantRepository.getPlantById(plantId).first()!!
        val expectedFloor = before + TimeUnit.DAYS.toMillis(1)
        val override = updated.wateringDueDateOverride
        assertEquals(true, override != null && override >= expectedFloor)
    }

    @Test
    fun `skipWatering never changes wateringConfidence or wateringIntervalDays (#570 - not a learning signal)`() =
        runBlocking {
            val plantId = app.plantRepository.addPlant(
                Plant(name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
                    .copy(wateringConfidence = 2)
            )

            SkipWateringReceiver().skipWatering(app, plantId)

            val updated = app.plantRepository.getPlantById(plantId).first()!!
            assertEquals(2, updated.wateringConfidence)
            assertEquals(7, updated.wateringIntervalDays)
        }

    @Test
    fun `skipWatering never writes a wateringBaseIntervalDays value (not a learning signal)`() = runBlocking {
        val plantId = app.plantRepository.addPlant(
            Plant(name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
                .copy(wateringBaseIntervalDays = 7.0)
        )

        SkipWateringReceiver().skipWatering(app, plantId)

        val updated = app.plantRepository.getPlantById(plantId).first()!!
        assertEquals(7.0, updated.wateringBaseIntervalDays)
    }

    @Test
    fun `skipWatering advances a stale past override to at least now plus one day (#741)`() = runBlocking {
        val now = System.currentTimeMillis()
        val staleOverride = now - TimeUnit.DAYS.toMillis(6)
        val plantId = app.plantRepository.addPlant(
            Plant(name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
                .copy(wateringDueDateOverride = staleOverride)
        )

        SkipWateringReceiver().skipWatering(app, plantId)

        val updated = app.plantRepository.getPlantById(plantId).first()!!
        val expectedFloor = now + TimeUnit.DAYS.toMillis(1)
        val override = updated.wateringDueDateOverride
        assertEquals(true, override != null && override >= expectedFloor)
    }

    @Test
    fun `skipWatering advances a future override by exactly one day from that override`() = runBlocking {
        val futureOverride = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(10)
        val plantId = app.plantRepository.addPlant(
            Plant(name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
                .copy(wateringDueDateOverride = futureOverride)
        )

        SkipWateringReceiver().skipWatering(app, plantId)

        val updated = app.plantRepository.getPlantById(plantId).first()!!
        val expected = futureOverride + TimeUnit.DAYS.toMillis(1)
        assertEquals(expected, updated.wateringDueDateOverride)
    }

    /**
     * #733 (technical ADR-0034) DST parity: the deferral uses the same `plusCalendarDays()` arithmetic
     * as `PlantDetailRescheduleActions.rescheduledRelativeDueAt()`, not the old fixed-24h span. A future
     * override at 00:30 local on `America/New_York`'s 2026-11-01 fall-back day advances to Nov 2 local
     * (the fixed `+1 day` arithmetic would have landed at 23:30 local on Nov 1 — the same calendar day).
     * `now` is pinned before the override so the test stays deterministic after that date passes.
     */
    @Test
    fun `skipWatering advances a future override across a DST fall-back day to the next calendar day`() =
        runBlocking {
            val originalTimeZone = TimeZone.getDefault()
            val newYork = ZoneId.of("America/New_York")
            TimeZone.setDefault(TimeZone.getTimeZone(newYork))
            try {
                val futureOverride = LocalDateTime.of(2026, 11, 1, 0, 30)
                    .atZone(newYork).toInstant().toEpochMilli()
                val plantId = app.plantRepository.addPlant(
                    Plant(name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
                        .copy(wateringDueDateOverride = futureOverride)
                )

                // Pinned clock a day before the override, so the override stays the `maxOf` anchor
                // whatever the real wall-clock date is when this test runs.
                val pinnedNow = LocalDateTime.of(2026, 10, 31, 12, 0)
                    .atZone(newYork).toInstant().toEpochMilli()
                SkipWateringReceiver().skipWatering(app, plantId, now = pinnedNow)

                val updated = app.plantRepository.getPlantById(plantId).first()!!
                val override = requireNotNull(updated.wateringDueDateOverride)
                val overrideLocalDate = Instant.ofEpochMilli(override).atZone(newYork).toLocalDate()
                assertEquals(LocalDate.of(2026, 11, 2), overrideLocalDate)
            } finally {
                TimeZone.setDefault(originalTimeZone)
            }
        }
}
