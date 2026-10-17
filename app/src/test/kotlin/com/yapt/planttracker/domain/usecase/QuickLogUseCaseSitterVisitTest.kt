package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.AwayPeriodRepository
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.util.toLocalDate
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
import java.util.concurrent.Executor

/**
 * [QuickLogUseCase.logSitterVisitWaterings] (#899, product ADR-0065) against a real in-memory Room database:
 * the confirm step of a sitter visit writes its WATER logs through the shared quick-log path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLogUseCaseSitterVisitTest {

    private lateinit var db: PlantDatabase
    private lateinit var plantRepo: PlantRepository
    private lateinit var careLogRepo: CareLogRepository
    private lateinit var awayRepo: AwayPeriodRepository
    private lateinit var adjustments: WateringAdjustmentRepository
    private lateinit var useCase: QuickLogUseCase
    private val postWateringReminders = mutableListOf<Long>()
    private val zone = ZoneId.systemDefault()
    private val today = LocalDate.of(2026, 10, 20)
    private var now = today.atTime(15, 0).atZone(zone).toInstant().toEpochMilli()

    @Before
    fun setUp() {
        postWateringReminders.clear()
        val synchronous = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PlantDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(synchronous)
            .setTransactionExecutor(synchronous)
            .build()
        plantRepo = PlantRepository(db.plantDao())
        careLogRepo = CareLogRepository(db.careLogDao())
        awayRepo = AwayPeriodRepository(db.awayPeriodDao())
        adjustments = WateringAdjustmentRepository(db.wateringAdjustmentDao())
        val dataStore: DataStore<Preferences> = mockk { every { data } returns flowOf(emptyPreferences()) }
        useCase = QuickLogUseCase(
            mockk<Application>(relaxed = true),
            plantRepo,
            careLogRepo,
            PlantPhotoRepository(db.plantPhotoDao()),
            dataStore,
            db,
            adjustments,
            nowProvider = { now },
            onWaterLogged = { postWateringReminders.add(it) },
            awayPeriodRepository = awayRepo
        )
    }

    @After
    fun tearDown() = db.close()

    private fun millis(day: LocalDate, hour: Int = 12) = day.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private suspend fun addPlant(name: String, confidence: Int? = 3): Plant {
        val plant = Plant(
            name = name,
            wateringIntervalDays = 7,
            wateringConfidence = confidence,
            pinIntervalToBase = true,
            createdAt = 0L,
            updatedAt = 0L
        )
        return plant.copy(id = plantRepo.addPlant(plant))
    }

    private suspend fun waterLogs(plantId: Long) =
        careLogRepo.getAllLogs().first().filter { it.plantId == plantId && it.careType == CareType.WATER }

    @Test
    fun `it writes one WATER log per plant dated on the visit day`() = runTest {
        val a = addPlant("A")
        val b = addPlant("B")
        val visitDay = today.minusDays(3)

        val result = useCase.logSitterVisitWaterings(listOf(a, b), visitDay)

        assertEquals(2, result.loggedCount)
        assertEquals(0, result.skippedCount)
        listOf(a, b).forEach { plant ->
            val log = waterLogs(plant.id).single()
            assertEquals(visitDay, log.loggedAt.toLocalDate())
            assertEquals(millis(visitDay), log.loggedAt)
        }
    }

    @Test
    fun `it never schedules the post-watering reminder`() = runTest {
        useCase.logSitterVisitWaterings(listOf(addPlant("A")), today.minusDays(1))

        assertTrue(postWateringReminders.isEmpty())
    }

    @Test
    fun `a watering already logged on the visit day is skipped, not doubled`() = runTest {
        val a = addPlant("A")
        val b = addPlant("B")
        val visitDay = today.minusDays(2)
        careLogRepo.addLog(CareLog(plantId = a.id, careType = CareType.WATER, loggedAt = millis(visitDay, hour = 8)))

        val result = useCase.logSitterVisitWaterings(listOf(a, b), visitDay)

        assertEquals(1, result.loggedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(2, result.totalCount)
        assertEquals(1, waterLogs(a.id).size)
        assertEquals(1, waterLogs(b.id).size)
    }

    @Test
    fun `confirming the same visit twice writes nothing the second time`() = runTest {
        val a = addPlant("A")
        useCase.logSitterVisitWaterings(listOf(a), today.minusDays(1))

        val second = useCase.logSitterVisitWaterings(listOf(a), today.minusDays(1))

        assertEquals(0, second.loggedCount)
        assertEquals(1, waterLogs(a.id).size)
    }

    @Test
    fun `a visit today is dated now, never in the future`() = runTest {
        now = today.atTime(9, 30).atZone(zone).toInstant().toEpochMilli()
        val a = addPlant("A")

        useCase.logSitterVisitWaterings(listOf(a), today)

        assertEquals(now, waterLogs(a.id).single().loggedAt)
    }

    @Test
    fun `the log carries no feedback and a gap touching the trip is excluded from learning`() = runTest {
        val a = addPlant("A")
        val tripStart = today.minusDays(6)
        awayRepo.addPeriod(AwayPeriod(firstDay = tripStart, lastDay = today.plusDays(10)))
        careLogRepo.addLog(
            CareLog(plantId = a.id, careType = CareType.WATER, loggedAt = millis(tripStart.minusDays(3)))
        )
        val before = plantRepo.getPlantById(a.id).first()!!

        useCase.logSitterVisitWaterings(listOf(a), today.minusDays(2))

        assertNull(waterLogs(a.id).last { it.loggedAt == millis(today.minusDays(2)) }.wateringFeedback)
        val excluded = adjustments.getByTrigger(a.id, WateringAdjustmentTrigger.AWAY_EXCLUDED)
        assertEquals(1, excluded.size)
        assertEquals(excluded.single().beforeIntervalDays, excluded.single().afterIntervalDays)
        val after = plantRepo.getPlantById(a.id).first()!!
        assertEquals(before.wateringIntervalDays, after.wateringIntervalDays)
        assertEquals(before.wateringConfidence, after.wateringConfidence)
    }
}
