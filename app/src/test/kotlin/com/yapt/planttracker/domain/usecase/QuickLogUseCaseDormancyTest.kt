package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.model.WateringReason
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * The correctness-critical slice (#699/#761, product ADR-0044): a watering whose gap spans a plant's
 * dormancy window must not corrupt the adaptive model, even when the reason prompt somehow *did*
 * supply explicit feedback — the exclusion lives here, inside [QuickLogUseCase], not just in the UI
 * gate that normally suppresses the prompt (`PlantDetailScreenGateTest`'s `isChosenDateDormancySpanning`
 * coverage). Pins the exact 127-day, Oct25-to-Mar1, SOIL_STILL_MOIST scenario from the issue.
 */
class QuickLogUseCaseDormancyTest {

    private val application: Application = mockk(relaxed = true)
    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val dataStore: DataStore<Preferences> = mockk {
        every { data } returns flowOf(preferencesOf(SettingsKeys.SEASONAL_AMPLITUDE to "OFF"))
    }
    private val database: PlantDatabase = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)
    private lateinit var useCase: QuickLogUseCase

    private val zone = ZoneId.systemDefault()
    private fun millisAt(year: Int, month: Int, day: Int) =
        LocalDate.of(year, month, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    // Oct 25 -> Mar 1, a Nov-Feb dormancy window: the canonical 127-day gap from the issue.
    private val octoberTwentyFifth = millisAt(2026, 10, 25)
    private val marchFirst = millisAt(2027, 3, 1)

    private fun dormantPlant(confidence: Int? = 3) = Plant(
        id = 1L,
        name = "Cactus",
        wateringIntervalDays = 7,
        wateringConfidence = confidence,
        dormancyStartMonth = 11,
        dormancyEndMonth = 2,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Before
    fun setUp() {
        coEvery { careLogRepo.hasLogOfTypeOnDay(any(), any(), any(), any()) } returns false
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(any()) } returns emptyList()
        coEvery { careLogRepo.getRecentWaterings(any(), limit = any()) } returns emptyList()
        coEvery { careLogRepo.getWaterLogTimestampsAscending(any()) } returns emptyList()
        coEvery { plantRepo.updatePlant(any()) } returns Unit
        useCase = QuickLogUseCase(
            application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, database, wateringAdjustmentRepo
        )
    }

    // ---- Pure exclusion: a watering entirely inside dormancy, no exit yet ----

    @Test
    fun `a dormancy-spanning watering with explicit feedback leaves base and confidence unchanged`() = runTest {
        // New watering (Dec 15) also lands inside the window, so this is "excluded" without "exited" —
        // isolates the exclusion mechanism from the separate exit-decrement rule below.
        val decemberFifteenth = millisAt(2026, 12, 15)
        val monstera = dormantPlant(confidence = 3)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastWateringBefore(1L, decemberFifteenth) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octoberTwentyFifth)

        useCase.quickWaterWithReason(monstera, WateringReason.SOIL_STILL_MOIST, loggedAt = decemberFifteenth)

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        coVerify {
            wateringAdjustmentRepo.addAdjustment(
                match {
                    it.trigger == WateringAdjustmentTrigger.DORMANCY_EXCLUDED &&
                        it.beforeIntervalDays == it.afterIntervalDays
                }
            )
        }
        coVerify(exactly = 0) {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXIT })
        }
    }

    // #699/#761 (Codex review round 1 on #776, P2-c): defense-in-depth at the model layer, mirroring
    // AddCareLogViewModel's equivalent write-time suppression — a persisted WateringFeedback on a
    // dormancy-spanning log would otherwise still enter a later correctionStreak() window even though
    // this observation's own base/confidence transition is separately excluded above. In production
    // this reason never reaches quickWaterWithReason at all, since every UI surface's own gate already
    // suppresses the prompt for a dormancy-spanning gap (WateringReasonGate.kt/CalendarScreen/
    // PlantListScreen) — this test calls the use case directly, bypassing the UI gate entirely, to
    // prove the model-layer protection holds independent of it.
    @Test
    fun `a dormancy-spanning watering never persists the explicit feedback on the CareLog itself`() = runTest {
        val decemberFifteenth = millisAt(2026, 12, 15)
        val monstera = dormantPlant(confidence = 3)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastWateringBefore(1L, decemberFifteenth) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octoberTwentyFifth)

        useCase.quickWaterWithReason(monstera, WateringReason.SOIL_STILL_MOIST, loggedAt = decemberFifteenth)

        coVerify {
            careLogRepo.addLog(
                match {
                    it.careType == CareType.WATER && it.loggedAt == decemberFifteenth && it.wateringFeedback == null
                }
            )
        }
    }

    @Test
    fun `a plant with no dormancy window still persists explicit feedback normally`() = runTest {
        val noWindow = dormantPlant(confidence = 3).copy(dormancyStartMonth = null, dormancyEndMonth = null)
        every { plantRepo.getPlantById(1L) } returns flowOf(noWindow)
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octoberTwentyFifth)

        useCase.quickWaterWithReason(noWindow, WateringReason.SOIL_STILL_MOIST, loggedAt = marchFirst)

        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.WATER && it.wateringFeedback == WateringFeedback.TOO_SOON }
            )
        }
    }

    @Test
    fun `a dormancy-spanning watering never writes a WATER_TOO_SOON trigger even with SOIL_STILL_MOIST`() = runTest {
        // Pins the actual hazard: without the fix, this exact scenario ratchets base 7 -> ~10 days.
        val decemberFifteenth = millisAt(2026, 12, 15)
        val monstera = dormantPlant(confidence = 3)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastWateringBefore(1L, decemberFifteenth) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octoberTwentyFifth)

        useCase.quickWaterWithReason(monstera, WateringReason.SOIL_STILL_MOIST, loggedAt = decemberFifteenth)

        coVerify(exactly = 0) {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.WATER_TOO_SOON })
        }
    }

    // ---- Exit decrement: the canonical Oct25 -> Mar1 gap ----

    @Test
    fun `leaving dormancy decrements confidence by exactly 1 and writes both trigger rows`() = runTest {
        val monstera = dormantPlant(confidence = 3)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octoberTwentyFifth)

        useCase.quickWaterWithReason(monstera, WateringReason.SOIL_STILL_MOIST, loggedAt = marchFirst)

        coVerify {
            plantRepo.updatePlant(match { it.wateringConfidence == 2 && it.wateringIntervalDays == 7 })
        }
        coVerify {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXCLUDED })
        }
        coVerify {
            wateringAdjustmentRepo.addAdjustment(
                match {
                    it.trigger == WateringAdjustmentTrigger.DORMANCY_EXIT &&
                        it.beforeIntervalDays == it.afterIntervalDays
                }
            )
        }
    }

    @Test
    fun `the exit decrement stops exactly at the floor, confidence 1 becomes 0`() = runTest {
        val monstera = dormantPlant(confidence = 1)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octoberTwentyFifth)

        useCase.quickWaterWithReason(monstera, WateringReason.SOIL_STILL_MOIST, loggedAt = marchFirst)

        coVerify { plantRepo.updatePlant(match { it.wateringConfidence == 0 }) }
    }

    @Test
    fun `the exit decrement from confidence 0 is a true no-op, never written as a change`() = runTest {
        // coerceAtLeast(0) means (0 - 1).coerceAtLeast(0) == 0 — confidence doesn't actually move, so
        // there is nothing for persistAdaptiveState to write for this axis at all.
        val monstera = dormantPlant(confidence = 0)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octoberTwentyFifth)

        useCase.quickWaterWithReason(monstera, WateringReason.SOIL_STILL_MOIST, loggedAt = marchFirst)

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        coVerify {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXIT })
        }
    }

    @Test
    fun `the exit decrement fires at most once per cycle across a multi-year gap`() = runTest {
        // Two winters skipped entirely: Oct 2025 -> Apr 2027, still only ever -1.
        val octoberTwoThousandTwentyFive = millisAt(2025, 10, 25)
        val aprilTwoThousandTwentySeven = millisAt(2027, 4, 10)
        val monstera = dormantPlant(confidence = 4)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastWateringBefore(1L, aprilTwoThousandTwentySeven) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octoberTwoThousandTwentyFive)

        useCase.quickWaterWithReason(monstera, reason = null, loggedAt = aprilTwoThousandTwentySeven)

        coVerify(exactly = 1) {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXIT })
        }
        coVerify { plantRepo.updatePlant(match { it.wateringConfidence == 3 }) }
    }

    @Test
    fun `the pair after an exit straddles nothing, so a second exit never fires`() = runTest {
        // First call: Oct25 -> Mar1, the exit fires, confidence 3 -> 2.
        val monstera = dormantPlant(confidence = 3)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octoberTwentyFifth)
        useCase.quickWaterWithReason(monstera, reason = null, loggedAt = marchFirst)

        // Second call: Mar1 -> a month later, well outside the window on both ends. The plant object
        // reflects the confidence the first call actually persisted (2).
        val aprilTenth = millisAt(2027, 4, 10)
        val afterExit = monstera.copy(wateringConfidence = 2)
        every { plantRepo.getPlantById(1L) } returns flowOf(afterExit)
        coEvery { careLogRepo.getLastWateringBefore(1L, aprilTenth) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = marchFirst)

        useCase.quickWaterWithReason(afterExit, reason = null, loggedAt = aprilTenth)

        coVerify(exactly = 1) {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXIT })
        }
    }

    // ---- No dormancy window configured: unchanged pre-existing behaviour ----

    @Test
    fun `a plant with no dormancy window sees the unprotected pre-existing behaviour`() = runTest {
        val noWindow = dormantPlant(confidence = 3).copy(dormancyStartMonth = null, dormancyEndMonth = null)
        every { plantRepo.getPlantById(1L) } returns flowOf(noWindow)
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octoberTwentyFifth)

        useCase.quickWaterWithReason(noWindow, WateringReason.SOIL_STILL_MOIST, loggedAt = marchFirst)

        coVerify(exactly = 0) {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXCLUDED })
        }
        coVerify(exactly = 0) {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXIT })
        }
        // The exact pre-existing hazard: a 127-day TOO_SOON observation is NOT excluded (no frozen
        // path applies), so it ratchets the base upward — confirming this slice adds protection
        // without silently changing behaviour for a plant that never configured a window.
        coVerify {
            wateringAdjustmentRepo.addAdjustment(
                match { it.trigger == WateringAdjustmentTrigger.WATER_TOO_SOON && it.afterIntervalDays > 7 }
            )
        }
    }
}
