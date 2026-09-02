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
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.model.WateringReason
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
import org.junit.Assert.assertNotEquals
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

    /** An arbitrary caller-supplied due date for `recordStillMoistCheck` — #586 made it a parameter. */
    private val newDueAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(3)

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
        // #571: below the 3-gap bootstrap threshold by default, so existing adaptive-model tests keep
        // exercising the plain per-observation path — tests exercising the bootstrap itself override this.
        coEvery { careLogRepo.getWaterLogTimestampsAscending(any()) } returns emptyList()
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
        // ADR-0030: the paired WATER of a liquid fertilizing is a silent writer — the user was
        // never asked why they watered, so nothing is attributed.
        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.WATER && it.wateringFeedback == null }
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

    // #571: quickLog's REPOT path is reached from BulkActionBar's bulk-repot action.

    @Test
    fun `quickLog REPOT resets confidence and starts the freeze window when adaptive_watering is on`() = runTest {
        val adaptiveDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(
                preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true)
            )
        }
        useCase = QuickLogUseCase(
            application, plantRepo, careLogRepo, plantPhotoRepo, adaptiveDataStore, database, wateringAdjustmentRepo
        )
        every { application.getString(R.string.care_type_repotted) } returns "Repotted"
        val monstera = plant(wateringIntervalDays = 7).copy(wateringConfidence = 3)

        useCase.quickLog(monstera, CareType.REPOT)

        coVerify(exactly = 1) {
            plantRepo.updatePlant(
                match { it.wateringConfidence == 0 && it.wateringResetAt != null && it.wateringFreezeUntil != null }
            )
        }
        coVerify { wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.REPOT_RESET }) }
    }

    @Test
    fun `quickLog REPOT does not reset confidence when adaptive_watering is off`() = runTest {
        every { application.getString(R.string.care_type_repotted) } returns "Repotted"
        val monstera = plant(wateringIntervalDays = 7).copy(wateringConfidence = 3)

        useCase.quickLog(monstera, CareType.REPOT)

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
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

    // quickWaterWithReason (#586)

    @Test
    fun `quickWaterWithReason PLANT_NEEDED_IT logs a WATER entry with TOO_LATE feedback`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT)

        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.WATER && it.wateringFeedback == WateringFeedback.TOO_LATE }
            )
        }
    }

    @Test
    fun `quickWaterWithReason JUST_MY_TIMING logs a WATER entry with no feedback at all`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickWaterWithReason(monstera, WateringReason.JUST_MY_TIMING)

        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.WATER && it.wateringFeedback == null })
        }
    }

    // #586 acceptance criterion: TOO_SOON is never written to a WATER log. WateringReason has no
    // value that maps to it, so every reason — and the no-reason case — is checked exhaustively here
    // rather than pinning one example.
    @Test
    fun `no WateringReason ever writes TOO_SOON to a WATER log`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        for (reason in WateringReason.entries + listOf(null)) {
            assertNotEquals(WateringFeedback.TOO_SOON, reason?.toWateringFeedback())
        }

        useCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT)

        coVerify(exactly = 0) {
            careLogRepo.addLog(
                match { it.careType == CareType.WATER && it.wateringFeedback == WateringFeedback.TOO_SOON }
            )
        }
    }

    @Test
    fun `quickWaterWithReason clears wateringDueDateOverride when active`() = runTest {
        val override = System.currentTimeMillis() + 86_400_000L
        val monstera = plant(wateringDueDateOverride = override)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickWaterWithReason(monstera, null)

        coVerify { plantRepo.updatePlant(match { it.wateringDueDateOverride == null }) }
    }

    @Test
    fun `quickWaterWithReason does not call updatePlant when no override active`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickWaterWithReason(monstera, null)

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    // #614 regression coverage (does not revert an active override when adaptive watering changes
    // confidence, for both quickWaterWithReason and quickLiquidFertilizeWithReason) now lives in
    // QuickLogUseCaseOverrideRevertTest, split out to stay under Detekt's LargeClass threshold —
    // mirrors QuickLogUseCaseSeasonalTest's precedent.

    @Test
    fun `quickWaterWithReason PLANT_NEEDED_IT with a different interval returns a suggestion`() = runTest {
        val now = System.currentTimeMillis()
        val fiveDaysAgo = now - TimeUnit.DAYS.toMillis(5)
        val tenDaysAgo = now - TimeUnit.DAYS.toMillis(10)
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fiveDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT),
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = tenDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT)
        )

        val outcome = useCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT)

        assertNotNull(outcome.suggestion)
        assertEquals(1L, outcome.suggestion!!.plantId)
        assertEquals(4, outcome.suggestion!!.suggestedInterval)
    }

    // #571: the first-ever adaptive observation cold-starts from existing watering history when
    // there's enough of it (>= 3 gaps), instead of the plain per-observation nudge.
    @Test
    fun `quickWaterWithReason bootstraps from history on the first adaptive observation when enough gaps exist`() =
        runTest {
            val adaptiveDataStore: DataStore<Preferences> = mockk {
                every { data } returns flowOf(
                    preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true)
                )
            }
            useCase = QuickLogUseCase(
                application, plantRepo, careLogRepo, plantPhotoRepo, adaptiveDataStore, database, wateringAdjustmentRepo
            )
            val now = System.currentTimeMillis()
            val monstera = plant(wateringIntervalDays = 7).copy(wateringConfidence = null)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
                CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = now - TimeUnit.DAYS.toMillis(7)),
                CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = now - TimeUnit.DAYS.toMillis(14))
            )
            // 5 timestamps, 7 days apart -> 4 gaps, clears MIN_BOOTSTRAP_GAPS (3).
            coEvery { careLogRepo.getWaterLogTimestampsAscending(1L) } returns (0..4).map {
                now - TimeUnit.DAYS.toMillis((28 - it * 7).toLong())
            }

            val outcome = useCase.quickWaterWithReason(monstera, null)

            // Bootstrap already silently committed the interval, so no suggestion dialog fires.
            assertNull(outcome.suggestion)
            coVerify(exactly = 1) {
                plantRepo.updatePlant(
                    match {
                        it.wateringConfidence == 1 &&
                            it.wateringIntervalDays == 7 &&
                            it.wateringResetAt == null
                    }
                )
            }
            coVerify {
                wateringAdjustmentRepo.addAdjustment(
                    match { it.trigger == WateringAdjustmentTrigger.HISTORY_BOOTSTRAP }
                )
            }
        }

    @Test
    fun `quickWaterWithReason with no reason and same actual-as-stored interval returns null`() = runTest {
        val now = System.currentTimeMillis()
        val sevenDaysAgo = now - TimeUnit.DAYS.toMillis(7)
        val fourteenDaysAgo = now - TimeUnit.DAYS.toMillis(14)
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = sevenDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT),
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fourteenDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT)
        )

        val outcome = useCase.quickWaterWithReason(monstera, null)

        assertNull(outcome.suggestion)
    }

    @Test
    fun `quickWaterWithReason with fewer than 2 prior waterings returns null`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        val outcome = useCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT)

        assertNull(outcome.suggestion)
    }

    @Test
    fun `quickWaterWithReason with no reason logs a WATER entry with null wateringFeedback`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickWaterWithReason(monstera, null)

        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.WATER && it.wateringFeedback == null })
        }
    }

    @Test
    fun `quickWaterWithReason with no reason returns no suggestion even when one would otherwise fire`() = runTest {
        val now = System.currentTimeMillis()
        val fiveDaysAgo = now - TimeUnit.DAYS.toMillis(5)
        val tenDaysAgo = now - TimeUnit.DAYS.toMillis(10)
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fiveDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT),
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = tenDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT)
        )

        val outcome = useCase.quickWaterWithReason(monstera, null)

        assertNull(outcome.suggestion)
    }

    @Test
    fun `quickWaterWithReason already watered today is rejected without inserting`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), null) } returns true

        val outcome = useCase.quickWaterWithReason(monstera, null)

        assertFalse(outcome.logged)
        assertNull(outcome.suggestion)
        assertEquals("Already watered Monstera today", outcome.message)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `quickWaterWithReason on a new day after an earlier same-day watering is accepted`() = runTest {
        // hasLogOfTypeOnDay defaults to false in setup(), simulating "no log for the queried day"
        // regardless of what happened on a previous day — exercises the day-boundary reset.
        val monstera = plant(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        val outcome = useCase.quickWaterWithReason(monstera, null)

        assertTrue(outcome.logged)
        coVerify(exactly = 1) { careLogRepo.addLog(any()) }
    }

    // Seasonal de-seasonalization of the observed gap (#569, product ADR-0026, #572 follow-up)
    // now lives in QuickLogUseCaseSeasonalTest, split out to stay under Detekt's LargeClass threshold.

    // quickLiquidFertilizeWithReason

    @Test
    fun `quickLiquidFertilizeWithReason logs paired FERTILIZE and WATER entries`() = runTest {
        val monstera = plant(useLiquidFertilizer = true, wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        val outcome = useCase.quickLiquidFertilizeWithReason(monstera, null)

        assertTrue(outcome.logged)
        assertTrue(outcome.waterPaired)
        coVerify(exactly = 2) { careLogRepo.addLog(any()) }
        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.FERTILIZE && it.fertilizerType == FertilizerType.LIQUID }
            )
        }
        // ADR-0030: the paired WATER of a liquid fertilizing is a silent writer — the user was
        // never asked why they watered, so nothing is attributed.
        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.WATER && it.wateringFeedback == null }
            )
        }
    }

    @Test
    fun `quickLiquidFertilizeWithReason clears wateringDueDateOverride when active`() = runTest {
        val override = System.currentTimeMillis() + 86_400_000L
        val monstera = plant(useLiquidFertilizer = true, wateringDueDateOverride = override)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

        useCase.quickLiquidFertilizeWithReason(monstera, null)

        coVerify { plantRepo.updatePlant(match { it.wateringDueDateOverride == null }) }
    }

    @Test
    fun `quickLiquidFertilizeWithReason PLANT_NEEDED_IT with a lower actual interval returns a suggestion`() = runTest {
        val now = System.currentTimeMillis()
        val fiveDaysAgo = now - TimeUnit.DAYS.toMillis(5)
        val tenDaysAgo = now - TimeUnit.DAYS.toMillis(10)
        val monstera = plant(useLiquidFertilizer = true, wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fiveDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT),
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = tenDaysAgo, wateringFeedback = WateringFeedback.JUST_RIGHT)
        )

        val outcome = useCase.quickLiquidFertilizeWithReason(monstera, WateringReason.PLANT_NEEDED_IT)

        assertNotNull(outcome.suggestion)
        assertEquals(1L, outcome.suggestion!!.plantId)
        // PLANT_NEEDED_IT -> TOO_LATE, which clamps to min(actual=5, stored=7) and steps down: 5-1=4.
        assertEquals(4, outcome.suggestion!!.suggestedInterval)
    }

    @Test
    fun `quickLiquidFertilizeWithReason with no reason logs a WATER entry with null wateringFeedback and no suggestion`() =
        runTest {
            val monstera = plant(useLiquidFertilizer = true, wateringIntervalDays = 7)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)

            val outcome = useCase.quickLiquidFertilizeWithReason(monstera, null)

            assertNull(outcome.suggestion)
            coVerify {
                careLogRepo.addLog(match { it.careType == CareType.WATER && it.wateringFeedback == null })
            }
        }

    @Test
    fun `quickLiquidFertilizeWithReason already fertilized today is rejected without inserting`() = runTest {
        val monstera = plant(useLiquidFertilizer = true, wateringIntervalDays = 7)
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.FERTILIZE, any(), null) } returns true

        val outcome = useCase.quickLiquidFertilizeWithReason(monstera, null)

        assertFalse(outcome.logged)
        assertEquals("Already fertilized Monstera today", outcome.message)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `quickLiquidFertilizeWithReason already watered today still fertilizes but suppresses paired WATER`() = runTest {
        val monstera = plant(useLiquidFertilizer = true, wateringIntervalDays = 7)
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.FERTILIZE, any(), null) } returns false
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), null) } returns true

        val outcome = useCase.quickLiquidFertilizeWithReason(monstera, null)

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

    // recordStillMoistCheck (#570; caller-supplied due date since #586)

    @Test
    fun `recordStillMoistCheck logs a CHECK entry with TOO_SOON feedback`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)

        val logged = useCase.recordStillMoistCheck(monstera, newDueAt)

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

        val logged = useCase.recordStillMoistCheck(monstera, newDueAt)

        assertFalse(logged)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    // #586 replaced #570's flat +1 day with a caller-supplied date: the picker's answer in the app,
    // the derived suggestion from the notification. The override is *set*, not advanced, so a plant
    // overdue by several days can actually be cleared in one go.
    @Test
    fun `recordStillMoistCheck writes the caller-supplied due date as the override`() = runTest {
        val monstera = plant(wateringIntervalDays = 7)

        useCase.recordStillMoistCheck(monstera, newDueAt)

        coVerify { plantRepo.updatePlant(match { it.wateringDueDateOverride == newDueAt }) }
    }

    @Test
    fun `recordStillMoistCheck replaces an existing override rather than stacking on top of it`() = runTest {
        val monstera = plant(wateringIntervalDays = 7, wateringDueDateOverride = 1_000_000L)

        useCase.recordStillMoistCheck(monstera, newDueAt)

        coVerify { plantRepo.updatePlant(match { it.wateringDueDateOverride == newDueAt }) }
    }

    // #586 acceptance criterion: reschedule length never affects what the model learns. Two
    // "soil still moist" reschedules of wildly different lengths must produce identical model input.
    @Test
    fun `recordStillMoistCheck deferral length does not change the adaptive observation`() = runTest {
        val adaptiveDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(
                preferencesOf(FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true)
            )
        }
        val tenDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)
        coEvery { careLogRepo.getLastLogOfType(1L, CareType.WATER) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = tenDaysAgo)
        coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

        val captured = mutableListOf<WateringAdjustment>()
        coEvery { wateringAdjustmentRepo.addAdjustment(capture(captured)) } returns 1L

        for (deferralMs in listOf(TimeUnit.DAYS.toMillis(1), TimeUnit.DAYS.toMillis(30))) {
            useCase = QuickLogUseCase(
                application, plantRepo, careLogRepo, plantPhotoRepo, adaptiveDataStore, database, wateringAdjustmentRepo
            )
            useCase.recordStillMoistCheck(
                plant(wateringIntervalDays = 7).copy(wateringConfidence = 2),
                System.currentTimeMillis() + deferralMs
            )
        }

        assertEquals(2, captured.size)
        assertEquals(captured[0].afterIntervalDays, captured[1].afterIntervalDays)
        assertEquals(WateringAdjustmentTrigger.CHECK_STILL_MOIST, captured[0].trigger)
    }

    // #612 regression: the override write and adaptive watering's off-state must land in the same
    // single updatePlant call — a stale-object clobber previously reverted the override.
    @Test
    fun `recordStillMoistCheck does not touch wateringConfidence when adaptive_watering is off`() = runTest {
        val monstera = plant(wateringIntervalDays = 7).copy(wateringConfidence = 2)

        useCase.recordStillMoistCheck(monstera, newDueAt)

        coVerify(exactly = 1) {
            plantRepo.updatePlant(match { it.wateringDueDateOverride == newDueAt && it.wateringConfidence == 2 })
        }
    }

    // #612 regression: recordStillMoistAdaptiveObservation used to write wateringConfidence off a
    // stale pre-override plant snapshot in a second updatePlant call, silently reverting the override
    // written moments earlier. The fix folds both into one updatePlant call built off the same state.
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

            useCase.recordStillMoistCheck(monstera, newDueAt)

            // Bootstrap (currentConfidence == null) -> confidence becomes 0, which differs from null,
            // so the confidence update fires — and must land in the same call as the override write.
            coVerify(exactly = 1) {
                plantRepo.updatePlant(match { it.wateringConfidence == 0 && it.wateringDueDateOverride == newDueAt })
            }
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

            useCase.recordStillMoistCheck(monstera, newDueAt)

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
