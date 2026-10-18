package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
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
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.model.WateringReason
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * Away mode (#897, product ADR-0065) through [QuickLogUseCase], the one call site of
 * [AdaptiveWateringObservation.observe]: the exclusion lives in the model layer, so it holds even when a
 * surface hands over an explicit reason, as `QuickLogUseCaseDormancyTest` pins for dormancy.
 */
class QuickLogUseCaseAwayTest {

    private val application: Application = mockk(relaxed = true)
    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val dataStore: DataStore<Preferences> = mockk {
        every { data } returns flowOf(preferencesOf(SettingsKeys.SEASONAL_AMPLITUDE to "OFF"))
    }
    private val database: PlantDatabase = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)
    private val awayPeriodRepo: AwayPeriodRepository = mockk()
    private lateinit var useCase: QuickLogUseCase

    private val zone = ZoneId.systemDefault()
    private fun millisAt(year: Int, month: Int, day: Int) =
        LocalDate.of(year, month, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private val trip = AwayPeriod(id = 1L, firstDay = LocalDate.of(2026, 7, 1), lastDay = LocalDate.of(2026, 7, 15))

    private val monstera = Plant(
        id = 1L,
        name = "Monstera",
        wateringIntervalDays = 7,
        wateringConfidence = 3,
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
        coEvery { awayPeriodRepo.getAll() } returns listOf(trip)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        useCase = QuickLogUseCase(
            application,
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            dataStore,
            database,
            wateringAdjustmentRepo,
            awayPeriodRepository = awayPeriodRepo
        )
    }

    private fun lastWateringWas(loggedAt: Long, previous: Long) {
        coEvery { careLogRepo.getLastWateringBefore(1L, loggedAt) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = previous)
    }

    @Test
    fun `an away-spanning watering never persists explicit feedback on the CareLog`() = runTest {
        val loggedAt = millisAt(2026, 7, 20)
        lastWateringWas(loggedAt, millisAt(2026, 6, 25))

        useCase.quickWaterWithReason(monstera, WateringReason.SOIL_STILL_MOIST, loggedAt = loggedAt)

        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.WATER && it.loggedAt == loggedAt && it.wateringFeedback == null }
            )
        }
    }

    @Test
    fun `an away-spanning watering leaves the plant row alone and writes AWAY_EXCLUDED`() = runTest {
        val loggedAt = millisAt(2026, 7, 20)
        lastWateringWas(loggedAt, millisAt(2026, 6, 25))

        val outcome = useCase.quickWaterWithReason(monstera, WateringReason.SOIL_STILL_MOIST, loggedAt = loggedAt)

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        coVerify(exactly = 1) {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.AWAY_EXCLUDED })
        }
        coVerify(exactly = 0) {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.WATER_TOO_SOON })
        }
        coVerify(exactly = 0) {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXIT })
        }
        assertNull(outcome.suggestion)
    }

    @Test
    fun `a liquid-fertilizer paired watering across the trip is excluded the same way`() = runTest {
        val loggedAt = millisAt(2026, 7, 20)
        val liquid = monstera.copy(useLiquidFertilizer = true)
        lastWateringWas(loggedAt, millisAt(2026, 6, 25))

        useCase.quickLiquidFertilizeWithReason(liquid, WateringReason.SOIL_STILL_MOIST, loggedAt = loggedAt)

        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.WATER && it.wateringFeedback == null }
            )
        }
        coVerify(exactly = 1) {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.AWAY_EXCLUDED })
        }
    }

    @Test
    fun `the first watering after the trip keeps its explicit feedback and learns`() = runTest {
        val loggedAt = millisAt(2026, 7, 23)
        lastWateringWas(loggedAt, millisAt(2026, 7, 16))

        useCase.quickWaterWithReason(monstera, WateringReason.SOIL_STILL_MOIST, loggedAt = loggedAt)

        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.WATER && it.wateringFeedback == WateringFeedback.TOO_SOON }
            )
        }
        coVerify(exactly = 0) {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.AWAY_EXCLUDED })
        }
    }
}
