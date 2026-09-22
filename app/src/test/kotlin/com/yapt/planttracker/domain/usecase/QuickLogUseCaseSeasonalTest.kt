package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.yapt.planttracker.data.db.PlantDatabase
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
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * `quickWaterWithReason`'s de-seasonalization coverage (#569/#572), split out of
 * [QuickLogUseCaseTest] to keep that file under Detekt's `LargeClass` threshold — mirrors
 * `PlantDetailViewModelSeasonalTest`'s precedent.
 *
 * Every `quickWaterWithReason` call below passes `loggedAt = peakDay` explicitly (#654 review round 1):
 * since the season used to de-seasonalize an observed gap is now evaluated at the caller's `loggedAt`
 * rather than [nowProvider] (see `QuickLogUseCase.deseasonalizedObservedIntervalDays`/
 * `effectiveIntervalForDisplay`), leaving `loggedAt` at its real-wall-clock default would decouple it
 * from [nowProvider]'s pinned [peakDay] and make these tests depend on whatever day they happen to run.
 */
class QuickLogUseCaseSeasonalTest {

    private val application: Application = mockk(relaxed = true)
    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val database: PlantDatabase = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)

    @Before
    fun setUp() {
        coEvery { careLogRepo.hasLogOfTypeOnDay(any(), any(), any(), any()) } returns false
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(any()) } returns emptyList()
        // #571: below the 3-gap bootstrap threshold by default — see QuickLogUseCaseTest's identical stub.
        coEvery { careLogRepo.getWaterLogTimestampsAscending(any()) } returns emptyList()
        coEvery { plantRepo.updatePlant(any()) } returns Unit
    }

    private fun plant(id: Long = 1L, name: String = "Monstera", wateringIntervalDays: Int? = null) = Plant(
        id = id,
        name = name,
        wateringIntervalDays = wateringIntervalDays,
        createdAt = 0L,
        updatedAt = 0L
    )

    /** Amplitude defaults to STANDARD (graduated, #656), with [nowProvider] pinned to [peakDay]. */
    private fun useCaseWithSeasonOn(peakDay: Long): QuickLogUseCase {
        val seasonalDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(emptyPreferences())
        }
        return QuickLogUseCase(
            application,
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            seasonalDataStore,
            database,
            wateringAdjustmentRepo,
            nowProvider = { peakDay }
        )
    }

    @Test
    fun `quickWaterWithReason de-seasonalizes the gap for a non-pinned plant`() =
        runTest {
            val peakDay = localDateUtcMillis(2023, 1, 5)
            val useCase = useCaseWithSeasonOn(peakDay)
            val twentyDaysBeforePeak = peakDay - TimeUnit.DAYS.toMillis(20)
            val monstera = plant(wateringIntervalDays = 10)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { careLogRepo.getLastWateringBefore(1L, peakDay) } returns
                CareLog(
                    plantId = 1L,
                    careType = CareType.WATER,
                    loggedAt = twentyDaysBeforePeak,
                    wateringFeedback = WateringFeedback.JUST_RIGHT
                )
            coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

            val outcome = useCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT, loggedAt = peakDay)

            // Peak day (Jan 5, northern): season(peakDay) = 1 + 0.35 = 1.35, so the observed 20-day
            // gap de-seasonalizes to round(20 / 1.35) = 15 before feeding the adaptive model.
            val deseasonalizedObserved = SeasonalWatering.deseasonalizeToDays(
                20,
                LocalDate.of(2023, 1, 5),
                SeasonalAmplitude.STANDARD.value,
                Hemisphere.NORTHERN
            )
            val expected = CareSchedule.computeAdaptiveInterval(
                feedback = WateringFeedback.TOO_LATE,
                observedIntervalDays = deseasonalizedObserved,
                currentBaseIntervalDays = 10,
                currentConfidence = null,
                recentFeedback = emptyList()
            )
            val expectedSuggestion = expected.intervalDays.takeIf { it != 10 }
            assertEquals(expectedSuggestion, outcome.suggestion?.suggestedInterval)
        }

    @Test
    fun `quickWaterWithReason adapts against wateringBaseIntervalDays, not stale wateringIntervalDays`() =
        runTest {
            // #572 regression: currentBaseIntervalDays must be season-neutral (wateringBaseIntervalDays)
            // once amplitude isn't Off and the plant isn't pinned — feeding it the raw
            // wateringIntervalDays (10, stale once season is on) instead of wateringBaseIntervalDays
            // (6.0, the live season-neutral value) is exactly the bug this issue fixes.
            val peakDay = localDateUtcMillis(2023, 1, 5)
            val useCase = useCaseWithSeasonOn(peakDay)
            val monstera = plant(wateringIntervalDays = 10).copy(wateringBaseIntervalDays = 6.0)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { careLogRepo.getLastWateringBefore(1L, peakDay) } returns
                CareLog(
                    plantId = 1L,
                    careType = CareType.WATER,
                    loggedAt = peakDay - TimeUnit.DAYS.toMillis(20),
                    wateringFeedback = WateringFeedback.JUST_RIGHT
                )
            coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

            val outcome = useCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT, loggedAt = peakDay)

            val deseasonalizedObserved = SeasonalWatering.deseasonalizeToDays(
                20,
                LocalDate.of(2023, 1, 5),
                SeasonalAmplitude.STANDARD.value,
                Hemisphere.NORTHERN
            )
            val expectedFromBase = CareSchedule.computeAdaptiveInterval(
                feedback = WateringFeedback.TOO_LATE,
                observedIntervalDays = deseasonalizedObserved,
                currentBaseIntervalDays = 6,
                currentConfidence = null,
                recentFeedback = emptyList()
            )
            val expectedFromStaleInterval = CareSchedule.computeAdaptiveInterval(
                feedback = WateringFeedback.TOO_LATE,
                observedIntervalDays = deseasonalizedObserved,
                currentBaseIntervalDays = 10,
                currentConfidence = null,
                recentFeedback = emptyList()
            )
            assertEquals(expectedFromBase.intervalDays.takeIf { it != 10 }, outcome.suggestion?.suggestedInterval)
            assertTrue(expectedFromBase.intervalDays != expectedFromStaleInterval.intervalDays)
            // #584 review: the WATER_TOO_LATE row itself must log the true base (6), not the stale
            // literal wateringIntervalDays (10) — the same value PlantDetailViewModelSeasonalTest's
            // "applySuggestedInterval logs the same base-space beforeIntervalDays..." case asserts a
            // DIALOG_EDIT row would log for this identical plant shape, so "Recent adjustments" never
            // mixes units for the same underlying change.
            coVerify {
                wateringAdjustmentRepo.addAdjustment(
                    match {
                        it.trigger == WateringAdjustmentTrigger.WATER_TOO_LATE && it.beforeIntervalDays == 6
                    }
                )
            }
        }

    @Test
    fun `quickWaterWithReason skips de-seasonalization for a pinned plant`() = runTest {
        val peakDay = localDateUtcMillis(2023, 1, 5)
        val useCase = useCaseWithSeasonOn(peakDay)
        val twentyDaysBeforePeak = peakDay - TimeUnit.DAYS.toMillis(20)
        val monstera = plant(wateringIntervalDays = 10).copy(pinIntervalToBase = true)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastWateringBefore(1L, peakDay) } returns
            CareLog(
                plantId = 1L,
                careType = CareType.WATER,
                loggedAt = twentyDaysBeforePeak,
                wateringFeedback = WateringFeedback.JUST_RIGHT
            )
        coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

        val outcome = useCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT, loggedAt = peakDay)

        val expected = CareSchedule.computeAdaptiveInterval(
            feedback = WateringFeedback.TOO_LATE,
            observedIntervalDays = 20,
            currentBaseIntervalDays = 10,
            currentConfidence = null,
            recentFeedback = emptyList()
        )
        val expectedSuggestion = expected.intervalDays.takeIf { it != 10 }
        assertEquals(expectedSuggestion, outcome.suggestion?.suggestedInterval)
    }

    /**
     * #620's own gate, at the choke point ([QuickLogUseCase.computeSuggestion]) shared by Calendar,
     * Plant List, and Plant Detail: when the base-space suggestion's effective-space conversion equals
     * `plant.wateringIntervalDays`, the whole "change" is a unit-mismatch artifact, not a real model
     * change, so no [com.yapt.planttracker.domain.model.QuickWaterSuggestion] should be emitted at all —
     * not even one whose two numbers happen to render identically.
     */
    @Test
    fun `quickWaterWithReason suppresses the suggestion entirely when its effective value equals current`() =
        runTest {
            val peakDay = localDateUtcMillis(2023, 1, 5)
            val useCase = useCaseWithSeasonOn(peakDay)
            val fourteenDaysBeforePeak = peakDay - TimeUnit.DAYS.toMillis(14)

            // No feedback (`null` -> NEUTRAL_TARGET_MULTIPLIER = 1.0): the deseasonalized observed gap
            // (round(14 / 1.35) = 10) matches the base exactly, so the model returns the base unchanged
            // regardless of gain — a deterministic raw suggestion of 10 with no adaptive-model math to
            // hand-simulate.
            val expectedEffective = CareSchedule.effectiveWateringIntervalDaysForDisplay(
                plant = plant(wateringIntervalDays = 10).copy(wateringBaseIntervalDays = 10.0),
                nowDate = LocalDate.of(2023, 1, 5),
                seasonalAmplitude = SeasonalAmplitude.STANDARD.value
            ) ?: 10
            val monstera = plant(wateringIntervalDays = expectedEffective).copy(wateringBaseIntervalDays = 10.0)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { careLogRepo.getLastWateringBefore(1L, peakDay) } returns
                CareLog(
                    plantId = 1L,
                    careType = CareType.WATER,
                    loggedAt = fourteenDaysBeforePeak,
                    wateringFeedback = null
                )
            coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

            val outcome = useCase.quickWaterWithReason(monstera, null, loggedAt = peakDay)

            assertEquals(null, outcome.suggestion)
        }

    @Test
    fun `quickWaterWithReason's suggestedIntervalEffective is the base-to-effective conversion`() = runTest {
        val peakDay = localDateUtcMillis(2023, 1, 5)
        val useCase = useCaseWithSeasonOn(peakDay)
        val twentyDaysBeforePeak = peakDay - TimeUnit.DAYS.toMillis(20)

        // TOO_LATE feedback, so a gap that disagrees with the base still moves it (unlike a null
        // observation, which #586 (product ADR-0030) excludes from base learning when off-schedule) —
        // a real raw suggestion distinct from the current literal interval.
        val monstera = plant(wateringIntervalDays = 10)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastWateringBefore(1L, peakDay) } returns
            CareLog(
                plantId = 1L,
                careType = CareType.WATER,
                loggedAt = twentyDaysBeforePeak,
                wateringFeedback = WateringFeedback.JUST_RIGHT
            )
        coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

        val outcome = useCase.quickWaterWithReason(monstera, WateringReason.PLANT_NEEDED_IT, loggedAt = peakDay)

        val deseasonalizedObserved = SeasonalWatering.deseasonalizeToDays(
            20,
            LocalDate.of(2023, 1, 5),
            SeasonalAmplitude.STANDARD.value,
            Hemisphere.NORTHERN
        )
        val expectedRaw = CareSchedule.computeAdaptiveInterval(
            feedback = WateringFeedback.TOO_LATE,
            observedIntervalDays = deseasonalizedObserved,
            currentBaseIntervalDays = 10,
            currentConfidence = null,
            recentFeedback = emptyList()
        ).intervalDays
        val expectedEffective = CareSchedule.effectiveWateringIntervalDaysForDisplay(
            plant = plant(wateringIntervalDays = expectedRaw).copy(wateringBaseIntervalDays = expectedRaw.toDouble()),
            nowDate = LocalDate.of(2023, 1, 5),
            seasonalAmplitude = SeasonalAmplitude.STANDARD.value
        ) ?: expectedRaw

        assertEquals(expectedRaw, outcome.suggestion?.suggestedInterval)
        assertEquals(expectedEffective, outcome.suggestion?.suggestedIntervalEffective)
    }

    /**
     * #716 worked-example regression (acceptance criteria 1, 3): a plant anchored around Sep 1 at
     * "every 7 days" (the stale, never-since-updated literal `wateringIntervalDays = 7`) with a real
     * season-neutral base of 8.8 — the old gate compared `effectiveIntervalForDisplay(...)` against
     * that stale `7` literal directly, so on Sep 13 (`season = 0.866`, `round(8.8 * 0.866) = 8 != 7`)
     * it fired the product ADR-0006 dialog purely off calendar drift, blaming whichever watering was
     * logged that day. Watering 1 day early (observed gap 7, de-seasonalizing to `round(7 / 0.866) =
     * 8`, agreeing with the base within [CareSchedule.GAP_AGREEMENT_TOLERANCE]) is exactly the
     * reported symptom's own numbers. The fixed gate compares live-to-live instead: pre-observation
     * `round(8.8 * 0.866) = 8` vs post-observation `round(8.68 * 0.866) = 8` (the neutral,
     * capped-gain nudge moves the base from 8.8 to 8.68 — a real but sub-threshold correction) — both
     * sides agree, so no suggestion is surfaced at all. Acceptance criterion 7 ("no `DIALOG_DISMISSAL`
     * row reachable for a calendar-only delta") follows structurally from this, not from any extra
     * assertion here: `recordWateringSuggestionDismissal()` is only ever reached from a Dismiss tap on
     * a dialog that opened, and `outcome.suggestion == null` below means none did — there is nothing
     * left to independently pin without asserting a call this test never makes in the first place.
     */
    @Test
    fun `quickWaterWithReason suppresses a suggestion on pure seasonal drift (#716 worked example)`() = runTest {
        val sep13 = localDateUtcMillis(2023, 9, 13)
        val useCase = useCaseWithSeasonOn(sep13)
        val sevenDaysBeforeSep13 = sep13 - TimeUnit.DAYS.toMillis(7)
        val monstera = plant(wateringIntervalDays = 7).copy(wateringBaseIntervalDays = 8.8)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastWateringBefore(1L, sep13) } returns
            CareLog(
                plantId = 1L,
                careType = CareType.WATER,
                loggedAt = sevenDaysBeforeSep13,
                wateringFeedback = null
            )
        coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

        // No reason: watered 1 day early while on schedule, so no prompt ever appeared (#586).
        val outcome = useCase.quickWaterWithReason(monstera, null, loggedAt = sep13)

        assertEquals(null, outcome.suggestion)
        // technical ADR-0027: the sub-threshold base correction still persists immediately even
        // though nothing crossed a rounding boundary for display.
        coVerify {
            plantRepo.updatePlant(match { it.wateringBaseIntervalDays != null && it.wateringBaseIntervalDays!! < 8.8 })
        }
    }

    /**
     * #716 acceptance criterion 2's sibling: when the model's base genuinely moves *and* that movement
     * crosses today's display-rounding threshold, the suggestion must still surface — the fix only
     * suppresses calendar-only drift, not genuine model movement. `suggestedIntervalEffective` is
     * unaffected by this fix (still the base-to-effective conversion the existing
     * `suggestedIntervalEffective is the base-to-effective conversion` test already covers) — this
     * test's own focus is that the *gate itself* still lets a real change through.
     */
    @Test
    fun `quickWaterWithReason still surfaces a suggestion when the base genuinely crosses a rounding threshold`() =
        runTest {
            val sep13 = localDateUtcMillis(2023, 9, 13)
            val useCase = useCaseWithSeasonOn(sep13)
            val eightDaysBeforeSep13 = sep13 - TimeUnit.DAYS.toMillis(8)
            val monstera = plant(wateringIntervalDays = 7).copy(wateringBaseIntervalDays = 8.6)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { careLogRepo.getLastWateringBefore(1L, sep13) } returns
                CareLog(
                    plantId = 1L,
                    careType = CareType.WATER,
                    loggedAt = eightDaysBeforeSep13,
                    wateringFeedback = null
                )
            coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

            val outcome = useCase.quickWaterWithReason(monstera, null, loggedAt = sep13)

            assertTrue(outcome.suggestion != null)
            // Pre-observation base 8.6 -> round(8.6 * 0.866) = 7; the observed 8-day gap de-seasonalizes
            // to 9 (agreeing with the base within tolerance), nudging the base up to 8.66, which crosses
            // the rounding boundary to round(8.66 * 0.866) = 8 -- a genuine, real change.
            assertEquals(7, outcome.suggestion?.currentIntervalEffective)
            assertEquals(8, outcome.suggestion?.suggestedIntervalEffective)
        }

    /**
     * #716 acceptance criterion 4: a pinned plant's gate must reduce to exactly the pre-existing
     * literal comparison, provably unchanged by this fix — [currentAdaptiveBaseIntervalDays] and
     * [effectiveIntervalForDisplay] both collapse to identity when pinned, regardless of
     * [Plant.wateringBaseIntervalDays]. `wateringBaseIntervalDays` is deliberately set to a wildly
     * different value (8.8) than the literal (10) to prove it's ignored entirely on both sides of the
     * gate, not merely coincidentally equal.
     */
    @Test
    fun `quickWaterWithReason's gate ignores wateringBaseIntervalDays entirely for a pinned plant (#716)`() = runTest {
        val peakDay = localDateUtcMillis(2023, 9, 13)
        val useCase = useCaseWithSeasonOn(peakDay)
        val tenDaysBeforePeak = peakDay - TimeUnit.DAYS.toMillis(10)
        val monstera = plant(wateringIntervalDays = 10)
            .copy(pinIntervalToBase = true, wateringBaseIntervalDays = 8.8)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.getLastWateringBefore(1L, peakDay) } returns
            CareLog(
                plantId = 1L,
                careType = CareType.WATER,
                loggedAt = tenDaysBeforePeak,
                wateringFeedback = null
            )
        coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

        // No reason (null feedback, on schedule): observed gap = current literal exactly -> the model
        // returns the base unchanged (target == currentBase == 10) regardless of gain, so the raw
        // suggestion also equals the literal -- suppressed, exactly like the pre-#716 pinned-plant gate.
        val outcome = useCase.quickWaterWithReason(monstera, null, loggedAt = peakDay)

        assertEquals(null, outcome.suggestion)
    }

    /**
     * #716 review round 1 regression, direction 1 (spurious dialog): `computeSuggestion()`'s two
     * `effectiveIntervalForDisplay` calls must be evaluated at real wall-clock `displayNow`, never at
     * the observation's own (possibly backdated, #654) `now`. STANDARD amplitude, northern hemisphere;
     * logged date Jan 1 (`season = 1.349`), real today Sep 21 (`season = 0.912`) — the two seasons must
     * provably differ, mirroring `WateringLifecycleResetTest`'s `displayNow` test. Both this test and
     * its direction-2 sibling below share the exact same observed gap (11 days) and feedback
     * (`TOO_LATE`, full confidence-0 gain of 0.60 — feedback is non-null, so #586's neutral-only
     * exclusion never applies) — only the plant's starting base differs, isolating the display-date bug
     * from every other variable in the model.
     *
     * `computeAdaptiveInterval(TOO_LATE, observed=8 [11 days deseasonalized at Jan 1], base=5.0)` moves
     * the base to 5.936. At the *logged* date (Jan 1) that is `round(5.0 * 1.349) = 7` ->
     * `round(5.936 * 1.349) = 8` — a real jump, which the pre-fix code (evaluating both sides at `now`)
     * would have surfaced as a dialog. At *today* (Sep 21) it is `round(5.0 * 0.912) = 5` ->
     * `round(5.936 * 0.912) = 5` — nothing the user would actually see change. The fixed gate must
     * therefore return no suggestion at all.
     */
    @Test
    fun `computeSuggestion uses displayNow not backdated now - direction 1, spurious dialog (#716 rr1)`() =
        runTest {
            val jan1 = localDateUtcMillis(2023, 1, 1)
            val sep21 = localDateUtcMillis(2023, 9, 21)
            val useCase = useCaseWithSeasonOn(jan1)
            val elevenDaysBeforeJan1 = jan1 - TimeUnit.DAYS.toMillis(11)
            val monstera = plant(wateringIntervalDays = 7).copy(wateringBaseIntervalDays = 5.0)
            coEvery { careLogRepo.getLastWateringBefore(1L, jan1) } returns
                CareLog(
                    plantId = 1L,
                    careType = CareType.WATER,
                    loggedAt = elevenDaysBeforeJan1,
                    wateringFeedback = null
                )
            coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

            val outcome = useCase.computeSuggestion(monstera, WateringFeedback.TOO_LATE, now = jan1, displayNow = sep21)

            assertEquals(null, outcome)
        }

    /**
     * #716 review round 1 regression, direction 2 (silent bypass — the worse direction): the mirror
     * image of the test above, same gap/feedback, starting one rounding band higher (base 5.6 instead
     * of 5.0). `computeAdaptiveInterval(TOO_LATE, observed=8, base=5.6)` moves the base to 6.176. At the
     * *logged* date (Jan 1): `round(5.6 * 1.349) = 8` -> `round(6.176 * 1.349) = 8` — unchanged, so the
     * pre-fix code (evaluating both sides at `now`) would have taken its *silent-persist* branch and
     * written the moved base straight to the database with no dialog and no
     * `askBeforeChangingIntervals` check at all. But at *today* (Sep 21): `round(5.6 * 0.912) = 5` ->
     * `round(6.176 * 0.912) = 6` — the number the user actually reads on screen right now really does
     * move. The fixed gate must surface a real suggestion instead of silently persisting, and — since
     * the suggestion is now pending rather than silently applied — must leave `wateringBaseIntervalDays`
     * untouched at its original 5.6 (any write is deferred to the explicit apply path).
     */
    @Test
    fun `computeSuggestion uses displayNow not backdated now - direction 2, silent bypass (#716 rr1)`() =
        runTest {
            val jan1 = localDateUtcMillis(2023, 1, 1)
            val sep21 = localDateUtcMillis(2023, 9, 21)
            val useCase = useCaseWithSeasonOn(jan1)
            val elevenDaysBeforeJan1 = jan1 - TimeUnit.DAYS.toMillis(11)
            val monstera = plant(wateringIntervalDays = 8).copy(wateringBaseIntervalDays = 5.6)
            coEvery { careLogRepo.getLastWateringBefore(1L, jan1) } returns
                CareLog(
                    plantId = 1L,
                    careType = CareType.WATER,
                    loggedAt = elevenDaysBeforeJan1,
                    wateringFeedback = null
                )
            coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()

            val outcome = useCase.computeSuggestion(monstera, WateringFeedback.TOO_LATE, now = jan1, displayNow = sep21)

            assertTrue(outcome != null)
            assertEquals(5, outcome?.currentIntervalEffective)
            assertEquals(6, outcome?.suggestedIntervalEffective)
            coVerify {
                plantRepo.updatePlant(match { it.wateringBaseIntervalDays == 5.6 })
            }
        }
}

private fun localDateUtcMillis(year: Int, month: Int, day: Int): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month - 1, day, 12, 0, 0)
    return cal.timeInMillis
}
