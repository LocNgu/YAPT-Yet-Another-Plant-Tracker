package com.yapt.planttracker.domain.usecase

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.model.WateringFeedback
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.util.TimeZone

/**
 * Focused direct coverage of [AdaptiveWateringObservation] (#780, technical ADR-0030), the one
 * shared write path behind [QuickLogUseCase] and `AddCareLogViewModel`. Exercises `feedbackForLog`
 * and `observe` with controlled repositories and clocks, without going through either caller.
 * Their own suites remain responsible for end-to-end wiring; this class protects the shared
 * seams: dormancy suppression/provenance, bootstrap precedence, confidence/adjustment
 * persistence, the observation-vs-display clock split, gap-source policy, and edit mode (#783).
 *
 * The default timezone is pinned to UTC (northern hemisphere) for the duration of this class so
 * [com.yapt.planttracker.domain.schedule.SeasonalWatering.currentHemisphere] is deterministic
 * regardless of the CI machine's own default zone; only the observation-vs-display-clock tests
 * below actually exercise the seasonal curve.
 */
class AdaptiveWateringObservationTest {

    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)
    private lateinit var originalDefaultTimeZone: TimeZone

    private val zone = ZoneId.of("UTC")
    private fun millisAt(year: Int, month: Int, day: Int) =
        LocalDate.of(year, month, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    private fun dataStoreWithAmplitude(amplitude: String): DataStore<Preferences> = mockk {
        every { data } returns flowOf(preferencesOf(SettingsKeys.SEASONAL_AMPLITUDE to amplitude))
    }

    @Suppress("LongParameterList")
    private fun plant(
        confidence: Int? = 3,
        wateringIntervalDays: Int? = 7,
        wateringBaseIntervalDays: Double? = null,
        dormancyStartMonth: Int? = null,
        dormancyEndMonth: Int? = null,
        pinIntervalToBase: Boolean = false
    ) = Plant(
        id = 1L,
        name = "Monstera",
        wateringIntervalDays = wateringIntervalDays,
        wateringConfidence = confidence,
        wateringBaseIntervalDays = wateringBaseIntervalDays,
        dormancyStartMonth = dormancyStartMonth,
        dormancyEndMonth = dormancyEndMonth,
        pinIntervalToBase = pinIntervalToBase,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun observation(dataStore: DataStore<Preferences> = dataStoreWithAmplitude("OFF")) =
        AdaptiveWateringObservation(plantRepo, careLogRepo, dataStore, wateringAdjustmentRepo)

    @Before
    fun setUp() {
        originalDefaultTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        coEvery { careLogRepo.getRecentWaterings(any(), limit = any()) } returns emptyList()
        coEvery { careLogRepo.getWaterLogTimestampsAscending(any()) } returns emptyList()
        coEvery { plantRepo.updatePlant(any()) } returns Unit
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(originalDefaultTimeZone)
    }

    // ---- feedbackForLog ----

    @Test
    fun `feedbackForLog returns null feedback as null without touching the repository`() = runTest {
        val result = observation().feedbackForLog(plant(), feedback = null, loggedAt = millisAt(2026, 6, 1))

        assertNull(result)
        coVerify(exactly = 0) { careLogRepo.getLastWateringBefore(any(), any(), any()) }
    }

    @Test
    fun `feedbackForLog keeps feedback when there is no predecessor`() = runTest {
        val loggedAt = millisAt(2026, 6, 1)
        coEvery { careLogRepo.getLastWateringBefore(1L, loggedAt, null) } returns null

        val result = observation().feedbackForLog(plant(), feedback = WateringFeedback.TOO_SOON, loggedAt = loggedAt)

        assertEquals(WateringFeedback.TOO_SOON, result)
    }

    @Test
    fun `feedbackForLog clears feedback when the predecessor gap spans dormancy`() = runTest {
        val dormant = plant(dormancyStartMonth = 11, dormancyEndMonth = 2)
        val decemberFifteenth = millisAt(2026, 12, 15)
        coEvery { careLogRepo.getLastWateringBefore(1L, decemberFifteenth, null) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = millisAt(2026, 10, 25))

        val result = observation().feedbackForLog(
            dormant,
            feedback = WateringFeedback.TOO_SOON,
            loggedAt = decemberFifteenth
        )

        assertNull(result)
    }

    @Test
    fun `feedbackForLog passes excludeLogId through to getLastWateringBefore`() = runTest {
        val loggedAt = millisAt(2026, 6, 1)
        coEvery { careLogRepo.getLastWateringBefore(1L, loggedAt, 42L) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = millisAt(2026, 5, 1))

        val result = observation().feedbackForLog(
            plant(),
            feedback = WateringFeedback.TOO_LATE,
            loggedAt = loggedAt,
            excludeLogId = 42L
        )

        assertEquals(WateringFeedback.TOO_LATE, result)
        coVerify { careLogRepo.getLastWateringBefore(1L, loggedAt, 42L) }
    }

    // ---- Dormancy provenance ----

    @Test
    fun `a spanning gap without exit writes DORMANCY_EXCLUDED and leaves confidence unchanged`() = runTest {
        val dormant = plant(confidence = 3, dormancyStartMonth = 11, dormancyEndMonth = 2)
        val decemberFifteenth = millisAt(2026, 12, 15)
        coEvery { careLogRepo.getLastWateringBefore(1L, decemberFifteenth) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = millisAt(2026, 10, 25))

        val result = observation().observe(
            dormant,
            feedback = null,
            loggedAt = decemberFifteenth,
            displayNow = decemberFifteenth,
            gapSource = AdaptiveWateringObservation.GapSource.CHRONOLOGICAL_PREDECESSOR
        )

        assertNull(result)
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

    @Test
    fun `a spanning gap that exits dormancy also writes DORMANCY_EXIT and drops confidence by one`() = runTest {
        val dormant = plant(confidence = 3, dormancyStartMonth = 11, dormancyEndMonth = 2)
        val marchFirst = millisAt(2027, 3, 1)
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = millisAt(2026, 10, 25))

        observation().observe(
            dormant,
            feedback = null,
            loggedAt = marchFirst,
            displayNow = marchFirst,
            gapSource = AdaptiveWateringObservation.GapSource.CHRONOLOGICAL_PREDECESSOR
        )

        coVerify { plantRepo.updatePlant(match { it.wateringConfidence == 2 && it.updatedAt == marchFirst }) }
        coVerify {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXCLUDED })
        }
        coVerify {
            wateringAdjustmentRepo.addAdjustment(
                match {
                    it.trigger == WateringAdjustmentTrigger.DORMANCY_EXIT &&
                        it.beforeIntervalDays == it.afterIntervalDays &&
                        it.triggeredAt == marchFirst
                }
            )
        }
    }

    @Test
    fun `an exit already recorded for the same dormancy cycle is not written or applied again`() = runTest {
        val recorded = mutableListOf<WateringAdjustment>()
        val statefulRepo: WateringAdjustmentRepository = mockk {
            coEvery { addAdjustment(any()) } coAnswers {
                recorded.add(firstArg())
                1L
            }
            coEvery { getByTrigger(1L, WateringAdjustmentTrigger.DORMANCY_EXIT) } coAnswers {
                recorded.filter { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXIT }
            }
        }
        val statefulObservation =
            AdaptiveWateringObservation(plantRepo, careLogRepo, dataStoreWithAmplitude("OFF"), statefulRepo)

        val aprilTenth = millisAt(2027, 4, 10)
        val octoberTwentyFifth = millisAt(2026, 10, 25)
        val monstera = plant(confidence = 3, dormancyStartMonth = 11, dormancyEndMonth = 2)
        coEvery { careLogRepo.getLastWateringBefore(1L, aprilTenth) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octoberTwentyFifth)

        statefulObservation.observe(
            monstera,
            feedback = null,
            loggedAt = aprilTenth,
            displayNow = aprilTenth,
            gapSource = AdaptiveWateringObservation.GapSource.CHRONOLOGICAL_PREDECESSOR
        )

        val marchFirst = millisAt(2027, 3, 1)
        val afterFirstExit = monstera.copy(wateringConfidence = 2)
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octoberTwentyFifth)

        statefulObservation.observe(
            afterFirstExit,
            feedback = null,
            loggedAt = marchFirst,
            displayNow = marchFirst,
            gapSource = AdaptiveWateringObservation.GapSource.CHRONOLOGICAL_PREDECESSOR
        )

        assertEquals(1, recorded.count { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXIT })
        coVerify(exactly = 1) { plantRepo.updatePlant(match { it.wateringConfidence == 2 }) }
    }

    // ---- Bootstrap precedence ----

    @Test
    fun `null confidence with enough history writes HISTORY_BOOTSTRAP and observe returns null`() = runTest {
        val neverAdapted = plant(confidence = null, dormancyStartMonth = null, dormancyEndMonth = null)
        val previous = millisAt(2026, 6, 29)
        val loggedAt = millisAt(2026, 7, 6)
        coEvery { careLogRepo.getLastWateringBefore(1L, loggedAt) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = previous)
        coEvery { careLogRepo.getWaterLogTimestampsAscending(1L) } returns listOf(
            millisAt(2026, 6, 1), millisAt(2026, 6, 8), millisAt(2026, 6, 15),
            millisAt(2026, 6, 22), millisAt(2026, 6, 29)
        )

        val result = observation().observe(
            neverAdapted,
            feedback = null,
            loggedAt = loggedAt,
            displayNow = loggedAt,
            gapSource = AdaptiveWateringObservation.GapSource.CHRONOLOGICAL_PREDECESSOR
        )

        assertNull(result)
        coVerify {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.HISTORY_BOOTSTRAP })
        }
        coVerify { plantRepo.updatePlant(match { it.wateringConfidence == 1 }) }
    }

    @Test
    fun `when the bootstrap wins on a spanning gap it also writes DORMANCY_EXCLUDED and no DORMANCY_EXIT`() = runTest {
        val neverAdapted = plant(confidence = null, dormancyStartMonth = 11, dormancyEndMonth = 2)
        val marchFirst = millisAt(2027, 3, 1)
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = millisAt(2026, 10, 25))
        coEvery { careLogRepo.getWaterLogTimestampsAscending(1L) } returns listOf(
            millisAt(2026, 6, 1), millisAt(2026, 6, 8), millisAt(2026, 6, 15),
            millisAt(2026, 6, 22), millisAt(2026, 6, 29)
        )

        val result = observation().observe(
            neverAdapted,
            feedback = null,
            loggedAt = marchFirst,
            displayNow = marchFirst,
            gapSource = AdaptiveWateringObservation.GapSource.CHRONOLOGICAL_PREDECESSOR
        )

        assertNull(result)
        coVerify {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.HISTORY_BOOTSTRAP })
        }
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

    // ---- Persistence ----

    @Test
    fun `an ordinary observation writes one adjustment row and persists confidence with matching timestamps`() =
        runTest {
            val monstera = plant(confidence = 3)
            val previous = millisAt(2026, 6, 1)
            val loggedAt = millisAt(2026, 6, 8)
            coEvery { careLogRepo.getLastWateringBefore(1L, loggedAt) } returns
                CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = previous)

            val result = observation().observe(
                monstera,
                feedback = null,
                loggedAt = loggedAt,
                displayNow = loggedAt,
                gapSource = AdaptiveWateringObservation.GapSource.CHRONOLOGICAL_PREDECESSOR
            )

            assertNull(result)
            coVerify(exactly = 1) { wateringAdjustmentRepo.addAdjustment(any()) }
            coVerify {
                wateringAdjustmentRepo.addAdjustment(
                    match {
                        it.trigger == WateringAdjustmentTrigger.WATER_NEUTRAL &&
                            it.beforeIntervalDays == 7 &&
                            it.afterIntervalDays == 7 &&
                            it.triggeredAt == loggedAt
                    }
                )
            }
            coVerify { plantRepo.updatePlant(match { it.wateringConfidence == 4 && it.updatedAt == loggedAt }) }
        }

    // ---- Observation time versus display time (#716) ----

    @Test
    fun `observation and display clocks split -- an unchanged display value persists the base silently`() = runTest {
        val monstera = plant(confidence = 5, wateringIntervalDays = 20, wateringBaseIntervalDays = 20.0)
        val loggedAt = millisAt(2026, 10, 10)
        val previous = millisAt(2026, 9, 16)
        val displayNow = millisAt(2027, 4, 10)
        coEvery { careLogRepo.getLastWateringBefore(1L, loggedAt) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = previous)

        val result = observation(dataStoreWithAmplitude("STANDARD")).observe(
            monstera,
            feedback = null,
            loggedAt = loggedAt,
            displayNow = displayNow,
            gapSource = AdaptiveWateringObservation.GapSource.CHRONOLOGICAL_PREDECESSOR
        )

        assertNull(result)
        coVerify {
            plantRepo.updatePlant(
                match { it.wateringBaseIntervalDays == 20.45 && it.wateringConfidence == 5 && it.updatedAt == loggedAt }
            )
        }
        coVerify {
            wateringAdjustmentRepo.addAdjustment(match { it.triggeredAt == loggedAt })
        }
    }

    @Test
    fun `observation and display clocks split -- a changed display value returns a suggestion, base not persisted`() =
        runTest {
            val monstera = plant(confidence = 5, wateringIntervalDays = 20, wateringBaseIntervalDays = 20.0)
            val loggedAt = millisAt(2026, 10, 10)
            val previous = millisAt(2026, 9, 16)
            val displayNow = millisAt(2027, 4, 20)
            coEvery { careLogRepo.getLastWateringBefore(1L, loggedAt) } returns
                CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = previous)

            val result = observation(dataStoreWithAmplitude("STANDARD")).observe(
                monstera,
                feedback = null,
                loggedAt = loggedAt,
                displayNow = displayNow,
                gapSource = AdaptiveWateringObservation.GapSource.CHRONOLOGICAL_PREDECESSOR
            )

            assertEquals(
                AdaptiveWateringObservation.Suggestion(
                    intervalDays = 20,
                    effectiveIntervalDays = 19,
                    baseIntervalDays = 20.45,
                    currentEffectiveIntervalDays = 18
                ),
                result
            )
            coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        }

    // ---- Gap source ----

    @Test
    fun `CHRONOLOGICAL_PREDECESSOR returns null with no writes when there is no predecessor`() = runTest {
        val monstera = plant(confidence = 3)
        val loggedAt = millisAt(2026, 6, 8)
        coEvery { careLogRepo.getLastWateringBefore(1L, loggedAt) } returns null

        val result = observation().observe(
            monstera,
            feedback = null,
            loggedAt = loggedAt,
            displayNow = loggedAt,
            gapSource = AdaptiveWateringObservation.GapSource.CHRONOLOGICAL_PREDECESSOR
        )

        assertNull(result)
        coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `NEWEST_PAIR_OR_CONFIGURED falls back to the configured interval when there is no newest pair`() = runTest {
        val monstera = plant(confidence = 3, wateringIntervalDays = 7)
        val loggedAt = millisAt(2026, 6, 8)
        coEvery { careLogRepo.getLastWateringBefore(1L, loggedAt) } returns null
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns emptyList()

        val result = observation().observe(
            monstera,
            feedback = null,
            loggedAt = loggedAt,
            displayNow = loggedAt,
            gapSource = AdaptiveWateringObservation.GapSource.NEWEST_PAIR_OR_CONFIGURED
        )

        assertNull(result)
        coVerify { careLogRepo.getLastTwoWaterings(1L) }
        coVerify {
            wateringAdjustmentRepo.addAdjustment(
                match {
                    it.trigger == WateringAdjustmentTrigger.WATER_NEUTRAL &&
                        it.beforeIntervalDays == 7 &&
                        it.afterIntervalDays == 7
                }
            )
        }
        coVerify { plantRepo.updatePlant(match { it.wateringConfidence == 4 }) }
    }

    @Test
    fun `a zero newest-pair gap still records dormancy exclusion when the true predecessor spans dormancy`() = runTest {
        val dormant = plant(confidence = 3, dormancyStartMonth = 11, dormancyEndMonth = 2)
        val marchFirst = millisAt(2027, 3, 1)
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = millisAt(2026, 10, 25))
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = marchFirst),
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = marchFirst)
        )

        observation().observe(
            dormant,
            feedback = null,
            loggedAt = marchFirst,
            displayNow = marchFirst,
            gapSource = AdaptiveWateringObservation.GapSource.NEWEST_PAIR_OR_CONFIGURED
        )

        coVerify {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXCLUDED })
        }
    }

    // ---- Edit mode ----

    @Test
    fun `observe in edit mode returns null and never touches a repository`() = runTest {
        val monstera = plant(confidence = 3)
        val loggedAt = millisAt(2026, 6, 8)

        val result = observation().observe(
            monstera,
            feedback = null,
            loggedAt = loggedAt,
            displayNow = loggedAt,
            gapSource = AdaptiveWateringObservation.GapSource.CHRONOLOGICAL_PREDECESSOR,
            isEditMode = true
        )

        assertNull(result)
        coVerify(exactly = 0) { careLogRepo.getLastWateringBefore(any(), any(), any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }
}
