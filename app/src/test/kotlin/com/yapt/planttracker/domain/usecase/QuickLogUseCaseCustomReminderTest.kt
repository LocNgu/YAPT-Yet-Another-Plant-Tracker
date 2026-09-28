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
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.today.TodayCareKind
import com.yapt.planttracker.domain.today.TodayCareTask
import com.yapt.planttracker.domain.today.TodayTaskBucket
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Exercises [QuickLogUseCase.completeCustomReminder] — the single "Mark done" write behind the Care
 * long-press menu — against a real in-memory Room database, since the log + `lastDoneAt` pair is only
 * meaningful as one committed transaction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLogUseCaseCustomReminderTest {

    private lateinit var db: PlantDatabase
    private lateinit var plantRepo: PlantRepository
    private lateinit var reminderRepo: CustomReminderRepository
    private lateinit var useCase: QuickLogUseCase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PlantDatabase::class.java
        ).allowMainThreadQueries().build()
        plantRepo = PlantRepository(db.plantDao())
        reminderRepo = CustomReminderRepository(db.customReminderDao())
        val application: Application = mockk(relaxed = true)
        val dataStore: DataStore<Preferences> = mockk { every { data } returns flowOf(emptyPreferences()) }
        useCase = QuickLogUseCase(
            application,
            plantRepo,
            CareLogRepository(db.careLogDao()),
            PlantPhotoRepository(db.plantPhotoDao()),
            dataStore,
            db,
            WateringAdjustmentRepository(db.wateringAdjustmentDao()),
            nowProvider = { NOW },
            customReminderRepository = reminderRepo
        )
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `completeCustomReminder logs the exact reminder and stamps lastDoneAt`() = runTest {
        val (task, reminderId) = customTask()

        val outcome = useCase.completeCustomReminder(task)

        assertTrue(outcome.logged)
        val log = db.careLogDao().getAllLogs().first().single()
        assertEquals(CareType.CUSTOM.name, log.careType)
        assertEquals(reminderId, log.customReminderId)
        assertEquals(NOW, log.loggedAt)
        assertEquals(NOW, reminderRepo.getReminderById(reminderId)!!.lastDoneAt)
    }

    @Test
    fun `completeCustomReminder is a no-op for a stale task that was already completed`() = runTest {
        val (task, reminderId) = customTask()
        useCase.completeCustomReminder(task)

        val stale = useCase.completeCustomReminder(task)

        assertFalse(stale.logged)
        assertEquals(1, db.careLogDao().getAllLogs().first().size)
        assertNotNull(reminderRepo.getReminderById(reminderId)!!.lastDoneAt)
    }

    private suspend fun customTask(): Pair<TodayCareTask, Long> {
        val plantId = plantRepo.addPlant(Plant(name = "Fern", createdAt = 0L, updatedAt = 0L))
        val reminderId = reminderRepo.addReminder(
            CustomReminder(plantId = plantId, name = "Neem", intervalDays = 7, createdAt = 1L)
        )
        val task = TodayCareTask(
            id = "custom:$reminderId",
            plant = Plant(id = plantId, name = "Fern", createdAt = 0L, updatedAt = 0L),
            kind = TodayCareKind.CUSTOM_REMINDER,
            dueAt = 0L,
            bucket = TodayTaskBucket.Today,
            customReminder = reminderRepo.getReminderById(reminderId)
        )
        return task to reminderId
    }

    private companion object {
        const val NOW = 1_800_000_000_000L
    }
}
