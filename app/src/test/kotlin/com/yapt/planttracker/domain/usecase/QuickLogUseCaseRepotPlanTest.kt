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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * A newly inserted REPOT log clears a planned repot when its local calendar day is on or after the
 * plan-made day, never when it is backdated to before that day (#809, product ADR-0057) — against a real
 * in-memory Room database, since the plan-clear write is a column-specific DAO statement that runs after
 * the lifecycle reset's full-row write and must not be reverted by it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLogUseCaseRepotPlanTest {

    private val originalTimeZone: TimeZone = TimeZone.getDefault()
    private lateinit var db: PlantDatabase
    private lateinit var plantRepo: PlantRepository
    private lateinit var careLogRepo: CareLogRepository
    private lateinit var useCase: QuickLogUseCase

    private val planStart = utcMillis(LocalDate.of(2027, 3, 1), 0)
    private val planMadeAt = utcMillis(LocalDate.of(2026, 9, 29), 15)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PlantDatabase::class.java
        ).allowMainThreadQueries().build()
        plantRepo = PlantRepository(db.plantDao())
        careLogRepo = CareLogRepository(db.careLogDao())
        val application: Application = mockk(relaxed = true)
        val dataStore: DataStore<Preferences> = mockk { every { data } returns flowOf(emptyPreferences()) }
        useCase = QuickLogUseCase(
            application,
            plantRepo,
            careLogRepo,
            PlantPhotoRepository(db.plantPhotoDao()),
            dataStore,
            db,
            WateringAdjustmentRepository(db.wateringAdjustmentDao()),
            nowProvider = { utcMillis(LocalDate.of(2026, 10, 1), 9) }
        )
    }

    @After
    fun tearDown() {
        db.close()
        TimeZone.setDefault(originalTimeZone)
    }

    private fun utcMillis(date: LocalDate, hour: Int): Long =
        date.atTime(hour, 0).atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private suspend fun plannedPlant(
        name: String = "Fern",
        madeAt: Long = planMadeAt
    ): Plant {
        val id = plantRepo.addPlant(
            Plant(
                name = name,
                createdAt = 0L,
                updatedAt = 0L,
                wateringIntervalDays = 7,
                wateringConfidence = 3,
                repottingIntervalDays = 360,
                repottingSeasons = setOf(FertilizingSeason.SPRING),
                repotPlanSeasonStartAt = planStart,
                repotPlanMadeAt = madeAt
            )
        )
        return plantRepo.getPlantById(id).first()!!
    }

    private suspend fun reload(plant: Plant): Plant = plantRepo.getPlantById(plant.id).first()!!

    @Test
    fun `a REPOT logged after the plan-made day clears the plan`() = runTest {
        val plant = plannedPlant()

        useCase.quickLog(plant, CareType.REPOT, utcMillis(LocalDate.of(2026, 10, 1), 9))

        val after = reload(plant)
        assertNull(after.repotPlanSeasonStartAt)
        assertNull(after.repotPlanMadeAt)
    }

    @Test
    fun `a REPOT logged on the plan-made day, even earlier that day, clears the plan`() = runTest {
        val plant = plannedPlant()

        useCase.quickLog(plant, CareType.REPOT, utcMillis(LocalDate.of(2026, 9, 29), 8))

        assertNull(reload(plant).repotPlanSeasonStartAt)
    }

    @Test
    fun `a REPOT backdated to before the plan-made day keeps the plan`() = runTest {
        val plant = plannedPlant()

        useCase.quickLog(plant, CareType.REPOT, utcMillis(LocalDate.of(2026, 9, 28), 20))

        val after = reload(plant)
        assertEquals(planStart, after.repotPlanSeasonStartAt)
        assertEquals(planMadeAt, after.repotPlanMadeAt)
        assertEquals("the lifecycle reset still applies", 0, after.wateringConfidence)
    }

    @Test
    fun `clearing the plan neither reverts the reset nor touches any other column`() = runTest {
        val plant = plannedPlant()

        useCase.quickLog(plant, CareType.REPOT, utcMillis(LocalDate.of(2026, 10, 1), 9))

        val after = reload(plant)
        assertNull("the reset's full-row write must not resurrect the plan", after.repotPlanSeasonStartAt)
        assertEquals(0, after.wateringConfidence)
        assertEquals(360, after.repottingIntervalDays)
        assertEquals(setOf(FertilizingSeason.SPRING), after.repottingSeasons)
        assertEquals(7, after.wateringIntervalDays)
    }

    @Test
    fun `a REPOT on a plant with no plan changes nothing about plans`() = runTest {
        val id = plantRepo.addPlant(Plant(name = "Cactus", createdAt = 0L, updatedAt = 0L))
        val plant = plantRepo.getPlantById(id).first()!!

        useCase.quickLog(plant, CareType.REPOT, utcMillis(LocalDate.of(2026, 10, 1), 9))

        val after = reload(plant)
        assertNull(after.repotPlanSeasonStartAt)
        assertNull(after.repotPlanMadeAt)
    }

    @Test
    fun `other care types never clear the plan`() = runTest {
        val plant = plannedPlant()

        useCase.quickLog(plant, CareType.PRUNE, utcMillis(LocalDate.of(2026, 10, 1), 9))
        useCase.quickLog(plant, CareType.FERTILIZE, utcMillis(LocalDate.of(2026, 10, 1), 9))

        assertEquals(planStart, reload(plant).repotPlanSeasonStartAt)
    }

    @Test
    fun `bulk REPOT clears each plan the log supersedes and leaves the rest`() = runTest {
        val now = System.currentTimeMillis()
        val madeLastWeek = plannedPlant(name = "A", madeAt = now - TimeUnit.DAYS.toMillis(7))
        val madeNextWeek = plannedPlant(name = "B", madeAt = now + TimeUnit.DAYS.toMillis(7))

        useCase.bulkLog(listOf(madeLastWeek, madeNextWeek), CareType.REPOT)

        assertNull(reload(madeLastWeek).repotPlanSeasonStartAt)
        assertEquals(planStart, reload(madeNextWeek).repotPlanSeasonStartAt)
    }

    @Test
    fun `deleting the REPOT log that cleared a plan does not resurrect it`() = runTest {
        val plant = plannedPlant()
        useCase.quickLog(plant, CareType.REPOT, utcMillis(LocalDate.of(2026, 10, 1), 9))
        val repotLog = careLogRepo.getLastLogOfType(plant.id, CareType.REPOT)!!

        careLogRepo.deleteLog(repotLog)

        assertNull(reload(plant).repotPlanSeasonStartAt)
    }

    @Test
    fun `editing or deleting an older REPOT log never clears a later plan`() = runTest {
        val plant = plannedPlant()
        val oldLogId = careLogRepo.addLog(
            CareLog(plantId = plant.id, careType = CareType.REPOT, loggedAt = utcMillis(LocalDate.of(2026, 1, 5), 9))
        )
        val oldLog = careLogRepo.getLogById(oldLogId)!!

        careLogRepo.updateLog(oldLog.copy(loggedAt = utcMillis(LocalDate.of(2026, 11, 5), 9)))
        assertEquals(planStart, reload(plant).repotPlanSeasonStartAt)

        careLogRepo.deleteLog(careLogRepo.getLogById(oldLogId)!!)
        assertEquals(planStart, reload(plant).repotPlanSeasonStartAt)
    }
}
