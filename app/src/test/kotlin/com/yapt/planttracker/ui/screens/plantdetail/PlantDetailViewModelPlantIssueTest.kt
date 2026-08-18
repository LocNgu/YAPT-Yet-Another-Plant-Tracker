package com.yapt.planttracker.ui.screens.plantdetail

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantIssue
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
import org.junit.Rule
import org.junit.Test

/**
 * `reportIssue`/`resolveIssue`/`activeIssues` coverage for [PlantDetailViewModel] (#564), split out
 * of `PlantDetailViewModelTest` to keep that file under Detekt's `LargeClass` threshold.
 */
class PlantDetailViewModelPlantIssueTest {

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

    private fun plant(id: Long = 1L, name: String = "Monstera") = Plant(
        id = id,
        name = name,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun makeVm(plantId: Long = 1L, activeIssues: List<PlantIssue> = emptyList()): PlantDetailViewModel {
        every { careLogRepo.getLogsForPlant(plantId) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(plantId) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(plantId) } returns flowOf(emptyList())
        every { customReminderRepo.getRemindersForPlant(plantId) } returns flowOf(emptyList())
        every { plantIssueRepo.getActiveIssuesForPlant(plantId) } returns flowOf(activeIssues)
        return PlantDetailViewModel(
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            plantId,
            dataStore,
            quickLogUseCase,
            customReminderRepo,
            plantIssueRepo
        )
    }

    @Test
    fun `reportIssue without reminder fields only creates a PlantIssue`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        coEvery { plantIssueRepo.addIssue(any()) } returns 1L
        val vm = makeVm()

        vm.reportIssue("Spider mites", null, null)

        coVerify {
            plantIssueRepo.addIssue(
                match { it.plantId == 1L && it.name == "Spider mites" && it.linkedReminderId == null }
            )
        }
        coVerify(exactly = 0) { customReminderRepo.addReminder(any()) }
    }

    @Test
    fun `reportIssue with reminder fields creates a CustomReminder first and links its id`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        coEvery { customReminderRepo.addReminder(any()) } returns 77L
        coEvery { plantIssueRepo.addIssue(any()) } returns 1L
        val vm = makeVm()

        vm.reportIssue("Spider mites", "Neem oil treatment", 7)

        coVerify {
            customReminderRepo.addReminder(
                match { it.plantId == 1L && it.name == "Neem oil treatment" && it.intervalDays == 7 }
            )
        }
        coVerify {
            plantIssueRepo.addIssue(
                match { it.plantId == 1L && it.name == "Spider mites" && it.linkedReminderId == 77L }
            )
        }
    }

    @Test
    fun `resolveIssue sets resolvedAt and the resolution note`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        val issue = PlantIssue(id = 5L, plantId = 1L, name = "Spider mites")
        coEvery { plantIssueRepo.updateIssue(any()) } just runs
        val vm = makeVm()

        vm.resolveIssue(issue, "Treated with neem oil")

        coVerify {
            plantIssueRepo.updateIssue(
                match { it.id == 5L && it.resolvedAt != null && it.resolutionNote == "Treated with neem oil" }
            )
        }
    }

    @Test
    fun `resolveIssue with a blank note stores null`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        val issue = PlantIssue(id = 5L, plantId = 1L, name = "Spider mites")
        coEvery { plantIssueRepo.updateIssue(any()) } just runs
        val vm = makeVm()

        vm.resolveIssue(issue, "   ")

        coVerify {
            plantIssueRepo.updateIssue(match { it.id == 5L && it.resolvedAt != null && it.resolutionNote == null })
        }
    }

    @Test
    fun `activeIssues emits the issues from the repository`() = runTest {
        every { plantRepo.getPlantById(1L) } returns flowOf(plant())
        val issue = PlantIssue(id = 5L, plantId = 1L, name = "Spider mites")
        val vm = makeVm(activeIssues = listOf(issue))

        vm.activeIssues.test {
            assertEquals(listOf(issue), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
