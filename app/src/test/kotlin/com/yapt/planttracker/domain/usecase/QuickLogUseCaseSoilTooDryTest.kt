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
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * "Soil was already too dry" (#838, product ADR-0067) through [QuickLogUseCase]: the tick reaches the
 * model as [WateringReason.PLANT_NEEDED_IT], and [QuickLogUseCase.wateringGateFacts] feeds Care's gate.
 */
class QuickLogUseCaseSoilTooDryTest {

    private val application: Application = mockk(relaxed = true)
    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val database: PlantDatabase = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)
    private val awayPeriodRepo: AwayPeriodRepository = mockk()

    private val zone = ZoneId.systemDefault()
    private fun millisAt(date: LocalDate) = date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun dataStore(amplitude: String): DataStore<Preferences> = mockk {
        every { data } returns flowOf(preferencesOf(SettingsKeys.SEASONAL_AMPLITUDE to amplitude))
    }

    private fun useCase(amplitude: String = "OFF") = QuickLogUseCase(
        application,
        plantRepo,
        careLogRepo,
        plantPhotoRepo,
        dataStore(amplitude),
        database,
        wateringAdjustmentRepo,
        awayPeriodRepository = awayPeriodRepo
    )

    @Before
    fun setUp() {
        coEvery { careLogRepo.hasLogOfTypeOnDay(any(), any(), any(), any()) } returns false
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(any()) } returns emptyList()
        coEvery { careLogRepo.getRecentWaterings(any(), limit = any()) } returns emptyList()
        coEvery { careLogRepo.getWaterLogTimestampsAscending(any()) } returns emptyList()
        coEvery { plantRepo.updatePlant(any()) } returns Unit
        coEvery { awayPeriodRepo.getAll() } returns emptyList()
    }

    @Test
    fun `a ticked on-schedule watering saves TOO_LATE and shortens the base to about 6_24`() = runTest {
        val loggedAt = millisAt(LocalDate.of(2026, 6, 15))
        val plant = Plant(
            id = 1L,
            name = "Fern",
            wateringIntervalDays = 7,
            wateringConfidence = 0,
            createdAt = 0L,
            updatedAt = 0L
        )
        every { plantRepo.getPlantById(1L) } returns flowOf(plant)
        coEvery { careLogRepo.getLastWateringBefore(1L, loggedAt) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = millisAt(LocalDate.of(2026, 6, 8)))

        val outcome = useCase().quickWaterWithReason(plant, WateringReason.PLANT_NEEDED_IT, loggedAt)

        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.WATER && it.wateringFeedback == WateringFeedback.TOO_LATE }
            )
        }
        // The shortened base is surfaced as the interval suggestion (6.24 days, 6 effective vs 7), the
        // same hand-off every other TOO_LATE observation uses; confidence 0 -> 1 is persisted at once.
        val suggestion = requireNotNull(outcome.suggestion)
        assertEquals(6, suggestion.suggestedInterval)
        assertTrue(suggestion.suggestedBaseInterval in 6.2..6.3)
        coVerify(exactly = 1) {
            wateringAdjustmentRepo.addAdjustment(
                match {
                    it.trigger == WateringAdjustmentTrigger.WATER_TOO_LATE &&
                        it.beforeIntervalDays == 7 &&
                        it.afterIntervalDays == 6
                }
            )
        }
    }

    @Test
    fun `gate facts return the predecessor before the chosen date, ignoring later waterings`() = runTest {
        val chosen = millisAt(LocalDate.of(2026, 6, 15))
        val logs = listOf(
            millisAt(LocalDate.of(2026, 6, 1)),
            millisAt(LocalDate.of(2026, 6, 8)),
            millisAt(LocalDate.of(2026, 6, 20))
        ).map { CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = it) }
        coEvery { careLogRepo.getLastWateringBefore(1L, any()) } answers {
            val before = it.invocation.args[1] as Long
            logs.filter { log -> log.loggedAt < before }.maxByOrNull { log -> log.loggedAt }
        }

        val facts = useCase().wateringGateFacts(1L, chosen)

        assertEquals(millisAt(LocalDate.of(2026, 6, 8)), facts.previousWateringAt)
    }

    @Test
    fun `gate facts have no predecessor when nothing precedes the chosen date`() = runTest {
        coEvery { careLogRepo.getLastWateringBefore(1L, any()) } returns null

        assertNull(useCase().wateringGateFacts(1L, millisAt(LocalDate.of(2026, 6, 15))).previousWateringAt)
    }

    @Test
    fun `gate facts return the away periods and the seasonal amplitude`() = runTest {
        val trip = AwayPeriod(id = 1L, firstDay = LocalDate.of(2026, 7, 1), lastDay = LocalDate.of(2026, 7, 15))
        coEvery { awayPeriodRepo.getAll() } returns listOf(trip)
        coEvery { careLogRepo.getLastWateringBefore(1L, any()) } returns null

        val standard = useCase("STANDARD").wateringGateFacts(1L, millisAt(LocalDate.of(2026, 6, 15)))
        val off = useCase("OFF").wateringGateFacts(1L, millisAt(LocalDate.of(2026, 6, 15)))

        assertEquals(listOf(trip), standard.awayPeriods)
        assertEquals(SeasonalAmplitude.STANDARD.value, standard.seasonalAmplitude, 0.0)
        assertTrue(SeasonalAmplitude.STANDARD.value != 0.0)
        assertEquals(0.0, off.seasonalAmplitude, 0.0)
    }
}
