package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayTaskBucket
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [QuickLogUseCase.bulkLog] against a real in-memory Room database rather than mocking
 * the `withTransaction` extension — the transaction path can only be verified end-to-end (#448).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLogUseCaseBulkLogTest {

    private lateinit var db: PlantDatabase
    private lateinit var plantRepo: PlantRepository
    private lateinit var careLogRepo: CareLogRepository
    private lateinit var customReminderRepo: CustomReminderRepository
    private lateinit var useCase: QuickLogUseCase
    private val scheduledWaterings = mutableListOf<Long>()

    @Before
    fun setUp() {
        scheduledWaterings.clear()
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PlantDatabase::class.java
        ).allowMainThreadQueries().build()
        plantRepo = PlantRepository(db.plantDao())
        careLogRepo = CareLogRepository(db.careLogDao())
        customReminderRepo = CustomReminderRepository(db.customReminderDao())
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
            onWaterLogged = { scheduledWaterings.add(it) },
            customReminderRepository = customReminderRepo
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `bulkLog water writes one WATER log for every plant`() = runTest {
        val id1 = plantRepo.addPlant(Plant(name = "A", createdAt = 0L, updatedAt = 0L))
        val id2 = plantRepo.addPlant(Plant(name = "B", createdAt = 0L, updatedAt = 0L))

        useCase.bulkLog(
            listOf(
                Plant(id = id1, name = "A", createdAt = 0L, updatedAt = 0L),
                Plant(id = id2, name = "B", createdAt = 0L, updatedAt = 0L)
            ),
            CareType.WATER
        )

        val logs = db.careLogDao().getAllLogs().first()
        assertEquals(2, logs.size)
        assertEquals(setOf(id1, id2), logs.map { it.plantId }.toSet())
        assertTrue(logs.all { it.careType == CareType.WATER.name })
        assertEquals(1, scheduledWaterings.size)
    }

    @Test
    fun `bulkLog liquid-fertilizer writes paired FERTILIZE and WATER logs for every plant`() = runTest {
        val id1 = plantRepo.addPlant(Plant(name = "A", useLiquidFertilizer = true, createdAt = 0L, updatedAt = 0L))
        val id2 = plantRepo.addPlant(Plant(name = "B", useLiquidFertilizer = true, createdAt = 0L, updatedAt = 0L))

        useCase.bulkLog(
            listOf(
                Plant(id = id1, name = "A", useLiquidFertilizer = true, createdAt = 0L, updatedAt = 0L),
                Plant(id = id2, name = "B", useLiquidFertilizer = true, createdAt = 0L, updatedAt = 0L)
            ),
            CareType.FERTILIZE
        )

        val logs = db.careLogDao().getAllLogs().first()
        // Each liquid-fertilizer plant gets a paired FERTILIZE + WATER entry.
        assertEquals(4, logs.size)
        assertEquals(2, logs.count { it.careType == CareType.FERTILIZE.name })
        assertEquals(2, logs.count { it.careType == CareType.WATER.name })
        assertEquals(1, scheduledWaterings.size)
    }

    @Test
    fun `bulkLog water skips a plant already watered today and logs the rest`() = runTest {
        val id1 = plantRepo.addPlant(Plant(name = "A", createdAt = 0L, updatedAt = 0L))
        val id2 = plantRepo.addPlant(Plant(name = "B", createdAt = 0L, updatedAt = 0L))
        careLogRepo.addLog(CareLog(plantId = id1, careType = CareType.WATER, loggedAt = System.currentTimeMillis()))

        val result = useCase.bulkLog(
            listOf(
                Plant(id = id1, name = "A", createdAt = 0L, updatedAt = 0L),
                Plant(id = id2, name = "B", createdAt = 0L, updatedAt = 0L)
            ),
            CareType.WATER
        )

        assertEquals(1, result.loggedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(2, result.totalCount)
        val logsForId1 = db.careLogDao().getLogsForPlant(id1).first()
        assertEquals(1, logsForId1.size) // no second WATER log was inserted
        val logsForId2 = db.careLogDao().getLogsForPlant(id2).first()
        assertEquals(1, logsForId2.size)
    }

    @Test
    fun `completeTodayTasks atomically completes mixed care and exact custom reminder`() = runTest {
        val water = addPlant("Water")
        val fertilize = addPlant("Fertilize")
        val combined = addPlant("Combined", liquid = true)
        val repot = addPlant("Repot")
        val custom = addPlant("Custom")
        val reminderId = customReminderRepo.addReminder(
            CustomReminder(plantId = custom.id, name = "Neem", intervalDays = 7, createdAt = 1L)
        )
        val reminder = customReminderRepo.getReminderById(reminderId)!!
        val customTask = task("custom", custom, TodayCareKind.CUSTOM_REMINDER, reminder)

        val result = useCase.completeTodayTasks(
            listOf(
                task("water", water, TodayCareKind.WATER),
                task("fertilize", fertilize, TodayCareKind.FERTILIZE),
                task("combined", combined, TodayCareKind.WATER_AND_FERTILIZE),
                task("repot", repot, TodayCareKind.REPOT),
                customTask
            )
        )

        assertEquals(5, result.completedCount)
        assertEquals(0, result.skippedCount)
        val logs = db.careLogDao().getAllLogs().first()
        assertEquals(6, logs.size)
        assertEquals(reminderId, logs.single { it.careType == CareType.CUSTOM.name }.customReminderId)
        assertTrue(customReminderRepo.getReminderById(reminderId)!!.lastDoneAt != null)
        assertEquals(1, scheduledWaterings.size)

        val staleResult = useCase.completeTodayTasks(listOf(customTask))

        assertEquals(0, staleResult.completedCount)
        assertEquals(1, staleResult.skippedCount)
        assertEquals(6, db.careLogDao().getAllLogs().first().size)
    }

    @Test
    fun `completeTodayTasks skips guarded duplicate without aborting valid work`() = runTest {
        val water = addPlant("Water")
        val repot = addPlant("Repot")
        careLogRepo.addLog(
            CareLog(plantId = water.id, careType = CareType.WATER, loggedAt = System.currentTimeMillis())
        )

        val result = useCase.completeTodayTasks(
            listOf(
                task("water", water, TodayCareKind.WATER),
                task("repot", repot, TodayCareKind.REPOT)
            )
        )

        assertEquals(1, result.completedCount)
        assertEquals(1, result.skippedCount)
        assertEquals(1, db.careLogDao().getLogsForPlant(water.id).first().size)
        assertEquals(CareType.REPOT.name, db.careLogDao().getLogsForPlant(repot.id).first().single().careType)
    }

    @Test
    fun `completeTodayTasks rolls back every write when one task fails`() = runTest {
        val repot = addPlant("Repot")
        val custom = addPlant("Custom")
        val reminder = CustomReminder(id = 44L, plantId = custom.id, name = "Neem", intervalDays = 7)
        val failingRepository = mockk<CustomReminderRepository>()
        coEvery { failingRepository.getReminderById(reminder.id) } returns reminder
        coEvery { failingRepository.updateReminder(any()) } throws IllegalStateException("write failed")
        val failingUseCase = QuickLogUseCase(
            mockk(relaxed = true),
            plantRepo,
            careLogRepo,
            PlantPhotoRepository(db.plantPhotoDao()),
            mockk { every { data } returns flowOf(emptyPreferences()) },
            db,
            WateringAdjustmentRepository(db.wateringAdjustmentDao()),
            customReminderRepository = failingRepository
        )

        val failure = runCatching {
            failingUseCase.completeTodayTasks(
                listOf(
                    task("repot", repot, TodayCareKind.REPOT),
                    task("custom", custom, TodayCareKind.CUSTOM_REMINDER, reminder)
                )
            )
        }

        assertTrue(failure.isFailure)
        assertTrue(db.careLogDao().getAllLogs().first().isEmpty())
    }

    private suspend fun addPlant(name: String, liquid: Boolean = false): Plant {
        val id = plantRepo.addPlant(
            Plant(name = name, useLiquidFertilizer = liquid, createdAt = 0L, updatedAt = 0L)
        )
        return Plant(id = id, name = name, useLiquidFertilizer = liquid, createdAt = 0L, updatedAt = 0L)
    }

    private fun task(
        id: String,
        plant: Plant,
        kind: TodayCareKind,
        reminder: CustomReminder? = null
    ) = TodayCareTask(
        id = id,
        plant = plant,
        kind = kind,
        dueAt = 0L,
        bucket = TodayTaskBucket.Today,
        customReminder = reminder
    )
}
