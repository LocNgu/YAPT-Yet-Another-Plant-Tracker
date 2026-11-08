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
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.model.WateringReason
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * #872: none of `QuickLogUseCase`'s plant-row writes may undo a concurrent write to a column it doesn't
 * change. Against a real in-memory Room database, each test has another writer (standing in for Plant
 * Detail, technical ADR-0036) change `notes` and `fertilizingIntervalDays` back to back with a quick-log
 * write, either before the call while the caller still holds the old snapshot, or in the middle of it,
 * between the use case's own read and its write (via the DataStore read that sits in that window).
 * Every test failed on the full-row `updatePlant()` writes this issue replaced.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class QuickLogUseCaseStaleSnapshotTest {

    private lateinit var db: PlantDatabase
    private lateinit var plantRepo: PlantRepository
    private lateinit var careLogRepo: CareLogRepository
    private lateinit var useCase: QuickLogUseCase

    /** Runs on every DataStore read while set: the "concurrent" writer's hook into the use case's window. */
    private var onDataStoreRead: (suspend () -> Unit)? = null

    private val now = System.currentTimeMillis()
    private fun daysAgo(days: Long) = now - TimeUnit.DAYS.toMillis(days)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PlantDatabase::class.java
        ).allowMainThreadQueries().build()
        plantRepo = PlantRepository(db.plantDao())
        careLogRepo = CareLogRepository(db.careLogDao())
        val application: Application = mockk(relaxed = true)
        val dataStore: DataStore<Preferences> = mockk {
            every { data } returns flow {
                onDataStoreRead?.invoke()
                emit(emptyPreferences())
            }
        }
        useCase = QuickLogUseCase(
            application,
            plantRepo,
            careLogRepo,
            PlantPhotoRepository(db.plantPhotoDao()),
            dataStore,
            db,
            WateringAdjustmentRepository(db.wateringAdjustmentDao()),
            nowProvider = { now }
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun addPlant(
        confidence: Int? = 2,
        useLiquidFertilizer: Boolean = false,
        wateringDueDateOverride: Long? = null
    ): Plant {
        val id = plantRepo.addPlant(
            Plant(
                name = "Monstera",
                createdAt = 0L,
                updatedAt = 0L,
                notes = "original",
                wateringIntervalDays = 7,
                fertilizingIntervalDays = 14,
                wateringConfidence = confidence,
                useLiquidFertilizer = useLiquidFertilizer,
                wateringDueDateOverride = wateringDueDateOverride
            )
        )
        return reload(id)
    }

    private suspend fun reload(id: Long): Plant = plantRepo.getPlantById(id).first()!!

    private suspend fun addWatering(plantId: Long, loggedAt: Long) {
        careLogRepo.addLog(
            CareLog(
                plantId = plantId,
                careType = CareType.WATER,
                loggedAt = loggedAt,
                wateringFeedback = WateringFeedback.JUST_RIGHT
            )
        )
    }

    /** The other writer: Plant Detail's own fresh-read, full-row edit of two non-watering columns. */
    private suspend fun concurrentPlantDetailEdit(id: Long) {
        plantRepo.updatePlant(reload(id).copy(notes = "edited elsewhere", fertilizingIntervalDays = 30))
    }

    private suspend fun assertConcurrentEditSurvived(id: Long) {
        val after = reload(id)
        assertEquals("edited elsewhere", after.notes)
        assertEquals(30, after.fertilizingIntervalDays)
    }

    // ---- quickWaterWithReason() ----

    @Test
    fun `quick water does not revert a write that lands between its read and its adaptive write`() = runTest {
        val plant = addPlant(confidence = 2, wateringDueDateOverride = daysAgo(-2))
        addWatering(plant.id, daysAgo(7))
        onDataStoreRead = { concurrentPlantDetailEdit(plant.id) }

        useCase.quickWaterWithReason(plant, WateringReason.PLANT_NEEDED_IT)

        assertConcurrentEditSurvived(plant.id)
        val after = reload(plant.id)
        assertNull("the override clear still applies", after.wateringDueDateOverride)
        assertNotEquals("the adaptive write still applies", 2, after.wateringConfidence)
    }

    @Test
    fun `backdated quick water does not revert a write made after the caller's snapshot`() = runTest {
        val snapshot = addPlant(confidence = 2)
        addWatering(snapshot.id, daysAgo(20))
        addWatering(snapshot.id, daysAgo(1))
        concurrentPlantDetailEdit(snapshot.id)

        useCase.quickWaterWithReason(snapshot, WateringReason.PLANT_NEEDED_IT, loggedAt = daysAgo(13))

        assertConcurrentEditSurvived(snapshot.id)
        assertNotEquals("the adaptive write still applies", 2, reload(snapshot.id).wateringConfidence)
    }

    @Test
    fun `backdated quick water bootstrap does not revert a write made after the caller's snapshot`() = runTest {
        val snapshot = addPlant(confidence = null)
        // 5 waterings a week apart (4 gaps) clear the bootstrap threshold; the newest is after loggedAt.
        listOf(36L, 29L, 22L, 15L, 1L).forEach { addWatering(snapshot.id, daysAgo(it)) }
        concurrentPlantDetailEdit(snapshot.id)

        useCase.quickWaterWithReason(snapshot, reason = null, loggedAt = daysAgo(8))

        assertConcurrentEditSurvived(snapshot.id)
        assertNotEquals("the bootstrap still applies", null, reload(snapshot.id).wateringConfidence)
    }

    // ---- quickLiquidFertilizeWithReason() ----

    @Test
    fun `liquid fertilize does not revert a write that lands between its read and its adaptive write`() = runTest {
        val plant = addPlant(confidence = 2, useLiquidFertilizer = true, wateringDueDateOverride = daysAgo(-2))
        addWatering(plant.id, daysAgo(7))
        onDataStoreRead = { concurrentPlantDetailEdit(plant.id) }

        useCase.quickLiquidFertilizeWithReason(plant, WateringReason.PLANT_NEEDED_IT)

        assertConcurrentEditSurvived(plant.id)
        val after = reload(plant.id)
        assertNull(after.wateringDueDateOverride)
        assertNotEquals(2, after.wateringConfidence)
    }

    @Test
    fun `backdated liquid fertilize does not revert a write made after the caller's snapshot`() = runTest {
        val snapshot = addPlant(confidence = 2, useLiquidFertilizer = true)
        addWatering(snapshot.id, daysAgo(20))
        addWatering(snapshot.id, daysAgo(1))
        concurrentPlantDetailEdit(snapshot.id)

        useCase.quickLiquidFertilizeWithReason(snapshot, WateringReason.PLANT_NEEDED_IT, loggedAt = daysAgo(13))

        assertConcurrentEditSurvived(snapshot.id)
        assertNotEquals(2, reload(snapshot.id).wateringConfidence)
    }

    // ---- applyWateringIntervalSuggestion() / recordWateringSuggestionDismissal() ----

    @Test
    fun `apply from a stale snapshot does not revert a write made since`() = runTest {
        val snapshot = addPlant(confidence = 2)
        concurrentPlantDetailEdit(snapshot.id)

        useCase.applyWateringIntervalSuggestion(snapshot, 9, newInterval = 9, suggestedBaseInterval = 9.0)

        assertConcurrentEditSurvived(snapshot.id)
        assertEquals(9, reload(snapshot.id).wateringIntervalDays)
    }

    /** Calendar's and Plant List's apply: a fresh read in the ViewModel, then the use case, then a race. */
    @Test
    fun `Calendar and Plant List apply does not revert a write that lands inside the use case`() = runTest {
        val plant = addPlant(confidence = 2)
        val freshInViewModel = reload(plant.id)
        // One-shot: the amplitude read at the top of the apply is the window; a later read must not
        // re-apply the edit after the write and hide a revert.
        onDataStoreRead = {
            onDataStoreRead = null
            concurrentPlantDetailEdit(plant.id)
        }

        useCase.applyWateringIntervalSuggestion(freshInViewModel, 9, newInterval = 9, suggestedBaseInterval = 9.0)

        assertConcurrentEditSurvived(plant.id)
        assertEquals(9, reload(plant.id).wateringIntervalDays)
    }

    @Test
    fun `dismissal from a stale snapshot does not revert a write made since`() = runTest {
        val snapshot = addPlant(confidence = 2)
        concurrentPlantDetailEdit(snapshot.id)

        useCase.recordWateringSuggestionDismissal(snapshot)

        assertConcurrentEditSurvived(snapshot.id)
        assertNotEquals("the confidence bump still applies", 2, reload(snapshot.id).wateringConfidence)
    }

    @Test
    fun `dismissal does not put back an override cleared since the snapshot`() = runTest {
        val snapshot = addPlant(confidence = 2, wateringDueDateOverride = daysAgo(-2))
        plantRepo.updateWateringDueDateOverride(snapshot.id, null, null, now)

        useCase.recordWateringSuggestionDismissal(snapshot)

        assertNull(reload(snapshot.id).wateringDueDateOverride)
    }
}
