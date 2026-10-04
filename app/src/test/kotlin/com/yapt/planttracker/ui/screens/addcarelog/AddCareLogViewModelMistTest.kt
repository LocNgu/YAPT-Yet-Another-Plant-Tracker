package com.yapt.planttracker.ui.screens.addcarelog

import app.cash.turbine.test
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

// Misting is retired for new logs (#875, product ADR-0061): create mode never offers or preselects
// MIST, and an edit session offers it only when it opened on an existing MIST log.
@OptIn(ExperimentalCoroutinesApi::class)
class AddCareLogViewModelMistTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val careLogRepo: CareLogRepository = mockk()
    private val plantRepo: PlantRepository = mockk()

    private val loggedAt = System.currentTimeMillis()

    private fun existingLog(careType: CareType) = CareLog(
        id = 99L,
        plantId = 1L,
        careType = careType,
        loggedAt = loggedAt,
        notes = "Old entry"
    )

    private fun createModeViewModel(): AddCareLogViewModel {
        every { plantRepo.getPlantById(any()) } returns flowOf(null)
        return AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L)
    }

    private fun editModeViewModel(careType: CareType): AddCareLogViewModel {
        every { plantRepo.getPlantById(any()) } returns flowOf(null)
        coEvery { careLogRepo.getLogById(99L) } returns existingLog(careType)
        coEvery { careLogRepo.addLog(any()) } returns 99L
        return AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 99L)
    }

    @Test
    fun `a MIST preselection request keeps the default care type`() {
        val vm = createModeViewModel()

        vm.preselectCareType(CareType.MIST)

        assertEquals(CareType.WATER, vm.selectedCareType)
    }

    @Test
    fun `other care types can still be preselected`() {
        val vm = createModeViewModel()

        vm.preselectCareType(CareType.PRUNE)

        assertEquals(CareType.PRUNE, vm.selectedCareType)
    }

    @Test
    fun `create mode does not offer the Mist type`() {
        val vm = createModeViewModel()

        assertFalse(vm.offersMistType)
    }

    @Test
    fun `editing a MIST log selects it and offers the Mist type`() = runTest {
        val vm = editModeViewModel(CareType.MIST)

        advanceUntilIdle()

        assertEquals(CareType.MIST, vm.selectedCareType)
        assertTrue(vm.offersMistType)
    }

    @Test
    fun `editing a non-MIST log does not offer the Mist type`() = runTest {
        val vm = editModeViewModel(CareType.PRUNE)

        advanceUntilIdle()

        assertEquals(CareType.PRUNE, vm.selectedCareType)
        assertFalse(vm.offersMistType)
    }

    @Test
    fun `saving an untouched MIST log keeps its type`() = runTest {
        val vm = editModeViewModel(CareType.MIST)
        advanceUntilIdle()

        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { careLogRepo.addLog(match { it.id == 99L && it.careType == CareType.MIST }) }
    }

    @Test
    fun `switching a MIST log to another type saves the new type and keeps offering Mist`() = runTest {
        val vm = editModeViewModel(CareType.MIST)
        advanceUntilIdle()

        vm.selectedCareType = CareType.NOTE

        assertTrue(vm.offersMistType)
        vm.events.test {
            vm.saveLog()
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }
        coVerify { careLogRepo.addLog(match { it.id == 99L && it.careType == CareType.NOTE }) }
    }

    @Test
    fun `picker options leave out Mist unless it is included`() {
        val withoutMist = careTypePickerOptions(includeMist = false)
        val withMist = careTypePickerOptions(includeMist = true)

        assertEquals(
            listOf(
                CareType.WATER,
                CareType.FERTILIZE,
                CareType.PRUNE,
                CareType.REPOT,
                CareType.NOTE,
                CareType.PHOTO
            ),
            withoutMist
        )
        assertEquals(
            listOf(
                CareType.WATER,
                CareType.FERTILIZE,
                CareType.PRUNE,
                CareType.MIST,
                CareType.REPOT,
                CareType.NOTE,
                CareType.PHOTO
            ),
            withMist
        )
    }
}
