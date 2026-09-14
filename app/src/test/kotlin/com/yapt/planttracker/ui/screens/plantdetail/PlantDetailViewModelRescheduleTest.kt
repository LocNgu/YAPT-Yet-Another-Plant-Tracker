package com.yapt.planttracker.ui.screens.plantdetail

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.RescheduleReason
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Reschedule watering (reason prompt, then Today/+N days/custom date) coverage for
 * [PlantDetailViewModel] (#508 product ADR-0029, reshaped by #586 product ADR-0030), split out of
 * `PlantDetailViewModelTest` to keep that file under Detekt's `LargeClass` threshold — mirrors
 * `PlantDetailViewModelSeasonalTest`'s precedent.
 */
class PlantDetailViewModelRescheduleTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val dataStore: DataStore<Preferences> = mockk {
        every { data } returns flowOf(emptyPreferences())
    }
    private val quickLogUseCase: QuickLogUseCase = mockk()
    private val customReminderRepo: CustomReminderRepository = mockk()
    private val plantIssueRepo: PlantIssueRepository = mockk()
    private val database: PlantDatabase = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk {
        every { getRecentForPlant(any(), any()) } returns flowOf(emptyList())
    }

    private fun plant(id: Long = 1L, name: String = "Monstera") = Plant(
        id = id,
        name = name,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun makeVm(plantId: Long = 1L, careLogs: List<CareLog> = emptyList()): PlantDetailViewModel {
        every { careLogRepo.getLogsForPlant(plantId) } returns flowOf(careLogs)
        every { careLogRepo.getPhotoLogsForPlant(plantId) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(plantId) } returns flowOf(emptyList())
        every { customReminderRepo.getRemindersForPlant(plantId) } returns flowOf(emptyList())
        every { plantIssueRepo.getActiveIssuesForPlant(plantId) } returns flowOf(emptyList())
        return PlantDetailViewModel(
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            plantId,
            dataStore,
            quickLogUseCase,
            customReminderRepo,
            plantIssueRepo,
            database,
            wateringAdjustmentRepo
        )
    }

    @Test
    fun `requestReschedule opens the reason prompt, not the date dialog`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val vm = makeVm()

        vm.showRescheduleReasonSheet.test {
            assertFalse(awaitItem())
            vm.requestReschedule()
            assertTrue(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        // #586: the date dialog only opens once a reason has been given.
        assertFalse(vm.showRescheduleDialog.value)
    }

    @Test
    fun `dismissRescheduleDialog sets showRescheduleDialog to false`() = runTest {
        val monstera = plant()
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val vm = makeVm()

        vm.requestReschedule()
        vm.chooseRescheduleReason(RescheduleReason.CANT_RIGHT_NOW)
        vm.showRescheduleDialog.test {
            assertTrue(awaitItem())
            vm.dismissRescheduleDialog()
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `dismissRescheduleReasonSheet abandons the reschedule without writing anything`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val vm = makeVm()

        vm.requestReschedule()
        vm.dismissRescheduleReasonSheet()

        assertFalse(vm.showRescheduleReasonSheet.value)
        assertFalse(vm.showRescheduleDialog.value)
        assertNull(vm.rescheduleReason.value)
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
    }

    @Test
    fun `chooseRescheduleReason CANT_RIGHT_NOW opens the date dialog with no suggested deferral`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val vm = makeVm()

        vm.requestReschedule()
        vm.chooseRescheduleReason(RescheduleReason.CANT_RIGHT_NOW)

        assertFalse(vm.showRescheduleReasonSheet.value)
        assertTrue(vm.showRescheduleDialog.value)
        assertNull(vm.rescheduleSuggestedDays.value)
        coVerify(exactly = 0) { quickLogUseCase.suggestedStillMoistDeferralDays(any()) }
    }

    @Test
    fun `chooseRescheduleReason SOIL_STILL_MOIST opens the date dialog on the derived deferral`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.suggestedStillMoistDeferralDays(monstera) } returns 4
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.requestReschedule()
            vm.chooseRescheduleReason(RescheduleReason.SOIL_STILL_MOIST)
            cancelAndIgnoreRemainingEvents()
        }

        assertTrue(vm.showRescheduleDialog.value)
        assertEquals(4, vm.rescheduleSuggestedDays.value)
    }

    // ---- Reschedule watering (#508, product ADR-0029) ----

    @Test
    fun `confirmRescheduleToday sets wateringDueDateOverride to now and closes the dialog`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()
        vm.requestReschedule()

        val before = System.currentTimeMillis()
        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.confirmRescheduleToday()
            cancelAndIgnoreRemainingEvents()
        }
        val after = System.currentTimeMillis()

        assertFalse(vm.showRescheduleDialog.value)
        coVerify {
            plantRepo.updatePlant(
                match {
                    val override = it.wateringDueDateOverride
                    it.wateringIntervalDays == 7 && override != null && override in before..after
                }
            )
        }
    }

    /**
     * A plant last watered 20 days ago on a 7-day interval is clearly overdue, so
     * `maxOf(nextWateringDueAt, now)` collapses to `now` — the resulting override is `now + N days`,
     * asserted against a `[before, after]` wall-clock window bracketing the call rather than an exact
     * timestamp, since both this test and the ViewModel read `System.currentTimeMillis()` independently.
     */
    @Test
    fun `confirmRescheduleRelativeDays(1) pushes the due date 1 day from the effective due date`() = runTest {
        val now = System.currentTimeMillis()
        val monstera = plant().copy(wateringIntervalDays = 7)
        val overdueLog = CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = now - TimeUnit.DAYS.toMillis(20))
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm(careLogs = listOf(overdueLog))

        val before = System.currentTimeMillis()
        vm.careStatus.test {
            assertTrue(awaitItem()!!.isOverdue)
            vm.confirmRescheduleRelativeDays(1)
            cancelAndIgnoreRemainingEvents()
        }
        val after = System.currentTimeMillis()

        val oneDayMs = TimeUnit.DAYS.toMillis(1)
        coVerify {
            plantRepo.updatePlant(
                match {
                    val override = it.wateringDueDateOverride
                    it.wateringIntervalDays == 7 &&
                        override != null &&
                        override in (before + oneDayMs)..(after + oneDayMs)
                }
            )
        }
    }

    @Test
    fun `confirmRescheduleRelativeDays(3) pushes the due date 3 days from the effective due date`() = runTest {
        val now = System.currentTimeMillis()
        val monstera = plant().copy(wateringIntervalDays = 7)
        val overdueLog = CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = now - TimeUnit.DAYS.toMillis(20))
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm(careLogs = listOf(overdueLog))

        val before = System.currentTimeMillis()
        vm.careStatus.test {
            assertTrue(awaitItem()!!.isOverdue)
            vm.confirmRescheduleRelativeDays(3)
            cancelAndIgnoreRemainingEvents()
        }
        val after = System.currentTimeMillis()

        val threeDaysMs = TimeUnit.DAYS.toMillis(3)
        coVerify {
            plantRepo.updatePlant(
                match {
                    val override = it.wateringDueDateOverride
                    it.wateringIntervalDays == 7 &&
                        override != null &&
                        override in (before + threeDaysMs)..(after + threeDaysMs)
                }
            )
        }
    }

    @Test
    fun `confirmRescheduleCustomDate writes the given date verbatim as wateringDueDateOverride`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()
        val customDate = 1_800_000_000_000L

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.confirmRescheduleCustomDate(customDate)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            plantRepo.updatePlant(
                match { it.wateringDueDateOverride == customDate && it.wateringIntervalDays == 7 }
            )
        }
    }

    // ---- Reschedule "(suggested)" option, now-anchored not due-date-anchored (#719) ----

    /**
     * Not-yet-due, holding/lengthening subset — the issue's 7-day/day-6 worked example.
     * `suggestedStillMoistDeferralDays()` is a from-today figure (its own KDoc: "come back when the
     * freshly-lengthened interval says it is due"), so `confirmRescheduleSuggestedDays` must land on
     * `now + suggestedDays` — the date the "(suggested)" label promises — not `due + suggestedDays`,
     * which is what `confirmRescheduleRelativeDays`'s due-date anchor would produce for the same
     * plant and overshoots by exactly `daysUntilDue`.
     *
     * The "(suggested)" row only renders for `RescheduleReason.SOIL_STILL_MOIST` — routed through
     * `chooseRescheduleReason()` so `rescheduleReason.value` is genuinely set, exactly as production
     * reaches this function — so `applyReschedule()`'s `SOIL_STILL_MOIST` branch is what actually
     * runs, calling `QuickLogUseCase.recordStillMoistCheck()` rather than a plain
     * `plantRepository.updatePlant()` (round-1 review fix: the earlier version of this test called
     * `confirmRescheduleSuggestedDays` directly with no reason set, so `applyReschedule()` silently
     * took its `else` branch instead — a path this function never takes in production).
     */
    @Test
    fun `confirmRescheduleSuggestedDays anchors to now, not the due date, for a not-yet-due plant`() = runTest {
        val now = System.currentTimeMillis()
        // pinIntervalToBase = true keeps the effective interval a flat 7 days regardless of today's
        // real-world seasonal amplitude (default STANDARD, nonzero, since #656) — otherwise the due
        // date this test depends on could drift with whatever day CI happens to run on.
        val monstera = plant().copy(wateringIntervalDays = 7, pinIntervalToBase = true)
        val recentLog = CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = now - TimeUnit.DAYS.toMillis(6))
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.suggestedStillMoistDeferralDays(monstera) } returns 1
        coEvery { quickLogUseCase.recordStillMoistCheck(monstera, any()) } returns true
        val vm = makeVm(careLogs = listOf(recentLog))

        val before = System.currentTimeMillis()
        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.careStatus.test {
                assertFalse(awaitItem()!!.isOverdue)
                vm.requestReschedule()
                vm.chooseRescheduleReason(RescheduleReason.SOIL_STILL_MOIST)
                vm.confirmRescheduleSuggestedDays(1)
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
        val after = System.currentTimeMillis()

        val oneDayMs = TimeUnit.DAYS.toMillis(1)
        coVerify {
            quickLogUseCase.recordStillMoistCheck(
                monstera,
                match { it in (before + oneDayMs)..(after + oneDayMs) }
            )
        }
    }

    /**
     * Shortening subset — the issue's 14-day/day-8 worked example (`1.25 × observedGap < base`,
     * confidence 3, `target = 10`, model base `14 + 0.28 × (10 − 14) = 12.88 → 13`, `suggested =
     * 13 − 8 = 5`). This test only pins what `confirmRescheduleSuggestedDays` itself writes —
     * `now + suggestedDays`, the corrected, earlier value — via the mocked `QuickLogUseCase
     * .recordStillMoistCheck()` call, which never runs `CareSchedule.computeWateringDue()`. It
     * deliberately does **not** assert the resulting *effective* due date:
     * `computeWateringDue()`'s `maxOf(computedNextDueAt, override)` clamp means this earlier
     * override is provisionally inert (the visible due date does not move) until #720 decides
     * whether an override may pull a due date earlier at all — see
     * `.claude/rules/adaptive-watering-cluster.md`. That downstream behaviour belongs to #720's own
     * test coverage, not this one.
     *
     * Routed through `chooseRescheduleReason(SOIL_STILL_MOIST)` for the same reason as the
     * lengthening-subset test above — `confirmRescheduleSuggestedDays` only ever runs with that
     * reason set in production, so the test must exercise `recordStillMoistCheck()`, not a plain
     * `plantRepository.updatePlant()` (round-1 review fix).
     */
    @Test
    fun `confirmRescheduleSuggestedDays writes now plus suggestedDays even in the shortening subset`() = runTest {
        val now = System.currentTimeMillis()
        // pinIntervalToBase = true, same rationale as the lengthening-subset test above — without it
        // the due date this test's assertion window depends on (last watered + 14 days) would drift
        // with today's real-world seasonal amplitude instead of staying a flat 14 days.
        val monstera = plant().copy(wateringIntervalDays = 14, pinIntervalToBase = true)
        val eightDaysAgoLog = CareLog(
            plantId = 1L,
            careType = CareType.WATER,
            loggedAt = now - TimeUnit.DAYS.toMillis(8)
        )
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.suggestedStillMoistDeferralDays(monstera) } returns 5
        coEvery { quickLogUseCase.recordStillMoistCheck(monstera, any()) } returns true
        val vm = makeVm(careLogs = listOf(eightDaysAgoLog))

        val before = System.currentTimeMillis()
        vm.plant.test {
            assertEquals(monstera, awaitItem())
            // careStatus needs an active collector for the assertFalse(isOverdue) assertion below:
            // it's WhileSubscribed, so .value stays null without a subscriber. The collector is for
            // that assertion only — confirmRescheduleSuggestedDays never reads careStatus itself,
            // unlike confirmRescheduleRelativeDays, which is precisely the #719 distinction.
            vm.careStatus.test {
                assertFalse(awaitItem()!!.isOverdue)
                vm.requestReschedule()
                vm.chooseRescheduleReason(RescheduleReason.SOIL_STILL_MOIST)
                vm.confirmRescheduleSuggestedDays(5)
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
        val after = System.currentTimeMillis()

        val fiveDaysMs = TimeUnit.DAYS.toMillis(5)
        coVerify {
            quickLogUseCase.recordStillMoistCheck(
                monstera,
                match { it in (before + fiveDaysMs)..(after + fiveDaysMs) }
            )
        }
    }

    @Test
    fun `reschedule options never touch wateringBaseIntervalDays, wateringConfidence, or watering_adjustments`() =
        runTest {
            val monstera = plant().copy(
                wateringIntervalDays = 7,
                wateringBaseIntervalDays = 7.0,
                wateringConfidence = 3
            )
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { plantRepo.updatePlant(any()) } just runs
            val vm = makeVm()

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                vm.confirmRescheduleToday()
                vm.confirmRescheduleRelativeDays(2)
                vm.confirmRescheduleCustomDate(1_800_000_000_000L)
                cancelAndIgnoreRemainingEvents()
            }

            coVerify(exactly = 3) {
                plantRepo.updatePlant(
                    match { it.wateringBaseIntervalDays == 7.0 && it.wateringConfidence == 3 }
                )
            }
            coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
        }

    @Test
    fun `reschedule options never emit an Event that could feed the interval-suggestion dialog`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.events.test {
                vm.confirmRescheduleToday()
                vm.confirmRescheduleRelativeDays(1)
                vm.confirmRescheduleCustomDate(1_800_000_000_000L)
                expectNoEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        assertNull(vm.suggestedWateringInterval.value)
    }

    // ---- "Soil still moist" reschedule (#586, product ADR-0030) ----

    @Test
    fun `a SOIL_STILL_MOIST reschedule routes through recordStillMoistCheck with the picked date`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        val pickedDate = 1_800_000_000_000L
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.suggestedStillMoistDeferralDays(monstera) } returns 2
        coEvery { quickLogUseCase.recordStillMoistCheck(monstera, pickedDate) } returns true
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.requestReschedule()
            vm.chooseRescheduleReason(RescheduleReason.SOIL_STILL_MOIST)
            vm.quickLogMessage.test {
                vm.confirmRescheduleCustomDate(pickedDate)
                assertEquals(PlantDetailViewModel.QuickLogMessage.StillMoistChecked("Monstera"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { quickLogUseCase.recordStillMoistCheck(monstera, pickedDate) }
        // The plain override write is the *other* branch's job — this one must not also fire it.
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `a SOIL_STILL_MOIST reschedule emits AlreadyCheckedToday when the use case returns false`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        val pickedDate = 1_800_000_000_000L
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.suggestedStillMoistDeferralDays(monstera) } returns 2
        coEvery { quickLogUseCase.recordStillMoistCheck(monstera, pickedDate) } returns false
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.requestReschedule()
            vm.chooseRescheduleReason(RescheduleReason.SOIL_STILL_MOIST)
            vm.quickLogMessage.test {
                vm.confirmRescheduleCustomDate(pickedDate)
                assertEquals(PlantDetailViewModel.QuickLogMessage.AlreadyCheckedToday("Monstera"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * #586 acceptance criterion: reschedule *length* never affects what the model learns. Whatever
     * date the user picks is passed through verbatim as the new due date, and the observation itself
     * is identical — the reason already decided it.
     */
    @Test
    fun `reschedule length is passed through verbatim and never varies the observation`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { quickLogUseCase.suggestedStillMoistDeferralDays(monstera) } returns 2
        coEvery { quickLogUseCase.recordStillMoistCheck(monstera, any()) } returns true
        val vm = makeVm()
        val shortDate = 1_800_000_000_000L
        val longDate = shortDate + TimeUnit.DAYS.toMillis(30)

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            for (date in listOf(shortDate, longDate)) {
                vm.requestReschedule()
                vm.chooseRescheduleReason(RescheduleReason.SOIL_STILL_MOIST)
                vm.confirmRescheduleCustomDate(date)
            }
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 1) { quickLogUseCase.recordStillMoistCheck(monstera, shortDate) }
        coVerify(exactly = 1) { quickLogUseCase.recordStillMoistCheck(monstera, longDate) }
    }

    @Test
    fun `a CANT_RIGHT_NOW reschedule writes only the override and never a CHECK log`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        val pickedDate = 1_800_000_000_000L
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.requestReschedule()
            vm.chooseRescheduleReason(RescheduleReason.CANT_RIGHT_NOW)
            vm.confirmRescheduleCustomDate(pickedDate)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.wateringDueDateOverride == pickedDate }) }
        coVerify(exactly = 0) { quickLogUseCase.recordStillMoistCheck(any(), any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
    }

    // ---- Reschedule delta + revert (#630) ----

    @Test
    fun `revertReschedule clears wateringDueDateOverride and emits RescheduleReverted with the prior value`() =
        runTest {
            val monstera = plant().copy(wateringIntervalDays = 7, wateringDueDateOverride = 1_800_000_000_000L)
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { plantRepo.updatePlant(any()) } just runs
            val vm = makeVm()

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                vm.events.test {
                    vm.revertReschedule()
                    val event = awaitItem() as PlantDetailViewModel.Event.RescheduleReverted
                    assertEquals(1_800_000_000_000L, event.previousOverrideAtMillis)
                    cancelAndIgnoreRemainingEvents()
                }
                cancelAndIgnoreRemainingEvents()
            }

            coVerify { plantRepo.updatePlant(match { it.wateringDueDateOverride == null }) }
        }

    @Test
    fun `revertReschedule never touches interval, base interval, confidence, or watering_adjustments`() =
        runTest {
            val monstera = plant().copy(
                wateringIntervalDays = 7,
                wateringBaseIntervalDays = 7.0,
                wateringConfidence = 3,
                wateringDueDateOverride = 1_800_000_000_000L
            )
            every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
            coEvery { plantRepo.updatePlant(any()) } just runs
            val vm = makeVm()

            vm.plant.test {
                assertEquals(monstera, awaitItem())
                vm.revertReschedule()
                cancelAndIgnoreRemainingEvents()
            }

            coVerify {
                plantRepo.updatePlant(
                    match {
                        it.wateringIntervalDays == 7 &&
                            it.wateringBaseIntervalDays == 7.0 &&
                            it.wateringConfidence == 3
                    }
                )
            }
            coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
        }

    @Test
    fun `revertReschedule is a no-op when there is no active override`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.events.test {
                vm.revertReschedule()
                expectNoEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `undoRevertReschedule restores the prior wateringDueDateOverride as-is`() = runTest {
        val monstera = plant().copy(wateringIntervalDays = 7, wateringDueDateOverride = null)
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } just runs
        val vm = makeVm()

        vm.plant.test {
            assertEquals(monstera, awaitItem())
            vm.undoRevertReschedule(1_800_000_000_000L)
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { plantRepo.updatePlant(match { it.wateringDueDateOverride == 1_800_000_000_000L }) }
    }
}
