package com.yapt.planttracker.domain.usecase

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.AwayPeriodRepository
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.AwayPeriod
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
 * Away mode's learning protection (#897, product ADR-0065, technical ADR-0038): an observation whose gap
 * overlaps a trip is excluded the way a dormancy-spanning one is, except for the label's precedence
 * and the dormancy-only exit decrement. Direct coverage of [AdaptiveWateringObservation] with
 * controlled repositories, mirroring [AdaptiveWateringObservationTest]'s dormancy block.
 */
class AdaptiveWateringObservationAwayTest {

    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)
    private val awayPeriodRepo: AwayPeriodRepository = mockk()
    private lateinit var originalDefaultTimeZone: TimeZone

    private val zone = ZoneId.of("UTC")
    private fun millisAt(year: Int, month: Int, day: Int) =
        LocalDate.of(year, month, day).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()

    // Jul 1 - Jul 15 2026, inclusive.
    private val trip = AwayPeriod(id = 1L, firstDay = LocalDate.of(2026, 7, 1), lastDay = LocalDate.of(2026, 7, 15))

    private fun dataStoreWithAmplitude(amplitude: String): DataStore<Preferences> = mockk {
        every { data } returns flowOf(preferencesOf(SettingsKeys.SEASONAL_AMPLITUDE to amplitude))
    }

    private fun plant(
        confidence: Int? = 3,
        dormancyStartMonth: Int? = null,
        dormancyEndMonth: Int? = null
    ) = Plant(
        id = 1L,
        name = "Monstera",
        wateringIntervalDays = 7,
        wateringConfidence = confidence,
        dormancyStartMonth = dormancyStartMonth,
        dormancyEndMonth = dormancyEndMonth,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun observation(periods: List<AwayPeriod> = listOf(trip)): AdaptiveWateringObservation {
        coEvery { awayPeriodRepo.getAll() } returns periods
        return AdaptiveWateringObservation(
            plantRepo,
            careLogRepo,
            dataStoreWithAmplitude("OFF"),
            wateringAdjustmentRepo,
            awayPeriodRepo
        )
    }

    private fun predecessor(loggedAt: Long, previous: Long, excludeLogId: Long? = null) {
        coEvery { careLogRepo.getLastWateringBefore(1L, loggedAt, excludeLogId) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = previous)
    }

    private fun recordAdjustments(): MutableList<WateringAdjustment> {
        val recorded = mutableListOf<WateringAdjustment>()
        coEvery { wateringAdjustmentRepo.addAdjustment(any()) } coAnswers {
            recorded.add(firstArg())
            1L
        }
        return recorded
    }

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

    // ---- observe: the exclusion itself ----

    @Test
    fun `a gap crossing the trip leaves base and confidence unchanged and writes one AWAY_EXCLUDED row`() = runTest {
        val recorded = recordAdjustments()
        val previous = millisAt(2026, 6, 25)
        val loggedAt = millisAt(2026, 7, 20)
        predecessor(loggedAt, previous)

        val result = observation().observe(
            plant(confidence = 3),
            feedback = WateringFeedback.TOO_LATE,
            loggedAt = loggedAt,
            displayNow = loggedAt
        )

        assertNull(result)
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        assertEquals(1, recorded.size)
        val row = recorded.single()
        assertEquals(WateringAdjustmentTrigger.AWAY_EXCLUDED, row.trigger)
        assertEquals(row.beforeIntervalDays, row.afterIntervalDays)
        assertEquals(7, row.afterIntervalDays)
        assertEquals(loggedAt, row.triggeredAt)
    }

    @Test
    fun `an away observation strips feedback so no feedback trigger or recent window entry is created`() = runTest {
        val recorded = recordAdjustments()
        val previous = millisAt(2026, 6, 25)
        val loggedAt = millisAt(2026, 7, 20)
        predecessor(loggedAt, previous)

        observation().observe(
            plant(confidence = 3),
            feedback = WateringFeedback.TOO_SOON,
            loggedAt = loggedAt,
            displayNow = loggedAt
        )

        assertEquals(listOf(WateringAdjustmentTrigger.AWAY_EXCLUDED), recorded.map { it.trigger })
    }

    @Test
    fun `a null confidence stays null through an away observation`() = runTest {
        val recorded = recordAdjustments()
        val previous = millisAt(2026, 6, 25)
        val loggedAt = millisAt(2026, 7, 20)
        predecessor(loggedAt, previous)

        observation().observe(
            plant(confidence = null),
            feedback = null,
            loggedAt = loggedAt,
            displayNow = loggedAt
        )

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        assertEquals(listOf(WateringAdjustmentTrigger.AWAY_EXCLUDED), recorded.map { it.trigger })
    }

    @Test
    fun `a log inside the trip is excluded and never writes DORMANCY_EXIT`() = runTest {
        val recorded = recordAdjustments()
        val previous = millisAt(2026, 6, 25)
        val loggedAt = millisAt(2026, 7, 8)
        predecessor(loggedAt, previous)

        observation().observe(
            plant(confidence = 3),
            feedback = null,
            loggedAt = loggedAt,
            displayNow = loggedAt
        )

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        assertEquals(listOf(WateringAdjustmentTrigger.AWAY_EXCLUDED), recorded.map { it.trigger })
    }

    @Test
    fun `a second log inside the same trip is excluded too`() = runTest {
        val recorded = recordAdjustments()
        val previous = millisAt(2026, 7, 4)
        val loggedAt = millisAt(2026, 7, 11)
        predecessor(loggedAt, previous)

        observation().observe(
            plant(confidence = 3),
            feedback = null,
            loggedAt = loggedAt,
            displayNow = loggedAt
        )

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        assertEquals(listOf(WateringAdjustmentTrigger.AWAY_EXCLUDED), recorded.map { it.trigger })
    }

    @Test
    fun `a backdated log inside the trip is excluded though it is entered after the trip`() = runTest {
        val recorded = recordAdjustments()
        val previous = millisAt(2026, 6, 28)
        val backdated = millisAt(2026, 7, 5)
        val entryDay = millisAt(2026, 7, 30)
        predecessor(backdated, previous)

        val result = observation().observe(
            plant(confidence = 3),
            feedback = WateringFeedback.TOO_LATE,
            loggedAt = backdated,
            displayNow = entryDay
        )

        assertNull(result)
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        assertEquals(listOf(WateringAdjustmentTrigger.AWAY_EXCLUDED), recorded.map { it.trigger })
        assertEquals(backdated, recorded.single().triggeredAt)
    }

    @Test
    fun `a backdated log across the trip is excluded though both ends are outside it`() = runTest {
        val recorded = recordAdjustments()
        val previous = millisAt(2026, 6, 20)
        val backdated = millisAt(2026, 7, 18)
        predecessor(backdated, previous)

        observation().observe(
            plant(confidence = 3),
            feedback = null,
            loggedAt = backdated,
            displayNow = millisAt(2026, 8, 20)
        )

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        assertEquals(listOf(WateringAdjustmentTrigger.AWAY_EXCLUDED), recorded.map { it.trigger })
    }

    @Test
    fun `a gap spanning both a trip and dormancy is labelled DORMANCY_EXCLUDED with its exit decrement intact`() =
        runTest {
            val recorded = recordAdjustments()
            val dormant = plant(confidence = 3, dormancyStartMonth = 11, dormancyEndMonth = 2)
            val winterTrip = AwayPeriod(
                id = 2L,
                firstDay = LocalDate.of(2026, 12, 20),
                lastDay = LocalDate.of(2027, 1, 5)
            )
            val previous = millisAt(2026, 10, 25)
            val marchFirst = millisAt(2027, 3, 1)
            predecessor(marchFirst, previous)

            observation(listOf(winterTrip)).observe(
                dormant,
                feedback = null,
                loggedAt = marchFirst,
                displayNow = marchFirst
            )

            assertEquals(
                listOf(WateringAdjustmentTrigger.DORMANCY_EXCLUDED, WateringAdjustmentTrigger.DORMANCY_EXIT),
                recorded.map { it.trigger }
            )
            coVerify { plantRepo.updatePlant(match { it.wateringConfidence == 2 && it.updatedAt == marchFirst }) }
        }

    @Test
    fun `a trip inside the dormancy window never adds an exit when the gap ends while still dormant`() = runTest {
        val recorded = recordAdjustments()
        val dormant = plant(confidence = 3, dormancyStartMonth = 11, dormancyEndMonth = 2)
        val winterTrip = AwayPeriod(
            id = 2L,
            firstDay = LocalDate.of(2026, 12, 20),
            lastDay = LocalDate.of(2027, 1, 5)
        )
        val previous = millisAt(2026, 12, 1)
        val loggedAt = millisAt(2027, 1, 10)
        predecessor(loggedAt, previous)

        observation(listOf(winterTrip)).observe(
            dormant,
            feedback = null,
            loggedAt = loggedAt,
            displayNow = loggedAt
        )

        assertEquals(listOf(WateringAdjustmentTrigger.DORMANCY_EXCLUDED), recorded.map { it.trigger })
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `the first gap after the trip learns normally`() = runTest {
        val recorded = recordAdjustments()
        val previous = millisAt(2026, 7, 16)
        val loggedAt = millisAt(2026, 7, 23)
        predecessor(loggedAt, previous)

        observation().observe(
            plant(confidence = 3),
            feedback = null,
            loggedAt = loggedAt,
            displayNow = loggedAt
        )

        assertEquals(listOf(WateringAdjustmentTrigger.WATER_NEUTRAL), recorded.map { it.trigger })
        coVerify { plantRepo.updatePlant(match { it.wateringConfidence == 4 && it.updatedAt == loggedAt }) }
    }

    @Test
    fun `an observation on a plant with no trips learns exactly as before`() = runTest {
        val recorded = recordAdjustments()
        val previous = millisAt(2026, 6, 1)
        val loggedAt = millisAt(2026, 6, 8)
        predecessor(loggedAt, previous)

        observation(periods = emptyList()).observe(
            plant(confidence = 3),
            feedback = null,
            loggedAt = loggedAt,
            displayNow = loggedAt
        )

        assertEquals(listOf(WateringAdjustmentTrigger.WATER_NEUTRAL), recorded.map { it.trigger })
    }

    // ---- bootstrap ----

    @Test
    fun `when the bootstrap wins on an away gap it also writes AWAY_EXCLUDED and no exit row`() = runTest {
        val recorded = recordAdjustments()
        val previous = millisAt(2026, 6, 29)
        val loggedAt = millisAt(2026, 7, 20)
        predecessor(loggedAt, previous)
        // 6 raw gaps (7 x 5, then 21 across the trip): confidence 2 unfiltered, 1 with the away gap dropped.
        coEvery { careLogRepo.getWaterLogTimestampsAscending(1L) } returns listOf(
            millisAt(2026, 5, 25), millisAt(2026, 6, 1), millisAt(2026, 6, 8), millisAt(2026, 6, 15),
            millisAt(2026, 6, 22), previous, loggedAt
        )

        val result = observation().observe(
            plant(confidence = null),
            feedback = null,
            loggedAt = loggedAt,
            displayNow = loggedAt
        )

        assertNull(result)
        assertEquals(
            setOf(WateringAdjustmentTrigger.HISTORY_BOOTSTRAP, WateringAdjustmentTrigger.AWAY_EXCLUDED),
            recorded.map { it.trigger }.toSet()
        )
        val awayRow = recorded.single { it.trigger == WateringAdjustmentTrigger.AWAY_EXCLUDED }
        assertEquals(awayRow.beforeIntervalDays, awayRow.afterIntervalDays)
        coVerify { plantRepo.updatePlant(match { it.wateringConfidence == 1 }) }
    }

    @Test
    fun `the bootstrap ignores an away gap in history so it falls below the threshold`() = runTest {
        val recorded = recordAdjustments()
        val previous = millisAt(2026, 6, 22)
        val loggedAt = millisAt(2026, 7, 20)
        predecessor(loggedAt, previous)
        // Raw gaps: 7, 7, 28 (across the trip) = 3, which would clear MIN_BOOTSTRAP_GAPS; without the
        // away gap only 2 remain, so no bootstrap fires and the null confidence stays null.
        coEvery { careLogRepo.getWaterLogTimestampsAscending(1L) } returns listOf(
            millisAt(2026, 6, 8), millisAt(2026, 6, 15), previous, loggedAt
        )

        observation().observe(
            plant(confidence = null),
            feedback = null,
            loggedAt = loggedAt,
            displayNow = loggedAt
        )

        assertEquals(listOf(WateringAdjustmentTrigger.AWAY_EXCLUDED), recorded.map { it.trigger })
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `the bootstrap drops an away gap from earlier history, not only the triggering one`() = runTest {
        val recorded = recordAdjustments()
        val previous = millisAt(2026, 8, 12)
        val loggedAt = millisAt(2026, 8, 19)
        predecessor(loggedAt, previous)
        // 6 raw gaps: 7, 28 (Jun 22 -> Jul 20, across the trip), then four weekly ones. Unfiltered that
        // is confidence 2; with the earlier away gap dropped it is 5 gaps, confidence 1.
        coEvery { careLogRepo.getWaterLogTimestampsAscending(1L) } returns listOf(
            millisAt(2026, 6, 15), millisAt(2026, 6, 22), millisAt(2026, 7, 20),
            millisAt(2026, 7, 27), millisAt(2026, 8, 3), previous, loggedAt
        )

        observation().observe(
            plant(confidence = null),
            feedback = null,
            loggedAt = loggedAt,
            displayNow = loggedAt
        )

        assertEquals(listOf(WateringAdjustmentTrigger.HISTORY_BOOTSTRAP), recorded.map { it.trigger })
        coVerify {
            plantRepo.updatePlant(match { it.wateringBaseIntervalDays == 7.0 && it.wateringConfidence == 1 })
        }
    }

    // ---- feedbackForLog ----

    @Test
    fun `feedbackForLog strips feedback when the predecessor gap spans a trip`() = runTest {
        val loggedAt = millisAt(2026, 7, 20)
        predecessor(loggedAt, millisAt(2026, 6, 25), excludeLogId = null)

        val result = observation().feedbackForLog(plant(), WateringFeedback.TOO_LATE, loggedAt)

        assertNull(result)
    }

    @Test
    fun `feedbackForLog strips feedback from an edit that moves a log into an away-spanning position`() = runTest {
        val editedTo = millisAt(2026, 7, 9)
        predecessor(editedTo, millisAt(2026, 6, 25), excludeLogId = 42L)

        val result = observation().feedbackForLog(plant(), WateringFeedback.TOO_SOON, editedTo, excludeLogId = 42L)

        assertNull(result)
    }

    @Test
    fun `feedbackForLog keeps feedback when the gap stays clear of every trip`() = runTest {
        val loggedAt = millisAt(2026, 7, 23)
        predecessor(loggedAt, millisAt(2026, 7, 16), excludeLogId = null)

        val result = observation().feedbackForLog(plant(), WateringFeedback.TOO_LATE, loggedAt)

        assertEquals(WateringFeedback.TOO_LATE, result)
    }

    @Test
    fun `feedbackForLog without an away repository behaves as before`() = runTest {
        val loggedAt = millisAt(2026, 7, 20)
        predecessor(loggedAt, millisAt(2026, 6, 25), excludeLogId = null)
        val noAway = AdaptiveWateringObservation(
            plantRepo,
            careLogRepo,
            dataStoreWithAmplitude("OFF"),
            wateringAdjustmentRepo
        )

        assertEquals(WateringFeedback.TOO_LATE, noAway.feedbackForLog(plant(), WateringFeedback.TOO_LATE, loggedAt))
    }
}
