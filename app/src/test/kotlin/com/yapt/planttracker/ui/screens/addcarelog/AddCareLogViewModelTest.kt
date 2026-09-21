package com.yapt.planttracker.ui.screens.addcarelog

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.util.Calendar
import java.util.TimeZone

@OptIn(ExperimentalCoroutinesApi::class)
class AddCareLogViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val careLogRepo: CareLogRepository = mockk()
    private val plantRepo: PlantRepository = mockk()

    private val now = System.currentTimeMillis()

    private fun plant(id: Long = 1L, wateringIntervalDays: Int? = 7, useLiquidFertilizer: Boolean = false) = Plant(
        id = id,
        name = "Monstera",
        wateringIntervalDays = wateringIntervalDays,
        createdAt = 0L,
        updatedAt = 0L,
        useLiquidFertilizer = useLiquidFertilizer
    )

    private fun waterLog(loggedAt: Long = now) = CareLog(
        id = 0L,
        plantId = 1L,
        careType = CareType.WATER,
        loggedAt = loggedAt,
        wateringFeedback = WateringFeedback.JUST_RIGHT
    )

    @Before
    fun setup() {
        // Default: no same-day log of any type exists yet; individual tests override to true to
        // exercise the duplicate-rejection paths (#509).
        coEvery { careLogRepo.hasLogOfTypeOnDay(any(), any(), any(), any()) } returns false
        // #571: below the 3-gap bootstrap threshold by default, so existing adaptive-model tests keep
        // exercising the plain per-observation path — tests exercising the bootstrap itself override this.
        coEvery { careLogRepo.getWaterLogTimestampsAscending(any()) } returns emptyList()
        // Now unconditional (no more ADAPTIVE_WATERING flag gate) — every WATER-log save with a
        // configured interval and 2+ prior waterings reaches this; individual tests override with a
        // real correction-streak window where that matters.
        coEvery { careLogRepo.getRecentWaterings(any(), limit = any()) } returns emptyList()
        // #699/#761 (product ADR-0044): default no predecessor at all, so isDormancySpanningForLoggedAt()
        // and computeSuggestedInterval()'s own dormancy check both resolve to "nothing to gate against"
        // for every existing test that doesn't care about dormancy — mirrors PlantDetailScreenTest's
        // identical precedent for the same call ("every 'Log watering' date-picker confirm now calls
        // previousWateringBefore() regardless of which test triggers it", .claude/rules/plant-detail.md).
        // Tests exercising dormancy override this with a real predecessor.
        coEvery { careLogRepo.getLastWateringBefore(any(), any()) } returns null
        // #699/#761 (Codex review round 3 on #776, P2): isDormancySpanningForLoggedAt() now runs
        // unconditionally on every WATER save, including edit mode (no more !isEditMode gate) — so
        // every existing edit-mode WATER test needs a plant lookup default too, not just non-edit ones.
        // null resolves the dormancy check to "nothing to gate against", same as the predecessor default
        // above; tests exercising dormancy override this with a real plant.
        every { plantRepo.getPlantById(any()) } returns flowOf(null)
    }

    @Test
    fun `new log can start with a preselected care type`() {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())

        val vm = AddCareLogViewModel(
            careLogRepo,
            plantRepo,
            plantId = 1L
        )
        vm.preselectCareType(CareType.PHOTO)

        assertEquals(CareType.PHOTO, vm.selectedCareType)

        vm.selectedCareType = CareType.NOTE
        vm.preselectCareType(CareType.PHOTO)
        assertEquals(CareType.NOTE, vm.selectedCareType)
    }

    @Test
    fun `save WATER log with JUST_RIGHT feedback emits Saved with null interval when gap matches stored`() = runTest {
        val sevenDaysAgo = now - 7L * 24 * 60 * 60 * 1000
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = now),
            waterLog(loggedAt = sevenDaysAgo)
        )
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.JUST_RIGHT

        vm.events.test {
            vm.saveLog()
            val event = awaitItem()
            assertTrue(event is AddCareLogViewModel.Event.Saved)
            assertNull((event as AddCareLogViewModel.Event.Saved).suggestedWateringInterval)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `save WATER log with JUST_RIGHT feedback emits Saved with suggested interval when gap differs from stored`() = runTest {
        val observedAt = localDateUtcMillis(2026, 1, 15)
        val sevenDaysAgo = localDateUtcMillis(2026, 1, 8)
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 14))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = observedAt),
            waterLog(loggedAt = sevenDaysAgo)
        )
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.JUST_RIGHT
        vm.loggedAt = observedAt

        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            // Confidence-weighted adaptive model (#655): base=14, target=observed(7)*JUST_RIGHT(1.00)=7,
            // first-ever observation uses the confidence-0 gain (0.60) -> 14 + 0.60*(7-14) = 9.8 -> 10.
            assertEquals(10, event.suggestedWateringInterval)
            cancelAndIgnoreRemainingEvents()
        }
        coVerify { plantRepo.updatePlant(match { it.updatedAt == observedAt }) }
    }

    @Test
    fun `save WATER log with TOO_SOON feedback emits Saved with non-null suggested interval`() = runTest {
        val threeDaysAgo = now - 3L * 24 * 60 * 60 * 1000
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = now),
            waterLog(loggedAt = threeDaysAgo)
        )
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.TOO_SOON

        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            assertTrue(event.suggestedWateringInterval != null)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // #699/#761 (product ADR-0044): this VM's own copy of the adaptive path must exclude a
    // dormancy-spanning gap from base learning too — even with explicit TOO_SOON feedback typed on
    // the form (this screen has no dynamic reason prompt to suppress; the exclusion has to hold at
    // the model layer regardless). Pins the same 127-day, Oct25-to-Mar1 scenario QuickLogUseCase's
    // own dormancy tests pin, confirming both independent copies agree.
    /**
     * Shared setup for the two dormancy-exclusion regression tests below — extracted to keep each
     * test's own body under Detekt's `LongMethod` threshold. [predecessorLoggedAt] is the plant's true
     * chronological predecessor of [marchFirst] ([CareLogRepository.getLastWateringBefore]);
     * [lastTwoWaterings] is a separate, independently-stubbed pair used only by
     * `computeSuggestedInterval`'s unrelated `actualIntervalDays` calculation (P2-d note above).
     */
    private fun buildDormancySpanningWaterVm(
        dormantPlant: Plant,
        lastTwoWaterings: List<CareLog>,
        predecessorLoggedAt: Long,
        marchFirst: Long
    ): Pair<AddCareLogViewModel, WateringAdjustmentRepository> {
        val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)
        every { plantRepo.getPlantById(1L) } returns flowOf(dormantPlant)
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns lastTwoWaterings
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = predecessorLoggedAt)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(
            careLogRepo,
            plantRepo,
            plantId = 1L,
            wateringAdjustmentRepository = wateringAdjustmentRepo
        )
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.TOO_SOON
        vm.loggedAt = marchFirst
        return vm to wateringAdjustmentRepo
    }

    @Test
    fun `save WATER log spanning dormancy excludes base learning and decrements confidence on exit`() = runTest {
        val octoberTwentyFifth = localDateUtcMillis(2026, 10, 25)
        val marchFirst = localDateUtcMillis(2027, 3, 1)
        val dormantPlant = plant(wateringIntervalDays = 7).copy(
            wateringConfidence = 3,
            dormancyStartMonth = 11,
            dormancyEndMonth = 2
        )
        val (vm, wateringAdjustmentRepo) = buildDormancySpanningWaterVm(
            dormantPlant = dormantPlant,
            lastTwoWaterings = listOf(waterLog(loggedAt = marchFirst), waterLog(loggedAt = octoberTwentyFifth)),
            predecessorLoggedAt = octoberTwentyFifth,
            marchFirst = marchFirst
        )

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // P2-c: the TOO_SOON feedback typed on the form is never persisted on a dormancy-spanning log.
        coVerify {
            careLogRepo.addLog(
                match { it.careType == CareType.WATER && it.loggedAt == marchFirst && it.wateringFeedback == null }
            )
        }
        // Base unchanged (7), confidence decremented by exactly 1 for leaving dormancy (3 -> 2) —
        // never the ~10-day ratchet a bare TOO_SOON observation would otherwise cause.
        coVerify {
            plantRepo.updatePlant(match { it.wateringConfidence == 2 && it.wateringIntervalDays == 7 })
        }
        coVerify {
            wateringAdjustmentRepo.addAdjustment(
                match {
                    it.trigger == WateringAdjustmentTrigger.DORMANCY_EXCLUDED &&
                        it.beforeIntervalDays == it.afterIntervalDays
                }
            )
        }
        coVerify {
            wateringAdjustmentRepo.addAdjustment(
                match {
                    it.trigger == WateringAdjustmentTrigger.DORMANCY_EXIT &&
                        it.beforeIntervalDays == it.afterIntervalDays
                }
            )
        }
        coVerify(exactly = 0) {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.WATER_TOO_SOON })
        }
    }

    @Test
    fun `form bootstrap keeps dormancy provenance and does not decrement the new confidence`() = runTest {
        val octoberTwentyFifth = localDateUtcMillis(2026, 10, 25)
        val marchFirst = localDateUtcMillis(2027, 3, 1)
        val neverAdapted = plant(wateringIntervalDays = 7).copy(
            wateringConfidence = null,
            dormancyStartMonth = 11,
            dormancyEndMonth = 2
        )
        val (vm, wateringAdjustmentRepo) = buildDormancySpanningWaterVm(
            dormantPlant = neverAdapted,
            lastTwoWaterings = listOf(waterLog(loggedAt = marchFirst), waterLog(loggedAt = octoberTwentyFifth)),
            predecessorLoggedAt = octoberTwentyFifth,
            marchFirst = marchFirst
        )
        coEvery { careLogRepo.getWaterLogTimestampsAscending(1L) } returns listOf(
            localDateUtcMillis(2026, 6, 1),
            localDateUtcMillis(2026, 6, 8),
            localDateUtcMillis(2026, 6, 15),
            localDateUtcMillis(2026, 6, 22),
            localDateUtcMillis(2026, 6, 29)
        )

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.wateringConfidence == 1 }) }
        coVerify {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.HISTORY_BOOTSTRAP })
        }
        coVerify {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXCLUDED })
        }
        coVerify(exactly = 0) {
            wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXIT })
        }
    }

    // #699/#761 (product ADR-0044, Codex review round 1 on #776, P2-d): getLastTwoWaterings() returns
    // the plant's globally newest pair, which disagrees with a backdated entry's own chronological
    // predecessor once that entry is older than both. Before this fix, the dormancy check ran against
    // an unrelated, non-dormant gap and the entry escaped exclusion entirely — this pins the exact
    // scenario: the backdated entry's true predecessor (Oct 25) is dormancy-spanning with it, but the
    // globally newest pair (two April waterings, both already on file and both later than the entry
    // being saved) is not.
    @Test
    fun `a backdated WATER log is excluded by its own predecessor even when the globally newest pair is unrelated`() =
        runTest {
            val octoberTwentyFifth = localDateUtcMillis(2026, 10, 25)
            val marchFirst = localDateUtcMillis(2027, 3, 1)
            val aprilFirst = localDateUtcMillis(2027, 4, 1)
            val aprilEighth = localDateUtcMillis(2027, 4, 8)
            val dormantPlant = plant(wateringIntervalDays = 7).copy(
                wateringConfidence = 3,
                dormancyStartMonth = 11,
                dormancyEndMonth = 2
            )
            // The globally newest pair: two unrelated April waterings, both already on file and both
            // chronologically *after* the entry being saved below; the true predecessor (Oct 25) is
            // queried relative to marchFirst itself, not this pair.
            val (vm, wateringAdjustmentRepo) = buildDormancySpanningWaterVm(
                dormantPlant = dormantPlant,
                lastTwoWaterings = listOf(waterLog(loggedAt = aprilEighth), waterLog(loggedAt = aprilFirst)),
                predecessorLoggedAt = octoberTwentyFifth,
                marchFirst = marchFirst
            )

            vm.events.test {
                vm.saveLog()
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            coVerify {
                careLogRepo.addLog(
                    match {
                        it.careType == CareType.WATER && it.loggedAt == marchFirst && it.wateringFeedback == null
                    }
                )
            }
            coVerify {
                wateringAdjustmentRepo.addAdjustment(
                    match { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXCLUDED }
                )
            }
            coVerify(exactly = 0) {
                wateringAdjustmentRepo.addAdjustment(match { it.trigger == WateringAdjustmentTrigger.WATER_TOO_SOON })
            }
        }

    // #699/#761 (product ADR-0044, Codex review round 2 on #776, P1-4): actualIntervalDays comes from
    // lastTwoWaterings (the plant's globally-newest pair), a *different* query than the dormancy
    // check's own predecessor (previousWateringBefore). Before this fix, a stale same-day-duplicate
    // pair elsewhere in history (actualIntervalDays == 0, reachable from imported/historical data —
    // the duplicate guard is repository-level) made computeSuggestedInterval() return null via the
    // #446 same-day guard *before* adaptWateringInterval ever ran, silently skipping all dormancy
    // handling for this save even though its own gap (against the correct predecessor) is a genuine,
    // 127-day, dormancy-spanning one.
    @Test
    fun `a backdated WATER log spanning dormancy is still excluded even when the newest pair is a stale same-day duplicate`() =
        runTest {
            val octoberTwentyFifth = localDateUtcMillis(2026, 10, 25)
            val marchFirst = localDateUtcMillis(2027, 3, 1)
            val duplicateDay = localDateUtcMillis(2027, 4, 1)
            val dormantPlant = plant(wateringIntervalDays = 7).copy(
                wateringConfidence = 3,
                dormancyStartMonth = 11,
                dormancyEndMonth = 2
            )
            // The globally newest pair: an unrelated same-day duplicate elsewhere in history, giving
            // actualIntervalDays == 0 — nothing to do with the true Oct25 -> Mar1 gap being saved.
            val (vm, wateringAdjustmentRepo) = buildDormancySpanningWaterVm(
                dormantPlant = dormantPlant,
                lastTwoWaterings = listOf(waterLog(loggedAt = duplicateDay), waterLog(loggedAt = duplicateDay)),
                predecessorLoggedAt = octoberTwentyFifth,
                marchFirst = marchFirst
            )

            vm.events.test {
                vm.saveLog()
                awaitItem()
                cancelAndIgnoreRemainingEvents()
            }

            coVerify {
                wateringAdjustmentRepo.addAdjustment(
                    match { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXCLUDED }
                )
            }
            coVerify {
                wateringAdjustmentRepo.addAdjustment(
                    match { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXIT }
                )
            }
            coVerify {
                plantRepo.updatePlant(match { it.wateringConfidence == 2 })
            }
        }

    // #699/#761 (product ADR-0044, Codex review round 3 on #776, P1): the DORMANCY_EXIT idempotency
    // check and its row's triggeredAt must key off the observation date (loggedAt), not wall-clock
    // System.currentTimeMillis() — see .claude/rules/watering-transparency.md's "Follow-up (#654)"
    // note for the precedent this brings AddCareLogViewModel in line with. Backfilling exits for two
    // different winters within the same real-world entry session (both saves happen moments apart in
    // wall-clock time, well within one calendar month) must not let the second, genuinely distinct
    // cycle's decrement be suppressed just because both entries were *typed* around the same time.
    @Test
    fun `backfilling exits for two different winters in one sitting decrements both, not just the first`() = runTest {
        val octTwentyFive2025 = localDateUtcMillis(2025, 10, 25)
        val marchFirst2026 = localDateUtcMillis(2026, 3, 1)
        val octTwentyFive2026 = localDateUtcMillis(2026, 10, 25)
        val marchFirst2027 = localDateUtcMillis(2027, 3, 1)

        val recordedAdjustments = mutableListOf<WateringAdjustment>()
        val statefulAdjustmentRepo: WateringAdjustmentRepository = mockk {
            coEvery { addAdjustment(any()) } coAnswers {
                recordedAdjustments.add(firstArg())
                1L
            }
            coEvery { getByTrigger(1L, WateringAdjustmentTrigger.DORMANCY_EXIT) } coAnswers {
                recordedAdjustments.filter { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXIT }
            }
        }
        val dormantPlant = plant(wateringIntervalDays = 7).copy(
            wateringConfidence = 3,
            dormancyStartMonth = 11,
            dormancyEndMonth = 2
        )
        every { plantRepo.getPlantById(1L) } returns flowOf(dormantPlant)
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns emptyList()
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst2026) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octTwentyFive2025)
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst2027) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octTwentyFive2026)
        coEvery { plantRepo.updatePlant(any()) } just runs

        val vm = AddCareLogViewModel(
            careLogRepo,
            plantRepo,
            plantId = 1L,
            wateringAdjustmentRepository = statefulAdjustmentRepo
        )
        vm.selectedCareType = CareType.WATER

        // Backfill winter A's exit.
        vm.loggedAt = marchFirst2026
        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // Backfill winter B's exit, entered moments later in wall-clock time -- but a genuinely
        // different, later dormancy cycle by its own loggedAt.
        vm.loggedAt = marchFirst2027
        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(2, recordedAdjustments.count { it.trigger == WateringAdjustmentTrigger.DORMANCY_EXIT })
    }

    // #699/#761 (product ADR-0044, Codex review round 3 on #776, P2): shouldSuppressWateringFeedback()
    // no longer gates on !isEditMode -- covers both scenarios Codex named.

    @Test
    fun `editing an existing WATER log's date into a dormancy-spanning position suppresses its feedback`() = runTest {
        val octoberTwentyFifth = localDateUtcMillis(2026, 10, 25)
        val marchFirst = localDateUtcMillis(2027, 3, 1)
        val originalNonDormantDate = localDateUtcMillis(2026, 6, 1)
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.WATER,
            loggedAt = originalNonDormantDate,
            wateringFeedback = WateringFeedback.TOO_SOON
        )
        val dormantPlant = plant(wateringIntervalDays = 7).copy(
            wateringConfidence = 3,
            dormancyStartMonth = 11,
            dormancyEndMonth = 2
        )
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        every { plantRepo.getPlantById(1L) } returns flowOf(dormantPlant)
        coEvery { careLogRepo.addLog(any()) } returns 99L
        // The edit's own predecessor, excluding the row being edited itself (id 99L) -- P2's excludeId fix.
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst, 99L) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octoberTwentyFifth)

        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)
        advanceUntilIdle()
        // selectedFeedback is loaded from existingLog (TOO_SOON); the user only moves the date picker.
        vm.loggedAt = marchFirst

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            careLogRepo.addLog(match { it.id == 99L && it.wateringFeedback == null })
        }
    }

    @Test
    fun `re-saving an already-dormant WATER log that carries stale feedback strips it`() = runTest {
        val octoberTwentyFifth = localDateUtcMillis(2026, 10, 25)
        val marchFirst = localDateUtcMillis(2027, 3, 1)
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.WATER,
            loggedAt = marchFirst,
            wateringFeedback = WateringFeedback.TOO_SOON
        )
        val dormantPlant = plant(wateringIntervalDays = 7).copy(
            wateringConfidence = 3,
            dormancyStartMonth = 11,
            dormancyEndMonth = 2
        )
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        every { plantRepo.getPlantById(1L) } returns flowOf(dormantPlant)
        coEvery { careLogRepo.addLog(any()) } returns 99L
        coEvery { careLogRepo.getLastWateringBefore(1L, marchFirst, 99L) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = octoberTwentyFifth)

        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)
        advanceUntilIdle()
        // No changes at all -- a plain re-save, e.g. the user only edited the notes field.

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            careLogRepo.addLog(match { it.id == 99L && it.wateringFeedback == null })
        }
    }

    @Test
    fun `save FERTILIZE log emits Saved with null interval regardless of feedback`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        coEvery { careLogRepo.addLog(any()) } returns 1L
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.FERTILIZE
        vm.selectedFeedback = WateringFeedback.TOO_SOON

        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            assertNull(event.suggestedWateringInterval)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `saving a new WATER log schedules the post-watering reminder`() = runTest {
        val scheduled = mutableListOf<Long>()
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = null))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns emptyList()
        val vm = AddCareLogViewModel(
            careLogRepo,
            plantRepo,
            plantId = 1L,
            onWaterLogged = { scheduled.add(it) }
        )
        vm.selectedCareType = CareType.WATER
        vm.loggedAt = now

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf(now), scheduled)
    }

    @Test
    fun `edit mode loads existing log fields and isEditMode is true`() = runTest {
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.FERTILIZE,
            loggedAt = now,
            notes = "Monthly feed",
            wateringFeedback = null
        )
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        coEvery { careLogRepo.addLog(any()) } returns 99L
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)

        advanceUntilIdle()
        assertTrue(vm.isEditMode)
        assertEquals(CareType.FERTILIZE, vm.selectedCareType)
        assertEquals("Monthly feed", vm.notes)
    }

    @Test
    fun `edit mode WATER save skips suggestion and post-watering reminder`() = runTest {
        val scheduled = mutableListOf<Long>()
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.WATER,
            loggedAt = now,
            wateringFeedback = WateringFeedback.TOO_SOON
        )
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        coEvery { careLogRepo.addLog(any()) } returns 99L
        val vm = AddCareLogViewModel(
            careLogRepo,
            plantRepo,
            plantId = 1L,
            careLogId = 99L,
            onWaterLogged = { scheduled.add(it) }
        )
        advanceUntilIdle()

        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            assertNull(event.suggestedWateringInterval)
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `FERTILIZE with LIQUID type auto-creates paired WATER log`() = runTest {
        val scheduled = mutableListOf<Long>()
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(useLiquidFertilizer = true))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        val vm = AddCareLogViewModel(
            careLogRepo,
            plantRepo,
            plantId = 1L,
            onWaterLogged = { scheduled.add(it) }
        )
        vm.selectedCareType = CareType.FERTILIZE
        vm.selectedFertilizerType = FertilizerType.LIQUID

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 2) { careLogRepo.addLog(any()) }
        // #586: the paired watering carries no reason — the user fertilized and the watering came
        // along with it (product ADR-0008), so they were never asked why they watered.
        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.WATER && it.wateringFeedback == null })
        }
        assertEquals(1, scheduled.size)
    }

    @Test
    fun `FERTILIZE with SOLID type does not create paired WATER log`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        coEvery { careLogRepo.addLog(any()) } returns 1L
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.FERTILIZE
        vm.selectedFertilizerType = FertilizerType.SOLID

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `new mode init defaults selectedFertilizerType to LIQUID when plant useLiquidFertilizer is true`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(useLiquidFertilizer = true))
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)

        assertEquals(FertilizerType.LIQUID, vm.selectedFertilizerType)
    }

    @Test
    fun `new mode init defaults selectedFertilizerType to UNSPECIFIED when plant useLiquidFertilizer is false`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(useLiquidFertilizer = false))
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)

        assertEquals(FertilizerType.UNSPECIFIED, vm.selectedFertilizerType)
    }

    @Test
    fun `save new WATER log clears wateringDueDateOverride when it is set`() = runTest {
        val sevenDaysAgo = now - 7L * 24 * 60 * 60 * 1000
        val plantWithOverride = plant(wateringIntervalDays = 7)
            .copy(wateringDueDateOverride = now + 3L * 24 * 60 * 60 * 1000)
        every { plantRepo.getPlantById(1L) } returns flowOf(plantWithOverride)
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = now),
            waterLog(loggedAt = sevenDaysAgo)
        )
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.wateringDueDateOverride == null }) }
    }

    // #571: a new REPOT log resets wateringConfidence and starts the freeze window.
    @Test
    fun `save new REPOT log resets confidence and starts the freeze window`() = runTest {
        val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7).copy(wateringConfidence = 3))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(
            careLogRepo,
            plantRepo,
            plantId = 1L,
            wateringAdjustmentRepository = wateringAdjustmentRepo
        )
        vm.selectedCareType = CareType.REPOT

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) {
            plantRepo.updatePlant(
                match { it.wateringConfidence == 0 && it.wateringResetAt != null && it.wateringFreezeUntil != null }
            )
        }
        coVerify { wateringAdjustmentRepo.addAdjustment(any()) }
    }

    // #571 AC3 regression: editing a past REPOT log's date/type must never re-trigger the reset —
    // it's written once at original log-creation time, not derived from querying REPOT history live.
    @Test
    fun `editing an existing REPOT log's date does not re-trigger the reset`() = runTest {
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.REPOT,
            loggedAt = now - 10L * 24 * 60 * 60 * 1000
        )
        val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk(relaxed = true)
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        coEvery { careLogRepo.addLog(any()) } returns 99L
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7).copy(wateringConfidence = 3))
        val vm = AddCareLogViewModel(
            careLogRepo,
            plantRepo,
            plantId = 1L,
            careLogId = 99L,
            wateringAdjustmentRepository = wateringAdjustmentRepo
        )
        advanceUntilIdle()
        vm.loggedAt = now

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
    }

    @Test
    fun `save PHOTO log with photoUri updates plant coverPhotoUri`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.PHOTO
        vm.photoUri = "content://photo.jpg"

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.coverPhotoUri == "content://photo.jpg" }) }
    }

    @Test
    fun `save WATER log with photo does not update coverPhotoUri`() = runTest {
        val sevenDaysAgo = now - 7L * 24 * 60 * 60 * 1000
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = now),
            waterLog(loggedAt = sevenDaysAgo)
        )
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.JUST_RIGHT
        vm.photoUri = "content://photo.jpg"

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        // A WATER log may still trigger a confidence-only updatePlant() (unconditional adaptive
        // model, #655) — this only asserts that call never carries the WATER log's photo through
        // to coverPhotoUri, which is PHOTO-log-only behavior.
        coVerify(exactly = 0) { plantRepo.updatePlant(match { it.coverPhotoUri == "content://photo.jpg" }) }
    }

    @Test
    fun `edit mode save PHOTO log with photoUri updates plant coverPhotoUri`() = runTest {
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.PHOTO,
            loggedAt = now,
            photoUri = "content://photo.jpg"
        )
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        coEvery { careLogRepo.addLog(any()) } returns 99L
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)
        advanceUntilIdle()

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.coverPhotoUri == "content://photo.jpg" }) }
    }

    @Test
    fun `edit mode save preserves customReminderId when editing a CUSTOM log`() = runTest {
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.CUSTOM,
            loggedAt = now,
            notes = "Original notes",
            customReminderId = 42L
        )
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        coEvery { careLogRepo.addLog(any()) } returns 99L
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)
        advanceUntilIdle()
        vm.notes = "Edited notes"

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            careLogRepo.addLog(
                match { it.customReminderId == 42L && it.notes == "Edited notes" }
            )
        }
    }

    // Same-day duplicate rejection (#509)

    @Test
    fun `duplicate WATER log shows inline error without saving or scheduling reminder`() = runTest {
        val scheduled = mutableListOf<Long>()
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7))
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), null) } returns true
        val vm = AddCareLogViewModel(
            careLogRepo,
            plantRepo,
            plantId = 1L,
            onWaterLogged = { scheduled.add(it) }
        )
        vm.selectedCareType = CareType.WATER

        vm.events.test {
            vm.saveLog()
            expectNoEvents()
        }

        assertEquals(R.string.care_log_error_already_watered, vm.duplicateLogError)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `save FERTILIZE log already logged today shows inline error and does not save`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.FERTILIZE, any(), null) } returns true
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.FERTILIZE

        vm.events.test {
            vm.saveLog()
            expectNoEvents()
        }

        assertEquals(R.string.care_log_error_already_fertilized, vm.duplicateLogError)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `save WATER log on a different day than an existing same-day log is accepted`() = runTest {
        val sevenDaysAgo = now - 7L * 24 * 60 * 60 * 1000
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = now),
            waterLog(loggedAt = sevenDaysAgo)
        )
        coEvery { plantRepo.updatePlant(any()) } just runs
        // hasLogOfTypeOnDay defaults to false for the queried day in setup() — simulates a day
        // with no existing WATER log even though other days have one.
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER

        vm.events.test {
            vm.saveLog()
            val event = awaitItem()
            assertTrue(event is AddCareLogViewModel.Event.Saved)
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(vm.duplicateLogError)
    }

    @Test
    fun `edit mode re-saving the same WATER log on the same day excludes its own id and succeeds`() = runTest {
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.WATER,
            loggedAt = now,
            wateringFeedback = WateringFeedback.JUST_RIGHT
        )
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        coEvery { careLogRepo.addLog(any()) } returns 99L
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), 99L) } returns false
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)
        advanceUntilIdle()

        vm.events.test {
            vm.saveLog()
            val event = awaitItem()
            assertTrue(event is AddCareLogViewModel.Event.Saved)
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(vm.duplicateLogError)
        coVerify { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), 99L) }
    }

    @Test
    fun `edit mode moving a WATER log onto a day with another WATER log is rejected`() = runTest {
        val existingLog = CareLog(
            id = 99L,
            plantId = 1L,
            careType = CareType.WATER,
            loggedAt = now,
            wateringFeedback = WateringFeedback.JUST_RIGHT
        )
        coEvery { careLogRepo.getLogById(99L) } returns existingLog
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), 99L) } returns true
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)
        advanceUntilIdle()

        vm.events.test {
            vm.saveLog()
            expectNoEvents()
        }

        assertEquals(R.string.care_log_error_already_watered, vm.duplicateLogError)
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `FERTILIZE with LIQUID type already watered today still saves FERTILIZE but suppresses paired WATER`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.FERTILIZE, any(), null) } returns false
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), null) } returns true
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.FERTILIZE
        vm.selectedFertilizerType = FertilizerType.LIQUID

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { careLogRepo.addLog(any()) }
        coVerify(exactly = 0) { careLogRepo.addLog(match { it.careType == CareType.WATER }) }
    }

    @Test
    fun `clearDuplicateLogError resets the error to null`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7))
        coEvery { careLogRepo.hasLogOfTypeOnDay(1L, CareType.WATER, any(), null) } returns true
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
        vm.selectedCareType = CareType.WATER
        vm.events.test {
            vm.saveLog()
            expectNoEvents()
        }
        assertEquals(R.string.care_log_error_already_watered, vm.duplicateLogError)

        vm.clearDuplicateLogError()

        assertNull(vm.duplicateLogError)
    }

    // Seasonal de-seasonalization of the observed gap (#569, product ADR-0026, #578 follow-up)

    @Test
    fun `save WATER log de-seasonalizes the observed gap for a non-pinned plant`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val peakDay = localDateUtcMillis(2023, 1, 5)
        val twentyDaysBeforePeak = peakDay - 20L * 24 * 60 * 60 * 1000
        val seasonalDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(emptyPreferences())
        }
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 10))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = peakDay),
            waterLog(loggedAt = twentyDaysBeforePeak)
        )
        coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, dataStore = seasonalDataStore)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.JUST_RIGHT
        vm.loggedAt = peakDay

        var suggestedInterval: Int? = null
        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            suggestedInterval = event.suggestedWateringInterval
            cancelAndIgnoreRemainingEvents()
        }

        // Peak day (Jan 5, northern): season(peakDay) = 1 + 0.35 = 1.35, so the observed 20-day gap
        // de-seasonalizes to round(20 / 1.35) = 15 before feeding the adaptive model.
        val deseasonalizedObserved = SeasonalWatering.deseasonalizeToDays(
            20,
            LocalDate.of(2023, 1, 5),
            SeasonalAmplitude.STANDARD.value,
            Hemisphere.NORTHERN
        )
        val expected = CareSchedule.computeAdaptiveInterval(
            feedback = WateringFeedback.JUST_RIGHT,
            observedIntervalDays = deseasonalizedObserved,
            currentBaseIntervalDays = 10,
            currentConfidence = null,
            recentFeedback = emptyList()
        )
        assertEquals(expected.intervalDays.takeIf { it != 10 }, suggestedInterval)
    }

    @Test
    fun `save WATER log skips de-seasonalization for a pinned plant`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val peakDay = localDateUtcMillis(2023, 1, 5)
        val twentyDaysBeforePeak = peakDay - 20L * 24 * 60 * 60 * 1000
        val seasonalDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(emptyPreferences())
        }
        val pinnedPlant = plant(wateringIntervalDays = 10).copy(pinIntervalToBase = true)
        every { plantRepo.getPlantById(1L) } returns flowOf(pinnedPlant)
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = peakDay),
            waterLog(loggedAt = twentyDaysBeforePeak)
        )
        coEvery { careLogRepo.getRecentWaterings(1L, limit = 3) } returns emptyList()
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, dataStore = seasonalDataStore)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.JUST_RIGHT
        vm.loggedAt = peakDay

        var suggestedInterval: Int? = null
        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            suggestedInterval = event.suggestedWateringInterval
            cancelAndIgnoreRemainingEvents()
        }

        // Pinned: the raw 20-day gap is used unchanged, not the season(peakDay)-corrected 15.
        val expected = CareSchedule.computeAdaptiveInterval(
            feedback = WateringFeedback.JUST_RIGHT,
            observedIntervalDays = 20,
            currentBaseIntervalDays = 10,
            currentConfidence = null,
            recentFeedback = emptyList()
        )
        assertEquals(expected.intervalDays.takeIf { it != 10 }, suggestedInterval)
    }

    // #620 round 2: computeSuggestedInterval() must gate on the effective-space value, not the raw
    // base-space suggestion, or a pure unit-mismatch artifact reaches Event.Saved ungated.
    @Test
    fun `save WATER log suppresses a suggestion whose base moves but today's rounded effective value doesn't`() =
        runTest {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val peakDay = localDateUtcMillis(2023, 1, 5)
            val eightDaysBeforePeak = peakDay - 8L * 24 * 60 * 60 * 1000
            val seasonalDataStore: DataStore<Preferences> = mockk {
                every { data } returns flowOf(emptyPreferences())
            }
            // #716 regression, corrected from the pre-fix version of this test (which compared against
            // the stale wateringIntervalDays literal directly — the exact bug #716 fixes, see
            // .claude/rules/adaptive-watering-cluster.md). A self-consistent plant: base = 5.0,
            // literal = 7 = round(5.0 * season(peakDay)=1.35) — the literal already agrees with what
            // the live base would show today, so this isn't stale. The observed 8-day gap de-seasonalizes
            // to round(8 / 1.35) = 6; TOO_LATE (mult 0.82) targets 6*0.82 = 4.92; confidence-0 gain 0.60
            // lands the raw base-space suggestion at 5.0 + 0.60*(4.92-5.0) = 4.952 — a genuine, if small,
            // base movement. But round(4.952 * 1.35) = 7 == round(5.0 * 1.35) = 7: today's *displayed*
            // effective value doesn't move, so per technical ADR-0027/#716 the observation must persist
            // silently (base updates immediately) with no dialog and no suggestion surfaced.
            val monstera = plant(wateringIntervalDays = 7).copy(wateringBaseIntervalDays = 5.0)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { careLogRepo.addLog(any()) } returns 1L
            coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
                waterLog(loggedAt = peakDay),
                waterLog(loggedAt = eightDaysBeforePeak)
            )
            coEvery { plantRepo.updatePlant(any()) } just runs
            val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, dataStore = seasonalDataStore)
            vm.selectedCareType = CareType.WATER
            vm.selectedFeedback = WateringFeedback.TOO_LATE
            vm.loggedAt = peakDay

            vm.events.test {
                vm.saveLog()
                val event = awaitItem() as AddCareLogViewModel.Event.Saved
                assertNull(event.suggestedWateringInterval)
                cancelAndIgnoreRemainingEvents()
            }

            // technical ADR-0027: silently persists the moved base even though nothing is surfaced.
            coVerify {
                plantRepo.updatePlant(
                    match { it.wateringBaseIntervalDays != null && Math.abs(it.wateringBaseIntervalDays!! - 4.952) < 1e-9 }
                )
            }
        }

    /**
     * #716 acceptance criterion 5: this VM's independent copy of the gate must reach the same
     * conclusion as [com.yapt.planttracker.domain.usecase.QuickLogUseCase.computeSuggestion] for the
     * exact same worked example (see `QuickLogUseCaseSeasonalTest`'s identically-named/numbered
     * scenario) — a stale `wateringIntervalDays = 7` literal, a real base of 8.8, watered 1 day early
     * on Sep 13 (season 0.866) with no feedback. Both gates must independently suppress the suggestion.
     */
    @Test
    fun `save WATER log agrees with QuickLogUseCase on the #716 worked example`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val sep13 = localDateUtcMillis(2023, 9, 13)
        val sevenDaysBeforeSep13 = sep13 - 7L * 24 * 60 * 60 * 1000
        val seasonalDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(emptyPreferences())
        }
        val monstera = plant(wateringIntervalDays = 7).copy(wateringBaseIntervalDays = 8.8)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = sep13),
            waterLog(loggedAt = sevenDaysBeforeSep13)
        )
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, dataStore = seasonalDataStore)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = null
        vm.loggedAt = sep13

        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            assertNull(event.suggestedWateringInterval)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * #716 review round 1 regression, direction 1 (spurious dialog), mirroring
     * `QuickLogUseCaseSeasonalTest`'s identically-numbered test — this screen's own date picker can
     * back-date [AddCareLogViewModel.loggedAt] just as freely as Plant Detail's #654 quick-water picker
     * can. STANDARD amplitude, northern hemisphere; logged date Jan 1 (`season = 1.349`), real today
     * Sep 21 (`season = 0.912`). `computeAdaptiveInterval(TOO_LATE, observed=8, base=5.0)` moves the
     * base to 5.936: at the logged date `round(5.0*1.349)=7 -> round(5.936*1.349)=8` (a real jump, what
     * the pre-fix code — evaluating both sides at `loggedAt` — would have surfaced as a dialog); at
     * today `round(5.0*0.912)=5 -> round(5.936*0.912)=5` (nothing the user would actually see change).
     * `computeSuggestedInterval` is called directly (now `internal`, mirroring `QuickLogUseCase
     * .computeSuggestion`'s identical widening) so [displayNow] can be pinned independently of
     * [AddCareLogViewModel.loggedAt] without fighting the real device clock.
     */
    @Test
    fun `computeSuggestedInterval uses displayNow not backdated loggedAt - direction 1, spurious dialog (#716 rr1)`() =
        runTest {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val jan1 = localDateUtcMillis(2023, 1, 1)
            val sep21 = localDateUtcMillis(2023, 9, 21)
            val elevenDaysBeforeJan1 = jan1 - 11L * 24 * 60 * 60 * 1000
            val seasonalDataStore: DataStore<Preferences> = mockk {
                every { data } returns flowOf(emptyPreferences())
            }
            val monstera = plant(wateringIntervalDays = 7).copy(wateringBaseIntervalDays = 5.0)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
                waterLog(loggedAt = jan1),
                waterLog(loggedAt = elevenDaysBeforeJan1)
            )
            coEvery { plantRepo.updatePlant(any()) } just runs
            val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, dataStore = seasonalDataStore)
            vm.selectedCareType = CareType.WATER
            vm.selectedFeedback = WateringFeedback.TOO_LATE
            vm.loggedAt = jan1

            val suggestion = vm.computeSuggestedInterval(displayNow = sep21)

            assertEquals(null, suggestion)
        }

    /**
     * #716 review round 1 regression, direction 2 (silent bypass — the worse direction): the mirror
     * image of the test above, same gap/feedback, starting one rounding band higher (base 5.6 instead
     * of 5.0). `computeAdaptiveInterval(TOO_LATE, observed=8, base=5.6)` moves the base to 6.176. At the
     * logged date: `round(5.6*1.349)=8 -> round(6.176*1.349)=8` — unchanged, so the pre-fix code
     * (evaluating both sides at `loggedAt`) would have silently persisted the moved base with no
     * suggestion surfaced at all, bypassing `askBeforeChangingIntervals` entirely. At today:
     * `round(5.6*0.912)=5 -> round(6.176*0.912)=6` — the number the user actually reads on screen right
     * now really does move. The fixed gate must surface a real suggestion and must leave
     * `wateringBaseIntervalDays` untouched at its original 5.6 (any write is deferred to the explicit
     * apply path).
     */
    @Test
    fun `computeSuggestedInterval uses displayNow not backdated loggedAt - direction 2, silent bypass (#716 rr1)`() =
        runTest {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val jan1 = localDateUtcMillis(2023, 1, 1)
            val sep21 = localDateUtcMillis(2023, 9, 21)
            val elevenDaysBeforeJan1 = jan1 - 11L * 24 * 60 * 60 * 1000
            val seasonalDataStore: DataStore<Preferences> = mockk {
                every { data } returns flowOf(emptyPreferences())
            }
            val monstera = plant(wateringIntervalDays = 8).copy(wateringBaseIntervalDays = 5.6)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
                waterLog(loggedAt = jan1),
                waterLog(loggedAt = elevenDaysBeforeJan1)
            )
            coEvery { plantRepo.updatePlant(any()) } just runs
            val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, dataStore = seasonalDataStore)
            vm.selectedCareType = CareType.WATER
            vm.selectedFeedback = WateringFeedback.TOO_LATE
            vm.loggedAt = jan1

            val suggestion = vm.computeSuggestedInterval(displayNow = sep21)

            assertTrue(suggestion != null)
            coVerify {
                plantRepo.updatePlant(match { it.wateringBaseIntervalDays == 5.6 })
            }
        }

    // Sanity check for the same fix: a raw suggestion whose effective-space value genuinely differs
    // from current must still surface, seasonal multiplier or not.
    @Test
    fun `save WATER log still surfaces a suggestion whose effective-space value genuinely differs from current`() = runTest {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val peakDay = localDateUtcMillis(2023, 1, 5)
        val oneDayBeforePeak = peakDay - 1L * 24 * 60 * 60 * 1000
        val seasonalDataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(emptyPreferences())
        }
        // The observed 1-day gap de-seasonalizes to round(1 / 1.35) = 1; the model's confidence-0 gain
        // (0.60) pulls the base from 7 toward target=1 down to 3.4, clamped at the ±40% floor
        // (7*0.6=4.2) and rounded to 4 — the raw base-space suggestion. round(4 * 1.35) = 5 != 7 ==
        // current — a genuine effective-space change, so the suggestion must still surface.
        every { plantRepo.getPlantById(1L) } returns flowOf(plant(wateringIntervalDays = 7))
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(1L) } returns listOf(
            waterLog(loggedAt = peakDay),
            waterLog(loggedAt = oneDayBeforePeak)
        )
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, dataStore = seasonalDataStore)
        vm.selectedCareType = CareType.WATER
        vm.selectedFeedback = WateringFeedback.JUST_RIGHT
        vm.loggedAt = peakDay

        vm.events.test {
            vm.saveLog()
            val event = awaitItem() as AddCareLogViewModel.Event.Saved
            assertEquals(4, event.suggestedWateringInterval)
            assertEquals(4.2, event.suggestedWateringBaseInterval!!, 1e-9)
            cancelAndIgnoreRemainingEvents()
        }
    }
}

private fun localDateUtcMillis(year: Int, month: Int, day: Int): Long {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
    cal.clear()
    cal.set(year, month - 1, day, 12, 0, 0)
    return cal.timeInMillis
}
