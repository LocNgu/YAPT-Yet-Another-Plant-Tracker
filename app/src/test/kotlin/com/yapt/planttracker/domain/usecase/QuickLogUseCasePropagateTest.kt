package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * PROPAGATE logging (#960, product ADR-0073) against a real in-memory Room database: the log-only type,
 * the same-day duplicate guard, and the once-at-creation confidence drop of 2 with its single
 * `PROPAGATE_CONFIDENCE_DROP` row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLogUseCasePropagateTest {

    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private lateinit var db: PlantDatabase
    private lateinit var plantRepo: PlantRepository
    private lateinit var careLogRepo: CareLogRepository
    private lateinit var adjustmentRepo: WateringAdjustmentRepository
    private lateinit var useCase: QuickLogUseCase

    private val today = utcMillis(LocalDate.of(2026, 10, 1), 9)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PlantDatabase::class.java
        ).allowMainThreadQueries().build()
        plantRepo = PlantRepository(db.plantDao())
        careLogRepo = CareLogRepository(db.careLogDao())
        adjustmentRepo = WateringAdjustmentRepository(db.wateringAdjustmentDao())
        val application: Application = mockk(relaxed = true)
        val dataStore: DataStore<Preferences> = mockk { every { data } returns flowOf(emptyPreferences()) }
        useCase = QuickLogUseCase(
            application,
            plantRepo,
            careLogRepo,
            PlantPhotoRepository(db.plantPhotoDao()),
            dataStore,
            db,
            adjustmentRepo,
            nowProvider = { today }
        )
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(originalTimeZone)
    }

    private fun utcMillis(date: LocalDate, hour: Int): Long =
        date.atTime(hour, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private suspend fun plant(confidence: Int?, modify: (Plant) -> Plant = { it }): Plant {
        val id = plantRepo.addPlant(
            modify(
                Plant(
                    name = "Pothos",
                    createdAt = 0L,
                    updatedAt = 0L,
                    wateringIntervalDays = 7,
                    wateringBaseIntervalDays = 7.0,
                    wateringConfidence = confidence
                )
            )
        )
        return plantRepo.getPlantById(id).first()!!
    }

    private suspend fun reload(plant: Plant): Plant = plantRepo.getPlantById(plant.id).first()!!

    private suspend fun dropRows(plant: Plant) =
        adjustmentRepo.getByTrigger(plant.id, WateringAdjustmentTrigger.PROPAGATE_CONFIDENCE_DROP)

    @Test
    fun `PROPAGATE is the last care type`() {
        assertEquals(CareType.PROPAGATE, CareType.entries.last())
    }

    @Test
    fun `a PROPAGATE log is stored and read back as PROPAGATE`() = runTest {
        val plant = plant(confidence = null)

        val outcome = useCase.quickLog(plant, CareType.PROPAGATE, today)

        assertTrue(outcome.logged)
        assertEquals(listOf(CareType.PROPAGATE), careLogRepo.getLogsForPlant(plant.id).first().map { it.careType })
    }

    @Test
    fun `confidence 5 drops to 3 with exactly one row and nothing else moves`() = runTest {
        val plant = plant(confidence = 5) {
            it.copy(wateringDueDateOverride = 123L, wateringResetAt = 456L, wateringFreezeUntil = 789L)
        }

        useCase.quickLog(plant, CareType.PROPAGATE, today)

        val after = reload(plant)
        assertEquals(3, after.wateringConfidence)
        assertEquals(7, after.wateringIntervalDays)
        assertEquals(7.0, after.wateringBaseIntervalDays!!, 0.0)
        assertEquals(123L, after.wateringDueDateOverride)
        assertEquals(456L, after.wateringResetAt)
        assertEquals(789L, after.wateringFreezeUntil)
        val rows = dropRows(plant)
        assertEquals(1, rows.size)
        assertEquals(rows[0].beforeIntervalDays, rows[0].afterIntervalDays)
    }

    @Test
    fun `confidence 1 floors at 0`() = runTest {
        val plant = plant(confidence = 1)

        useCase.quickLog(plant, CareType.PROPAGATE, today)

        assertEquals(0, reload(plant).wateringConfidence)
        assertEquals(1, dropRows(plant).size)
    }

    @Test
    fun `confidence 0 stays 0 and writes no row`() = runTest {
        val plant = plant(confidence = 0)

        useCase.quickLog(plant, CareType.PROPAGATE, today)

        assertEquals(0, reload(plant).wateringConfidence)
        assertTrue(dropRows(plant).isEmpty())
    }

    @Test
    fun `null confidence stays null and writes no row`() = runTest {
        val plant = plant(confidence = null)

        useCase.quickLog(plant, CareType.PROPAGATE, today)

        assertNull(reload(plant).wateringConfidence)
        assertTrue(dropRows(plant).isEmpty())
    }

    @Test
    fun `the drop reads the plant fresh instead of trusting the caller's snapshot`() = runTest {
        val stale = plant(confidence = 5)
        plantRepo.updateWateringConfidence(stale.id, 4, 1L)

        useCase.quickLog(stale, CareType.PROPAGATE, today)

        assertEquals(2, reload(stale).wateringConfidence)
    }

    @Test
    fun `a backdated PROPAGATE inside a freeze window still drops once`() = runTest {
        val plant = plant(confidence = 4) { it.copy(wateringFreezeUntil = today + 1_000_000L) }

        useCase.quickLog(plant, CareType.PROPAGATE, utcMillis(LocalDate.of(2026, 9, 1), 9))

        assertEquals(2, reload(plant).wateringConfidence)
    }

    @Test
    fun `a second PROPAGATE on the same day is accepted but drops nothing more`() = runTest {
        val plant = plant(confidence = 5)
        useCase.quickLog(plant, CareType.PROPAGATE, today)

        val second = useCase.quickLog(plant, CareType.PROPAGATE, today + 1_000L)

        assertTrue(second.logged)
        assertEquals(3, reload(plant).wateringConfidence)
        assertEquals(2, careLogRepo.getLogsForPlant(plant.id).first().size)
        assertEquals(1, dropRows(plant).size)
    }

    @Test
    fun `any number of same-day PROPAGATE logs drop once, whatever order they are dated in`() = runTest {
        val plant = plant(confidence = 5)

        useCase.quickLog(plant, CareType.PROPAGATE, today + 5_000L)
        useCase.quickLog(plant, CareType.PROPAGATE, today)
        useCase.quickLog(plant, CareType.PROPAGATE, today + 9_000L)

        assertEquals(3, reload(plant).wateringConfidence)
        assertEquals(3, careLogRepo.getLogsForPlant(plant.id).first().size)
        assertEquals(1, dropRows(plant).size)
    }

    @Test
    fun `PROPAGATE on the next day drops confidence again`() = runTest {
        val plant = plant(confidence = 5)
        useCase.quickLog(plant, CareType.PROPAGATE, today)

        val next = useCase.quickLog(plant, CareType.PROPAGATE, today + 86_400_000L)

        assertTrue(next.logged)
        assertEquals(1, reload(plant).wateringConfidence)
        assertEquals(2, dropRows(plant).size)
    }

    @Test
    fun `REPOT and PRUNE stay unguarded on the same day`() = runTest {
        val plant = plant(confidence = 3)

        assertTrue(useCase.quickLog(plant, CareType.REPOT, today).logged)
        assertTrue(useCase.quickLog(plant, CareType.REPOT, today + 1_000L).logged)
        assertTrue(useCase.quickLog(plant, CareType.PRUNE, today).logged)
        assertTrue(useCase.quickLog(plant, CareType.PRUNE, today + 1_000L).logged)
    }

    @Test
    fun `editing or deleting a PROPAGATE log changes no confidence and writes no row`() = runTest {
        val plant = plant(confidence = 5)
        useCase.quickLog(plant, CareType.PROPAGATE, today)
        val log = careLogRepo.getLogsForPlant(plant.id).first().single()

        careLogRepo.updateLog(log.copy(notes = "three cuttings", loggedAt = today - 86_400_000L))
        assertEquals(3, reload(plant).wateringConfidence)
        careLogRepo.deleteLog(log)

        assertEquals(3, reload(plant).wateringConfidence)
        assertEquals(1, dropRows(plant).size)
    }

    @Test
    fun `PROPAGATE then REPOT on the same day ends like a lone REPOT and keeps both rows`() = runTest {
        val combined = plant(confidence = 4)
        val lone = plant(confidence = 4)

        useCase.quickLog(combined, CareType.PROPAGATE, today)
        useCase.quickLog(combined, CareType.REPOT, today)
        useCase.quickLog(lone, CareType.REPOT, today)

        val a = reload(combined)
        val b = reload(lone)
        assertEquals(0, a.wateringConfidence)
        assertEquals(b.wateringConfidence, a.wateringConfidence)
        assertEquals(b.wateringResetAt, a.wateringResetAt)
        assertEquals(b.wateringFreezeUntil, a.wateringFreezeUntil)
        assertEquals(1, dropRows(combined).size)
        assertEquals(
            1,
            adjustmentRepo.getByTrigger(combined.id, WateringAdjustmentTrigger.REPOT_RESET).size
        )
    }

    @Test
    fun `REPOT then PROPAGATE on the same day drops nothing`() = runTest {
        val plant = plant(confidence = 4)

        useCase.quickLog(plant, CareType.REPOT, today)
        useCase.quickLog(plant, CareType.PROPAGATE, today)

        assertEquals(0, reload(plant).wateringConfidence)
        assertTrue(dropRows(plant).isEmpty())
    }

    @Test
    fun `PROPAGATE keeps a repot plan and a REPOT still clears it`() = runTest {
        val planStart = utcMillis(LocalDate.of(2027, 3, 1), 0)
        val madeAt = utcMillis(LocalDate.of(2026, 9, 29), 15)
        val plant = plant(confidence = 3) {
            it.copy(
                repottingSeasons = setOf(FertilizingSeason.SPRING),
                repotPlanSeasonStartAt = planStart,
                repotPlanMadeAt = madeAt
            )
        }

        useCase.quickLog(plant, CareType.PROPAGATE, today)
        val afterPropagate = reload(plant)
        assertEquals(planStart, afterPropagate.repotPlanSeasonStartAt)
        assertEquals(madeAt, afterPropagate.repotPlanMadeAt)

        useCase.quickLog(plant, CareType.REPOT, today)
        assertNull(reload(plant).repotPlanSeasonStartAt)
    }

    @Test
    fun `a PROPAGATE log is excluded from the duplicate guard of other types`() = runTest {
        val plant = plant(confidence = 2)
        careLogRepo.addLog(CareLog(plantId = plant.id, careType = CareType.PROPAGATE, loggedAt = today))

        assertTrue(useCase.quickLog(plant, CareType.WATER, today).logged)
        assertTrue(useCase.quickLog(plant, CareType.FERTILIZE, today).logged)
    }
}
