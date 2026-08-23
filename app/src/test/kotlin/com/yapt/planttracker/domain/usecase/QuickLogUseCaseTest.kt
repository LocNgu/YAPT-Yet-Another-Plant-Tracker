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
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class QuickLogUseCaseTest {

    private val application: Application = mockk {
        every { getString(R.string.quick_log_fertilized, any()) } answers { "Fertilized ${(args[1] as Array<*>)[0]}" }
        every { getString(R.string.quick_log_watered, any()) } answers { "Watered ${(args[1] as Array<*>)[0]}" }
        every {
            getString(R.string.quick_log_watered_and_fertilized, any())
        } answers { "Watered and fertilized ${(args[1] as Array<*>)[0]}" }
        every {
            getString(R.string.quick_log_other, any(), any())
        } answers { "${(args[1] as Array<*>)[0]} ${(args[1] as Array<*>)[1]}" }
        every { getString(R.string.care_type_pruned) } returns "Pruned"
        every {
            getString(R.string.quick_log_already_watered, any())
        } answers { "Already watered ${(args[1] as Array<*>)[0]} today" }
        every {
            getString(R.string.quick_log_already_fertilized, any())
        } answers { "Already fertilized ${(args[1] as Array<*>)[0]} today" }
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
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)
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
        // Default: plant has no log of any type today; individual tests override to true to
        // exercise the duplicate-rejection paths (#509).
        coEvery { careLogRepo.hasLogOfTypeOnDay(any(), any(), any(), any()) } returns false
        useCase = QuickLogUseCase(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, database, wateringAdjustmentRepo)
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

        val outcome = useCase.quickLog(monstera, CareType.FERTILIZE)

        assertTrue(outcome.logged)
        assertEquals("Fertilized Monstera", outcome.message)
        coVerify(exactly = 1) { careLogRepo.addLog(any()) }
        coVerify {
            careLogRepo.addLog(
                match {
                    it.careType == CareType.FERTILIZE && it.wateringFeedback == null && it.fertilizerType == FertilizerType.UNSPECIFIED
                }
            )
        }
    }

    @Test
    fun `quickLog liquid-fertilizer plant logs paired FERTILIZE and WATER entries`() = runTest {
        val monstera = plant(useLiquidFertilizer = true)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        val outcome = useCase.quickLog(monstera, CareType.FERTILIZE)

        assertTrue(outcome.logged)
        assertTrue(outcome.waterPaired)
        assertEquals("Watered and fertilized Monstera", outcome.message)
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

        val outcome = useCase.quickLog(monstera, CareType.PRUNE)

        assertTrue(outcome.logged)
        assertEquals("Pruned Monstera", outcome.message)
        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.PRUNE && it.wateringFeedback == null })
        }
    }

    @Test
    fun `quickLog FERTILIZE already logged today is rejected without inserting`() = runTest {
        val monstera = plant()
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.FERTILIZE, any(), null) } returns true

        val outcome = useCase.quickLog(monstera, CareType.FERTILIZE)

        assertFalse(outcome.logged)
        assertEquals("Already fertilized Monstera today", outcome.message)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `quickLog liquid-fertilizer plant already watered today suppresses paired WATER but still fertilizes`() = runTest {
        val monstera = plant(useLiquidFertilizer = true)
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.FERTILIZE, any(), null) } returns false
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), null) } returns true

        val outcome = useCase.quickLog(monstera, CareType.FERTILIZE)

        assertTrue(outcome.logged)
        assertFalse(outcome.waterPaired)
        assertEquals("Fertilized Monstera", outcome.message)
        coVerify(exactly = 1) { careLogRepo.addLog(any()) }
        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.FERTILIZE && it.fertilizerType == FertilizerType.LIQUID }
            )
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

        val outcome = useCase.quickWaterWithFeedback(monstera, WateringFeedback.TOO_LATE)

        assertNotNull(outcome.suggestion)
        assertEquals(1L, outcome.suggestion!!.plantId)
        assertEquals(4, outcome.suggestion!!.suggestedInterval)
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

        val outcome = useCase.quickWaterWithFeedback(monstera, WateringFeedback.JUST_RIGHT)

        assertNull(outcome.suggestion)
    }

    @Test
    fun `quickWaterWithFeedback with fewer than 2 prior waterings returns null`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        val outcome = useCase.quickWaterWithFeedback(monstera, WateringFeedback.TOO_LATE)

        assertNull(outcome.suggestion)
    }

    @Test
    fun `quickWaterWithFeedback with null feedback logs a WATER entry with null wateringFeedback`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickWaterWithFeedback(monstera, null)

        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.WATER && it.wateringFeedback == null })
        }
    }

    @Test
    fun `quickWaterWithFeedback with null feedback returns no suggestion even when one would otherwise fire`() = runTest {
        val now = System.currentTimeMillis()
        val fiveDaysAgo = now - TimeUnit.DAYS.toMillis(5)
        val tenDaysAgo = now - TimeUnit.DAYS.toMillis(10)
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fiveDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT),
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = tenDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT)
        )

        val outcome = useCase.quickWaterWithFeedback(monstera, null)

        assertNull(outcome.suggestion)
    }

    @Test
    fun `quickWaterWithFeedback already watered today is rejected without inserting`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), null) } returns true

        val outcome = useCase.quickWaterWithFeedback(monstera, WateringFeedback.JUST_RIGHT)

        assertFalse(outcome.logged)
        assertNull(outcome.suggestion)
        assertEquals("Already watered Monstera today", outcome.message)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `quickWaterWithFeedback on a new day after an earlier same-day watering is accepted`() = runTest {
        // hasLogOfTypeOnDay defaults to false in setup(), simulating "no log for the queried day"
        // regardless of what happened on a previous day — exercises the day-boundary reset.
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        val outcome = useCase.quickWaterWithFeedback(monstera, WateringFeedback.JUST_RIGHT)

        assertTrue(outcome.logged)
        coVerify(exactly = 1) { careLogRepo.addLog(any()) }
    }

    // Seasonal de-seasonalization of the observed gap (#569, product ADR-0026, #572 follow-up)
    // now lives in QuickLogUseCaseSeasonalTest, split out to stay under Detekt's LargeClass threshold.

    // quickLiquidFertilizeWithFeedback

    @Test
    fun `quickLiquidFertilizeWithFeedback logs paired FERTILIZE and WATER entries`() = runTest {
        val monstera = plant(useLiquidFertilizer = true, wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        val outcome = useCase.quickLiquidFertilizeWithFeedback(monstera, WateringFeedback.JUST_RIGHT)

        assertTrue(outcome.logged)
        assertTrue(outcome.waterPaired)
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

        val outcome = useCase.quickLiquidFertilizeWithFeedback(monstera, WateringFeedback.TOO_SOON)

        assertNotNull(outcome.suggestion)
        assertEquals(1L, outcome.suggestion!!.plantId)
        // actual=5 < current=7 -> TOO_SOON base=current=7, suggestion=7+1=8
        assertEquals(8, outcome.suggestion!!.suggestedInterval)
    }

    @Test
    fun `quickLiquidFertilizeWithFeedback with null feedback logs a WATER entry with null wateringFeedback and no suggestion`() =
        runTest {
            val monstera = plant(useLiquidFertilizer = true, wateringIntervalDays = 7)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

            val outcome = useCase.quickLiquidFertilizeWithFeedback(monstera, null)

            assertNull(outcome.suggestion)
            coVerify {
                careLogRepo.addLog(match { it.careType == CareType.WATER && it.wateringFeedback == null })
            }
        }

    @Test
    fun `quickLiquidFertilizeWithFeedback already fertilized today is rejected without inserting`() = runTest {
        val monstera = plant(useLiquidFertilizer = true, wateringIntervalDays = 7)
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.FERTILIZE, any(), null) } returns true

        val outcome = useCase.quickLiquidFertilizeWithFeedback(monstera, WateringFeedback.JUST_RIGHT)

        assertFalse(outcome.logged)
        assertEquals("Already fertilized Monstera today", outcome.message)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `quickLiquidFertilizeWithFeedback already watered today still fertilizes but suppresses paired WATER`() = runTest {
        val monstera = plant(useLiquidFertilizer = true, wateringIntervalDays = 7)
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.FERTILIZE, any(), null) } returns false
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), null) } returns true

        val outcome = useCase.quickLiquidFertilizeWithFeedback(monstera, WateringFeedback.JUST_RIGHT)

        assertTrue(outcome.logged)
        assertFalse(outcome.waterPaired)
        assertNull(outcome.suggestion)
        assertEquals("Fertilized Monstera", outcome.message)
        coVerify(exactly = 1) { careLogRepo.addLog(any()) }
        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.FERTILIZE && it.fertilizerType == FertilizerType.LIQUID }
            )
        }
    }

    // recordStillMoistCheck (#570)

    @Test
    fun `recordStillMoistCheck logs a CHECK entry with TOO_SOON feedback`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)

        val logged = useCase.recordStillMoistCheck(monstera)

        assertTrue(logged)
        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.CHECK && it.wateringFeedback == WateringFeedback.TOO_SOON }
            )
        }
    }

    @Test
    fun `recordStillMoistCheck already checked today is rejected without inserting`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.CHECK, any(), null) } returns true

        val logged = useCase.recordStillMoistCheck(monstera)

        assertFalse(logged)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `recordStillMoistCheck advances a fresh due date override by one day`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)

        useCase.recordStillMoistCheck(monstera)

        coVerify {
            plantRepo.updatePlant(
                match { plant ->
                    plant.wateringDueDateOverride != null &&
                        plant.wateringDueDateOverride!! - System.currentTimeMillis() in
                        (TimeUnit.DAYS.toMillis(1) - 5_000)..(TimeUnit.DAYS.toMillis(1) + 5_000)
                }
            )
        }
    }

    @Test
    fun `recordStillMoistCheck advances an existing override by one more day`() = runTest {
        val existingOverride = 1_000_000L
        val monstera = plant(wateringIntervalDays = 7, wateringDueDateOverride = existingOverride)

        useCase.recordStillMoistCheck(monstera)

        coVerify {
            plantRepo.updatePlant(match { it.wateringDueDateOverride == existingOverride + TimeUnit.DAYS.toMillis(1) })
        }
    }

    @Test
    fun `recordStillMoistCheck does not touch wateringConfidence when adaptive_watering is off`() = runTest {
        val monstera = plant(wateringIntervalDays = 7).copy(wateringConfidence = 2)

        useCase.recordStillMoistCheck(monstera)

        coVerify(exactly = 0) {
            plantRepo.updatePlant(match { it.wateringConfidence != 2 })
        }
    }

    @Test
    fun `recordStillMoistCheck feeds computeAdaptiveInterval and updates confidence when adaptive_watering is on`() =
        runTest {
            val adaptiveDataStore: DataStore<Preferences> = mockk {
                every { data } returns flowOf(
                    preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true)
                )
            }
            useCase = QuickLogUseCase(application, plantRepo, careLogRepo, plantPhotoRepo, adaptiveDataStore, database, wateringAdjustmentRepo)
            val fifteenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(15)
            val monstera = plant(wateringIntervalDays = 7).copy(wateringConfidence = null)
            coEvery { careLogRepo.getLastLogOfType(1L, CareType.WATER) } returns
                CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fifteenDaysAgo)
            coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

            useCase.recordStillMoistCheck(monstera)

            // Bootstrap (currentConfidence == null) -> confidence becomes 0, which differs from null,
            // so the confidence-only update fires.
            coVerify { plantRepo.updatePlant(match { it.wateringConfidence == 0 }) }
        }

    @Test
    fun `recordStillMoistCheck does not call computeAdaptiveInterval when the plant has never been watered`() =
        runTest {
            val adaptiveDataStore: DataStore<Preferences> = mockk {
                every { data } returns flowOf(
                    preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true)
                )
            }
            useCase = QuickLogUseCase(application, plantRepo, careLogRepo, plantPhotoRepo, adaptiveDataStore, database, wateringAdjustmentRepo)
            val monstera = plant(wateringIntervalDays = 7)
            coEvery { careLogRepo.getLastLogOfType(1L, CareType.WATER) } returns null

            useCase.recordStillMoistCheck(monstera)

            // Only the due-date-override update should have happened; no confidence write.
            coVerify(exactly = 1) { plantRepo.updatePlant(any()) }
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
        useCase = QuickLogUseCase(application, plantRepo, careLogRepo, plantPhotoRepo, enabledDataStore, database, wateringAdjustmentRepo)
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
        useCase = QuickLogUseCase(application, plantRepo, careLogRepo, plantPhotoRepo, enabledDataStore, database, wateringAdjustmentRepo)
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
        useCase = QuickLogUseCase(application, plantRepo, careLogRepo, plantPhotoRepo, enabledDataStore, database, wateringAdjustmentRepo)
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
        useCase = QuickLogUseCase(application, plantRepo, careLogRepo, plantPhotoRepo, enabledDataStore, database, wateringAdjustmentRepo)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantPhotoRepo.getPhotosForPlantOnce(any()) } returns listOf(
            PlantPhoto(plantId = 1L, uri = "content://recent", capturedAt = System.currentTimeMillis())
        )
        every { careLogRepo.getPhotoLogsForPlant(any()) } returns flowOf(emptyList())

        val request = useCase.maybeBuildPhotoReminderRequest(1L)

        assertNull(request)
    }
}
