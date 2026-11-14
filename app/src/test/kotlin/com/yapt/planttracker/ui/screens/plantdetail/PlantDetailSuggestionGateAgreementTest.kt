package com.yapt.planttracker.ui.screens.plantdetail

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.usecase.AdaptiveWateringObservation
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import com.yapt.planttracker.util.plusCalendarDays
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * #968: the "does the suggestion differ from today's effective interval?" gate must reach the same
 * verdict, with the same numbers, in [AdaptiveWateringObservation.observe] (which decides whether a
 * suggestion exists at all) and in [PlantDetailViewModel.pendingWateringSuggestion] (which rebuilds
 * the dialog from the raw suggestion). Both call `WateringSuggestionGate`; this test fails if either
 * side grows its own copy again and the two drift.
 *
 * Every case is evaluated at the real "today", because `pendingWateringSuggestion` has no injectable
 * clock; `observe()` is handed the same real day as both its observation and display clock.
 */
class PlantDetailSuggestionGateAgreementTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private data class Case(val plant: Plant, val amplitude: String, val feedback: WateringFeedback?, val gapDays: Int)

    private fun plant(base: Double?, interval: Int, confidence: Int?, pinned: Boolean = false) = Plant(
        id = 1L,
        name = "Monstera",
        wateringIntervalDays = interval,
        wateringBaseIntervalDays = base,
        wateringConfidence = confidence,
        pinIntervalToBase = pinned,
        createdAt = 0L,
        updatedAt = 0L
    )

    private val cases: List<Case> = buildList {
        val plants = listOf(
            plant(base = 7.0, interval = 7, confidence = 0),
            plant(base = 8.8, interval = 7, confidence = 3),
            plant(base = 20.0, interval = 20, confidence = 5),
            plant(base = null, interval = 10, confidence = 1),
            plant(base = 6.4, interval = 7, confidence = 0, pinned = true)
        )
        val feedbacks = listOf(WateringFeedback.TOO_SOON, WateringFeedback.TOO_LATE, null)
        for (p in plants) {
            for (amplitude in listOf("STANDARD", "STRONG", "OFF")) {
                for (feedback in feedbacks) {
                    for (gap in listOf(3, 7, 14, 30)) add(Case(p, amplitude, feedback, gap))
                }
            }
        }
    }

    private fun dataStore(amplitude: String): DataStore<Preferences> = mockk {
        every { data } returns flowOf(preferencesOf(SettingsKeys.SEASONAL_AMPLITUDE to amplitude))
    }

    private fun predecessor(case: Case, now: Long) =
        CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = now.plusCalendarDays(-case.gapDays.toLong()))

    private suspend fun observe(case: Case, now: Long): AdaptiveWateringObservation.Suggestion? {
        val plantRepo: PlantRepository = mockk(relaxed = true)
        val careLogRepo: CareLogRepository = mockk {
            coEvery { getLastWateringBefore(1L, now) } returns predecessor(case, now)
            coEvery { getRecentWaterings(any(), limit = any()) } returns emptyList()
            coEvery { getWaterLogTimestampsAscending(any()) } returns emptyList()
        }
        val adjustments: WateringAdjustmentRepository = mockk(relaxed = true)
        return AdaptiveWateringObservation(plantRepo, careLogRepo, dataStore(case.amplitude), adjustments)
            .observe(case.plant, case.feedback, loggedAt = now, displayNow = now)
    }

    private fun makeVm(case: Case): PlantDetailViewModel {
        val plantRepo: PlantRepository = mockk {
            every { getPlantById(1L) } returns flowOf(case.plant)
        }
        val careLogRepo: CareLogRepository = mockk {
            every { getLogsForPlant(1L) } returns flowOf(emptyList())
            every { getPhotoLogsForPlant(1L) } returns flowOf(emptyList())
        }
        val photoRepo: PlantPhotoRepository = mockk {
            every { getPhotosForPlant(1L) } returns flowOf(emptyList())
        }
        val reminderRepo: CustomReminderRepository = mockk {
            every { getRemindersForPlant(1L) } returns flowOf(emptyList())
        }
        val issueRepo: PlantIssueRepository = mockk {
            every { getActiveIssuesForPlant(1L) } returns flowOf(emptyList())
        }
        val adjustmentRepo: WateringAdjustmentRepository = mockk {
            every { getRecentForPlant(any(), any()) } returns flowOf(emptyList())
        }
        val quickLogUseCase: QuickLogUseCase = mockk()
        val database: PlantDatabase = mockk()
        return PlantDetailViewModel(
            plantRepo,
            careLogRepo,
            photoRepo,
            1L,
            dataStore(case.amplitude),
            quickLogUseCase,
            reminderRepo,
            issueRepo,
            database,
            adjustmentRepo,
            dayChange = flowOf(LocalDate.now())
        )
    }

    @Test
    fun `observe and pendingWateringSuggestion agree on the gate and its numbers`() =
        runTest(mainDispatcherRule.testDispatcher) {
            var shown = 0
            var suppressed = 0
            for (case in cases) {
                val now = System.currentTimeMillis()
                val suggestion = observe(case, now)
                // The dialog only ever receives a suggestion observe() returned; to compare the two gates on
                // every case, feed the model's raw result back in even when observe() suppressed it.
                val raw = suggestion ?: rawModelResultFor(case, now) ?: continue
                val vm = makeVm(case)
                backgroundScope.launch { vm.plant.collect {} }
                backgroundScope.launch { vm.pendingWateringSuggestion.collect {} }
                vm.suggestedWateringInterval.value = raw.intervalDays
                vm.suggestedWateringBaseInterval.value = raw.baseIntervalDays
                runCurrent()

                // Guards the null branch: a null from a not-yet-loaded plant must not pass as "suppressed".
                assertEquals(case.plant, vm.plant.value)
                val pending = vm.pendingWateringSuggestion.value
                if (suggestion == null) {
                    suppressed++
                    assertNull("$case: observe() suppressed it, the dialog must too", pending)
                } else {
                    shown++
                    assertEquals("$case raw", suggestion.intervalDays, pending?.rawIntervalDays)
                    assertEquals("$case effective", suggestion.effectiveIntervalDays, pending?.effectiveIntervalDays)
                    assertEquals("$case current", suggestion.currentEffectiveIntervalDays, pending?.currentIntervalDays)
                }
            }
            assertTrue("the matrix must exercise the shown-dialog side", shown > 0)
            assertTrue("the matrix must exercise the suppressed side", suppressed > 0)
        }

    /**
     * When observe() returns `null` because the gate said "no change", recover the model's raw result
     * from the base it persisted silently, so the dialog side can be checked against that verdict too.
     */
    private suspend fun rawModelResultFor(case: Case, now: Long): AdaptiveWateringObservation.Suggestion? {
        val capturedBases = mutableListOf<Double>()
        val plantRepo: PlantRepository = mockk {
            coEvery { updateWateringConfidenceAndBase(1L, any(), capture(capturedBases), any()) } returns Unit
            coEvery { updateWateringConfidence(1L, any(), any()) } returns Unit
        }
        val careLogRepo: CareLogRepository = mockk {
            coEvery { getLastWateringBefore(1L, now) } returns predecessor(case, now)
            coEvery { getRecentWaterings(any(), limit = any()) } returns emptyList()
            coEvery { getWaterLogTimestampsAscending(any()) } returns emptyList()
        }
        AdaptiveWateringObservation(plantRepo, careLogRepo, dataStore(case.amplitude), mockk(relaxed = true))
            .observe(case.plant, case.feedback, loggedAt = now, displayNow = now)
        val base = capturedBases.lastOrNull()
            ?.takeIf { it != case.plant.wateringBaseIntervalDays }
        return base?.let {
            AdaptiveWateringObservation.Suggestion(
                intervalDays = it.roundToInt(),
                effectiveIntervalDays = 0,
                baseIntervalDays = it,
                currentEffectiveIntervalDays = 0
            )
        }
    }
}
