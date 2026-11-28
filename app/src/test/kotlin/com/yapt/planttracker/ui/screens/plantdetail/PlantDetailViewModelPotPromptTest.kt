package com.yapt.planttracker.ui.screens.plantdetail

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PotMaterial
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The optional pot prompt after a repot logged from Plant Detail (#1003). */
@OptIn(ExperimentalCoroutinesApi::class)
class PlantDetailViewModelPotPromptTest {

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

    private val plant = Plant(
        id = 1L,
        name = "Monstera",
        potMaterial = PotMaterial.PLASTIC,
        potDiameterCm = 14,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun makeVm(): PlantDetailViewModel {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(1L) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(1L) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(1L) } returns flowOf(emptyList())
        every { customReminderRepo.getRemindersForPlant(1L) } returns flowOf(emptyList())
        every { plantIssueRepo.getActiveIssuesForPlant(1L) } returns flowOf(emptyList())
        coEvery { plantRepo.updatePot(any(), any(), any(), any()) } just runs
        return PlantDetailViewModel(
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            1L,
            dataStore,
            quickLogUseCase,
            customReminderRepo,
            plantIssueRepo,
            database,
            wateringAdjustmentRepo
        )
    }

    private fun stubRepotLogged(logged: Boolean) {
        coEvery { quickLogUseCase.quickLog(any(), CareType.REPOT, any()) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Repotted", logged = logged)
        coEvery { quickLogUseCase.maybeBuildPhotoReminderRequest(1L) } returns null
    }

    @Test
    fun `a logged repot opens the prompt pre-filled with the current pot`() = runTest {
        stubRepotLogged(true)
        val vm = makeVm()
        advanceUntilIdle()

        vm.quickRepot(1_700_000_000_000L)
        advanceUntilIdle()

        assertEquals(PotPrompt(PotMaterial.PLASTIC, 14), vm.potPrompt.value)
    }

    @Test
    fun `a repot that was not logged does not prompt`() = runTest {
        stubRepotLogged(false)
        val vm = makeVm()
        advanceUntilIdle()

        vm.quickRepot(1_700_000_000_000L)
        advanceUntilIdle()

        assertNull(vm.potPrompt.value)
    }

    @Test
    fun `skipping closes the prompt and writes nothing`() = runTest {
        stubRepotLogged(true)
        val vm = makeVm()
        advanceUntilIdle()
        vm.quickRepot(1_700_000_000_000L)
        advanceUntilIdle()

        vm.dismissPotPrompt()
        advanceUntilIdle()

        assertNull(vm.potPrompt.value)
        coVerify(exactly = 0) { plantRepo.updatePot(any(), any(), any(), any()) }
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `saving writes only the pot columns and closes the prompt`() = runTest {
        stubRepotLogged(true)
        val vm = makeVm()
        advanceUntilIdle()
        vm.quickRepot(1_700_000_000_000L)
        advanceUntilIdle()

        assertTrue(vm.savePotPrompt(PotMaterial.CERAMIC, "20", now = 5L))
        advanceUntilIdle()

        assertNull(vm.potPrompt.value)
        coVerify(exactly = 1) { plantRepo.updatePot(1L, PotMaterial.CERAMIC, 20, 5L) }
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `a blank size clears it and an out-of-range size is rejected`() = runTest {
        stubRepotLogged(true)
        val vm = makeVm()
        advanceUntilIdle()
        vm.quickRepot(1_700_000_000_000L)
        advanceUntilIdle()

        assertFalse(vm.savePotPrompt(PotMaterial.CERAMIC, "201", now = 5L))
        assertEquals(PotPrompt(PotMaterial.PLASTIC, 14), vm.potPrompt.value)
        coVerify(exactly = 0) { plantRepo.updatePot(any(), any(), any(), any()) }

        assertTrue(vm.savePotPrompt(null, "", now = 6L))
        advanceUntilIdle()
        coVerify(exactly = 1) { plantRepo.updatePot(1L, null, null, 6L) }
    }
}
