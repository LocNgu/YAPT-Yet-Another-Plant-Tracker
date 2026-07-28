package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import com.yapt.planttracker.R
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.reminder.PhotoReminderPolicy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class QuickLogUseCaseTest {

    private val application: Application = mockk {
        every { getString(R.string.quick_log_fertilized, any()) } answers { "Fertilized ${(args[1] as Array<*>)[0]}" }
        every {
            getString(R.string.quick_log_watered_and_fertilized, any())
        } answers { "Watered and fertilized ${(args[1] as Array<*>)[0]}" }
        every {
            getString(R.string.quick_log_other, any(), any())
        } answers { "${(args[1] as Array<*>)[0]} ${(args[1] as Array<*>)[1]}" }
        every { getString(R.string.care_type_pruned) } returns "Pruned"
    }
    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val dataStore: DataStore<Preferences> = mockk {
        every { data } returns flowOf(emptyPreferences())
    }

    // Unused by these single-log tests (only bulkLog opens a transaction); bulkLog's atomic
    // behaviour is covered by QuickLogUseCaseBulkLogTest against a real in-memory database.
    private val database: PlantDatabase = mockk()
    private lateinit var useCase: QuickLogUseCase

    private fun plant(
        id: Long = 1L,
        name: String = "Monstera",
        useLiquidFertilizer: Boolean = false,
        wateringIntervalDays: Int? = null,
        wateringDueDateOverride: Long? = null,
        createdAt: Long = 0L
    ) = Plant(
        id = id,
        name = name,
        useLiquidFertilizer = useLiquidFertilizer,
        wateringIntervalDays = wateringIntervalDays,
        wateringDueDateOverride = wateringDueDateOverride,
        createdAt = createdAt,
        updatedAt = createdAt
    )

    @Before
    fun setup() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        PhotoReminderPolicy.shownThisSession.clear()
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(any()) } returns emptyList()
        coEvery { plantRepo.updatePlant(any()) } returns Unit
        useCase = QuickLogUseCase(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, database)
    }

    @After
    fun tearDown() {
        PhotoReminderPolicy.shownThisSession.clear()
    }

    // quickLog

    @Test
    fun `quickLog regular fertilize logs a single FERTILIZE entry and returns fertilized message`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        val message = useCase.quickLog(monstera, CareType.FERTILIZE)

        assertEquals("Fertilized Monstera", message)
        coVerify(exactly = 1) { careLogRepo.addLog(any()) }
        coVerify {
            careLogRepo.addLog(match {
                it.careType == CareType.FERTILIZE && it.wateringFeedback == null && it.fertilizerType == FertilizerType.UNSPECIFIED
            })
        }
    }

    @Test
    fun `quickLog liquid-fertilizer plant logs paired FERTILIZE and WATER entries`() = runTest {
        val monstera = plant(useLiquidFertilizer = true)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        val message = useCase.quickLog(monstera, CareType.FERTILIZE)

        assertEquals("Watered and fertilized Monstera", message)
        coVerify(exactly = 2) { careLogRepo.addLog(any()) }
        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.FERTILIZE && it.fertilizerType == FertilizerType.LIQUID }
            )
        }
        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.WATER && it.wateringFeedback == WateringFeedback.JUST_RIGHT }
            )
        }
    }

    @Test
    fun `quickLog liquid-fertilizer plant clears wateringDueDateOverride when active`() = runTest {
        val override = System.currentTimeMillis() + 86_400_000L
        val monstera = plant(useLiquidFertilizer = true, wateringDueDateOverride = override)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickLog(monstera, CareType.FERTILIZE)

        coVerify { plantRepo.updatePlant(match { it.wateringDueDateOverride == null }) }
    }

    @Test
    fun `quickLog liquid-fertilizer plant does not call updatePlant when no override active`() = runTest {
        val monstera = plant(useLiquidFertilizer = true)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickLog(monstera, CareType.FERTILIZE)

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `quickLog other care type uses the labelRes-based message format`() = runTest {
        val monstera = plant()

        val message = useCase.quickLog(monstera, CareType.PRUNE)

        assertEquals("Pruned Monstera", message)
        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.PRUNE && it.wateringFeedback == null })
        }
    }

    // quickWaterWithFeedback

    @Test
    fun `quickWaterWithFeedback logs a WATER entry with the given feedback`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickWaterWithFeedback(monstera, WateringFeedback.JUST_RIGHT)

        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.WATER && it.wateringFeedback == WateringFeedback.JUST_RIGHT }
            )
        }
    }

    @Test
    fun `quickWaterWithFeedback clears wateringDueDateOverride when active`() = runTest {
        val override = System.currentTimeMillis() + 86_400_000L
        val monstera = plant(wateringDueDateOverride = override)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickWaterWithFeedback(monstera, WateringFeedback.JUST_RIGHT)

        coVerify { plantRepo.updatePlant(match { it.wateringDueDateOverride == null }) }
    }

    @Test
    fun `quickWaterWithFeedback does not call updatePlant when no override active`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickWaterWithFeedback(monstera, WateringFeedback.JUST_RIGHT)

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `quickWaterWithFeedback TOO_LATE with different interval returns a suggestion`() = runTest {
        val now = System.currentTimeMillis()
        val fiveDaysAgo = now - TimeUnit.DAYS.toMillis(5)
        val tenDaysAgo = now - TimeUnit.DAYS.toMillis(10)
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fiveDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT),
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = tenDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT)
        )

        val suggestion = useCase.quickWaterWithFeedback(monstera, WateringFeedback.TOO_LATE)

        assertNotNull(suggestion)
        assertEquals(1L, suggestion!!.plantId)
        assertEquals(4, suggestion.suggestedInterval)
    }

    @Test
    fun `quickWaterWithFeedback JUST_RIGHT with same actual-as-stored interval returns null`() = runTest {
        val now = System.currentTimeMillis()
        val sevenDaysAgo = now - TimeUnit.DAYS.toMillis(7)
        val fourteenDaysAgo = now - TimeUnit.DAYS.toMillis(14)
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = sevenDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT),
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fourteenDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT)
        )

        val suggestion = useCase.quickWaterWithFeedback(monstera, WateringFeedback.JUST_RIGHT)

        assertNull(suggestion)
    }

    @Test
    fun `quickWaterWithFeedback with fewer than 2 prior waterings returns null`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        val suggestion = useCase.quickWaterWithFeedback(monstera, WateringFeedback.TOO_LATE)

        assertNull(suggestion)
    }

    // quickLiquidFertilizeWithFeedback

    @Test
    fun `quickLiquidFertilizeWithFeedback logs paired FERTILIZE and WATER entries`() = runTest {
        val monstera = plant(useLiquidFertilizer = true, wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickLiquidFertilizeWithFeedback(monstera, WateringFeedback.JUST_RIGHT)

        coVerify(exactly = 2) { careLogRepo.addLog(any()) }
        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.FERTILIZE && it.fertilizerType == FertilizerType.LIQUID }
            )
        }
        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.WATER && it.wateringFeedback == WateringFeedback.JUST_RIGHT }
            )
        }
    }

    @Test
    fun `quickLiquidFertilizeWithFeedback clears wateringDueDateOverride when active`() = runTest {
        val override = System.currentTimeMillis() + 86_400_000L
        val monstera = plant(useLiquidFertilizer = true, wateringDueDateOverride = override)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickLiquidFertilizeWithFeedback(monstera, WateringFeedback.JUST_RIGHT)

        coVerify { plantRepo.updatePlant(match { it.wateringDueDateOverride == null }) }
    }

    @Test
    fun `quickLiquidFertilizeWithFeedback TOO_SOON with lower actual interval returns extended suggestion`() = runTest {
        val now = System.currentTimeMillis()
        val fiveDaysAgo = now - TimeUnit.DAYS.toMillis(5)
        val tenDaysAgo = now - TimeUnit.DAYS.toMillis(10)
        val monstera = plant(useLiquidFertilizer = true, wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fiveDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT),
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = tenDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT)
        )

        val suggestion = useCase.quickLiquidFertilizeWithFeedback(monstera, WateringFeedback.TOO_SOON)

        assertNotNull(suggestion)
        assertEquals(1L, suggestion!!.plantId)
        // actual=5 < current=7 -> TOO_SOON base=current=7, suggestion=7+1=8
        assertEquals(8, suggestion.suggestedInterval)
    }

    // maybeBuildPhotoReminderRequest

    @Test
    fun `maybeBuildPhotoReminderRequest returns null when the feature is disabled`() = runTest {
        val monstera = plant(createdAt = 0L)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantPhotoRepo.getPhotosForPlantOnce(any()) } returns emptyList()
        every { careLogRepo.getPhotoLogsForPlant(any()) } returns flowOf(emptyList())

        val request = useCase.maybeBuildPhotoReminderRequest(1L)

        assertNull(request)
    }

    @Test
    fun `maybeBuildPhotoReminderRequest returns null when plant already reminded this session`() = runTest {
        PhotoReminderPolicy.shownThisSession.add(1L)
        val monstera = plant(createdAt = 0L)
        val enabledDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(preferencesOf(SettingsKeys.PHOTO_REMINDER_ENABLED to true))
        }
        useCase = QuickLogUseCase(application, plantRepo, careLogRepo, plantPhotoRepo, enabledDataStore, database)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantPhotoRepo.getPhotosForPlantOnce(any()) } returns emptyList()
        every { careLogRepo.getPhotoLogsForPlant(any()) } returns flowOf(emptyList())

        val request = useCase.maybeBuildPhotoReminderRequest(1L)

        assertNull(request)
    }

    @Test
    fun `maybeBuildPhotoReminderRequest returns null when the plant no longer exists`() = runTest {
        val enabledDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(preferencesOf(SettingsKeys.PHOTO_REMINDER_ENABLED to true))
        }
        useCase = QuickLogUseCase(application, plantRepo, careLogRepo, plantPhotoRepo, enabledDataStore, database)
        every { plantRepo.getPlantById(1L) } returns flowOf(null)

        val request = useCase.maybeBuildPhotoReminderRequest(1L)

        assertNull(request)
    }

    @Test
    fun `maybeBuildPhotoReminderRequest returns a request and marks the plant shown when no recent photo`() = runTest {
        val monstera = plant(createdAt = 0L) // far past createdAt, no photos at all
        val enabledDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(preferencesOf(SettingsKeys.PHOTO_REMINDER_ENABLED to true))
        }
        useCase = QuickLogUseCase(application, plantRepo, careLogRepo, plantPhotoRepo, enabledDataStore, database)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantPhotoRepo.getPhotosForPlantOnce(any()) } returns emptyList()
        every { careLogRepo.getPhotoLogsForPlant(any()) } returns flowOf(emptyList())

        val request = useCase.maybeBuildPhotoReminderRequest(1L)

        assertNotNull(request)
        assertEquals(1L, request!!.plantId)
        assertEquals(true, 1L in PhotoReminderPolicy.shownThisSession)
    }

    @Test
    fun `maybeBuildPhotoReminderRequest returns null when a recent photo exists`() = runTest {
        val monstera = plant(createdAt = 0L)
        val enabledDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(preferencesOf(SettingsKeys.PHOTO_REMINDER_ENABLED to true))
        }
        useCase = QuickLogUseCase(application, plantRepo, careLogRepo, plantPhotoRepo, enabledDataStore, database)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantPhotoRepo.getPhotosForPlantOnce(any()) } returns listOf(
            PlantPhoto(plantId = 1L, uri = "content://recent", capturedAt = System.currentTimeMillis())
        )
        every { careLogRepo.getPhotoLogsForPlant(any()) } returns flowOf(emptyList())

        val request = useCase.maybeBuildPhotoReminderRequest(1L)

        assertNull(request)
    }
}
