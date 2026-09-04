package com.yapt.planttracker.domain.schedule

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.util.toLocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Wiring tests for #569 (product ADR-0026) — the pure curve itself is covered by [SeasonalWateringTest]. */
class CareScheduleSeasonalTest {

    // Jan 5 12:00 UTC 2023 — the northern-hemisphere peak day, so season(now) = 1 + amplitude exactly.
    private val now = LocalDateUtcMillis(2023, 1, 5)

    @Before
    fun setUp() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    }

    private fun plantWith(
        wateringIntervalDays: Int? = 7,
        wateringBaseIntervalDays: Double? = null,
        pinIntervalToBase: Boolean = false
    ) = Plant(
        id = 1L,
        name = "Test Plant",
        wateringIntervalDays = wateringIntervalDays,
        createdAt = now,
        wateringBaseIntervalDays = wateringBaseIntervalDays,
        pinIntervalToBase = pinIntervalToBase
    )

    @Test
    fun `seasonalAmplitude 0 (flag off) ignores wateringBaseIntervalDays entirely`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(3)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 10, wateringBaseIntervalDays = 5.0),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now,
            seasonalAmplitude = 0.0
        )
        assertEquals(lastWatered + TimeUnit.DAYS.toMillis(10), status.nextWateringDueAt)
    }

    @Test
    fun `seasonalAmplitude on with no recorded base falls back to wateringIntervalDays as the base`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(3)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 10, wateringBaseIntervalDays = null),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now,
            seasonalAmplitude = 0.35,
            hemisphere = Hemisphere.NORTHERN
        )
        // now is the peak day: effectiveInterval = round(10 * 1.35) = 14 (round-half-up).
        assertEquals(lastWatered + TimeUnit.DAYS.toMillis(14), status.nextWateringDueAt)
    }

    @Test
    fun `seasonalAmplitude on with a recorded base multiplies the base, not the literal interval`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(3)
        val status = CareSchedule.computeStatus(
            // wateringIntervalDays is stale/face-value; wateringBaseIntervalDays is what's used.
            plant = plantWith(wateringIntervalDays = 999, wateringBaseIntervalDays = 10.0),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now,
            seasonalAmplitude = 0.35,
            hemisphere = Hemisphere.NORTHERN
        )
        assertEquals(lastWatered + TimeUnit.DAYS.toMillis(14), status.nextWateringDueAt)
    }

    @Test
    fun `pinIntervalToBase skips the seasonal curve even when seasonalAmplitude is on`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(3)
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = 10, wateringBaseIntervalDays = 5.0, pinIntervalToBase = true),
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now,
            seasonalAmplitude = 0.35,
            hemisphere = Hemisphere.NORTHERN
        )
        assertEquals(lastWatered + TimeUnit.DAYS.toMillis(10), status.nextWateringDueAt)
    }

    @Test
    fun `no watering interval configured stays not scheduled regardless of seasonalAmplitude`() {
        val status = CareSchedule.computeStatus(
            plant = plantWith(wateringIntervalDays = null, wateringBaseIntervalDays = 5.0),
            lastWateredAt = null,
            lastFertilizedAt = null,
            totalLogs = 0,
            now = now,
            seasonalAmplitude = 0.35
        )
        assertNull(status.nextWateringDueAt)
    }

    /**
     * #572 regression, in `CareSchedule` terms: before the fix, `applySuggestedInterval()` wrote only
     * `wateringIntervalDays`, leaving `wateringBaseIntervalDays` stale — this reproduces exactly that
     * bug shape (both plants share the same `wateringIntervalDays` the dialog would have applied, but
     * only one has a base dual-written alongside it) and asserts the due date only moves once the
     * base is dual-written too.
     */
    @Test
    fun `applying a suggestion without dual-writing wateringBaseIntervalDays never moves the due date`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(3)
        val staleBase = plantWith(wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0)
        val dualWrittenBase = plantWith(wateringIntervalDays = 14, wateringBaseIntervalDays = 14.0)

        val staleStatus = CareSchedule.computeStatus(
            plant = staleBase,
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now,
            seasonalAmplitude = 0.35,
            hemisphere = Hemisphere.NORTHERN
        )
        val dualWrittenStatus = CareSchedule.computeStatus(
            plant = dualWrittenBase,
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now,
            seasonalAmplitude = 0.35,
            hemisphere = Hemisphere.NORTHERN
        )

        val staleEffectiveInterval = SeasonalWatering.effectiveInterval(
            7.0,
            now.toLocalDate(),
            0.35,
            Hemisphere.NORTHERN
        )
        assertEquals(
            lastWatered + TimeUnit.DAYS.toMillis(staleEffectiveInterval.toLong()),
            staleStatus.nextWateringDueAt
        )
        assert(staleStatus.nextWateringDueAt != dualWrittenStatus.nextWateringDueAt) {
            "Expected the dual-written base to move the due date relative to the stale-base plant"
        }
    }

    @Test
    fun `effectiveWateringIntervalDaysForDisplay matches what computeStatus used for the due date`() {
        val lastWatered = now - TimeUnit.DAYS.toMillis(3)
        val plant = plantWith(wateringIntervalDays = 10, wateringBaseIntervalDays = 6.0)
        val status = CareSchedule.computeStatus(
            plant = plant,
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = now,
            seasonalAmplitude = 0.35,
            hemisphere = Hemisphere.NORTHERN
        )

        val displayedInterval = CareSchedule.effectiveWateringIntervalDaysForDisplay(
            plant,
            now.toLocalDate(),
            0.35,
            Hemisphere.NORTHERN
        )

        assertEquals(lastWatered + TimeUnit.DAYS.toMillis(displayedInterval!!.toLong()), status.nextWateringDueAt)
    }

    /**
     * #584 review: a same-day round-trip can't catch a double-deseasonalization bug, since
     * `effectiveInterval(deseasonalize(x, today), today) == x` regardless of amplitude — the
     * corruption only surfaces once the due date is computed on a materially different point in the
     * season curve. This applies a suggestion (already base-space, per
     * `QuickLogUseCase.applyWateringIntervalSuggestion()`'s contract) on the Jan-5 peak day, then re-derives
     * the due date six months later near the trough, and asserts it matches what the *correct*
     * (un-re-deseasonalized) base implies — not the shorter interval the pre-#584-fix
     * double-deseasonalization would have produced.
     */
    @Test
    fun `a suggestion applied at the season peak still computes the correct due date six months later`() {
        val applyDate = now // Jan 5 2023, the peak day: season(applyDate) = 1.35
        val sixMonthsLater = LocalDateUtcMillis(2023, 7, 5)
        val suggestedInterval = 12

        // The fixed behavior (QuickLogUseCase.applyWateringIntervalSuggestion()): newInterval is already
        // season-neutral/base-space, written to wateringBaseIntervalDays unchanged.
        val correctlyAppliedPlant = plantWith(
            wateringIntervalDays = suggestedInterval,
            wateringBaseIntervalDays = suggestedInterval.toDouble()
        )

        // The pre-#584 bug: re-deseasonalizing an already-base-space value at apply time.
        val buggyBase = SeasonalWatering.deseasonalize(
            suggestedInterval.toDouble(),
            applyDate.toLocalDate(),
            0.35,
            Hemisphere.NORTHERN
        )
        val buggyAppliedPlant = plantWith(
            wateringIntervalDays = suggestedInterval,
            wateringBaseIntervalDays = buggyBase
        )

        val lastWatered = sixMonthsLater - TimeUnit.DAYS.toMillis(3)

        val correctStatus = CareSchedule.computeStatus(
            plant = correctlyAppliedPlant,
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = sixMonthsLater,
            seasonalAmplitude = 0.35,
            hemisphere = Hemisphere.NORTHERN
        )
        val buggyStatus = CareSchedule.computeStatus(
            plant = buggyAppliedPlant,
            lastWateredAt = lastWatered,
            lastFertilizedAt = null,
            totalLogs = 1,
            now = sixMonthsLater,
            seasonalAmplitude = 0.35,
            hemisphere = Hemisphere.NORTHERN
        )

        val expectedEffectiveInterval = SeasonalWatering.effectiveInterval(
            suggestedInterval.toDouble(),
            sixMonthsLater.toLocalDate(),
            0.35,
            Hemisphere.NORTHERN
        )
        assertEquals(
            lastWatered + TimeUnit.DAYS.toMillis(expectedEffectiveInterval.toLong()),
            correctStatus.nextWateringDueAt
        )
        assert(correctStatus.nextWateringDueAt != buggyStatus.nextWateringDueAt) {
            "Expected the double-deseasonalized base to diverge from the correctly-applied base six months later"
        }
    }
}

@Suppress("FunctionNaming")
private fun LocalDateUtcMillis(year: Int, month: Int, day: Int): Long {
    val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month - 1, day, 12, 0, 0)
    return cal.timeInMillis
}
