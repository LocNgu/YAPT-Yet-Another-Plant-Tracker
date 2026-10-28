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
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlantDetailViewModelFamilyTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val dataStore: DataStore<Preferences> = mockk { every { data } returns flowOf(emptyPreferences()) }
    private val quickLogUseCase: QuickLogUseCase = mockk()
    private val customReminderRepo: CustomReminderRepository = mockk()
    private val plantIssueRepo: PlantIssueRepository = mockk()
    private val database: PlantDatabase = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk {
        every { getRecentForPlant(any(), any()) } returns flowOf(emptyList())
    }

    private fun plant(id: Long, name: String, parent: Long? = null) =
        Plant(id = id, name = name, createdAt = 0L, updatedAt = 0L, propagatedFromId = parent)

    private fun makeVm(me: Plant): PlantDetailViewModel {
        every { plantRepo.getPlantById(me.id) } returns flowOf(me)
        every { careLogRepo.getLogsForPlant(me.id) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(me.id) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(me.id) } returns flowOf(emptyList())
        every { customReminderRepo.getRemindersForPlant(me.id) } returns flowOf(emptyList())
        every { plantIssueRepo.getActiveIssuesForPlant(me.id) } returns flowOf(emptyList())
        return PlantDetailViewModel(
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            me.id,
            dataStore,
            quickLogUseCase,
            customReminderRepo,
            plantIssueRepo,
            database,
            wateringAdjustmentRepo
        )
    }

    @Test
    fun `quickPropagate logs PROPAGATE at the picked date and offers the new plant`() = runTest {
        val me = plant(1L, "Pothos")
        coEvery { quickLogUseCase.quickLog(me, CareType.PROPAGATE, 5_000L) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Propagated Pothos", logged = true)
        val vm = makeVm(me)
        vm.plant.test {
            assertEquals(me, awaitItem())
            vm.events.test {
                vm.quickPropagate(5_000L)
                assertEquals(PlantDetailViewModel.Event.PropagateLogged, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }

        coVerify { quickLogUseCase.quickLog(me, CareType.PROPAGATE, 5_000L) }
    }

    @Test
    fun `quickPropagate on a day that already has one reports it and offers nothing`() = runTest {
        val me = plant(1L, "Pothos")
        coEvery { quickLogUseCase.quickLog(me, CareType.PROPAGATE, any()) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Already", logged = false)
        val vm = makeVm(me)
        vm.plant.test {
            assertEquals(me, awaitItem())
            vm.quickLogMessage.test {
                vm.quickPropagate(5_000L)
                assertEquals(PlantDetailViewModel.QuickLogMessage.AlreadyPropagatedToday("Pothos"), awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `parentPlant follows the propagatedFromId`() = runTest {
        val mother = plant(7L, "Mother")
        val child = plant(8L, "Cutting", parent = 7L)
        every { plantRepo.getPlantById(7L) } returns flowOf(mother)
        val vm = makeVm(child)

        assertEquals(mother, vm.parentPlant.first { it != null })
    }

    @Test
    fun `parentPlant stays null for a plant without a parent`() = runTest {
        val vm = makeVm(plant(8L, "Loner"))

        vm.parentPlant.test {
            assertNull(awaitItem())
            expectNoEvents()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `childPlants comes from the repository's children lookup`() = runTest {
        val me = plant(7L, "Mother")
        val kids = listOf(plant(8L, "A", parent = 7L), plant(9L, "B", parent = 7L))
        every { plantRepo.getChildren(7L) } returns flowOf(kids)
        val vm = makeVm(me)

        assertEquals(kids, vm.childPlants.first { it.isNotEmpty() })
    }

    @Test
    fun `parent picker excludes the plant and its descendants, keeps the rest incl archived and re-parentable`() =
        runTest {
            val me = plant(1L, "Me")
            val grandparent = plant(2L, "Grandparent")
            val child = plant(3L, "Child", parent = 1L)
            val archived = plant(4L, "Archived").copy(archivedAt = 9L)
            val adopted = plant(5L, "Adopted", parent = 2L)
            every { plantRepo.getAllPlantsIncludingArchived() } returns
                flowOf(listOf(me, grandparent, child, archived, adopted))
            coEvery { plantRepo.getDescendantIds(1L) } returns setOf(3L)
            val vm = makeVm(me)

            val candidates = vm.familyCandidates(FamilyPickerMode.PARENT)

            assertEquals(listOf("Adopted", "Archived", "Grandparent"), candidates.map { it.plant.name })
            assertEquals("Grandparent", candidates.first { it.plant.id == 5L }.currentParent?.name)
        }

    @Test
    fun `child picker excludes the plant, its ancestors and its existing children but keeps re-parentable plants`() =
        runTest {
            val me = plant(1L, "Me", parent = 2L)
            val parent = plant(2L, "Parent")
            val existingChild = plant(3L, "Existing child", parent = 1L)
            val stranger = plant(4L, "Stranger")
            val adopted = plant(5L, "Adopted", parent = 4L)
            every { plantRepo.getAllPlantsIncludingArchived() } returns
                flowOf(listOf(me, parent, existingChild, stranger, adopted))
            coEvery { plantRepo.getAncestorIds(1L) } returns setOf(2L)
            val vm = makeVm(me)

            val candidates = vm.familyCandidates(FamilyPickerMode.CHILD)

            assertEquals(listOf("Adopted", "Stranger"), candidates.map { it.plant.name })
            assertEquals("Stranger", candidates.first { it.plant.id == 5L }.currentParent?.name)
        }

    @Test
    fun `linkChild asks the repository to link and a refusal surfaces as an event`() = runTest {
        val me = plant(1L, "Me")
        coEvery { plantRepo.linkParent(3L, 1L) } returns false
        val vm = makeVm(me)

        vm.events.test {
            vm.linkChild(3L)
            assertEquals(PlantDetailViewModel.Event.FamilyLinkRefused, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        coVerify { plantRepo.linkParent(childId = 3L, parentId = 1L) }
    }

    @Test
    fun `linkParent links this plant under the chosen parent without an event on success`() = runTest {
        val me = plant(1L, "Me")
        coEvery { plantRepo.linkParent(1L, 7L) } returns true
        val vm = makeVm(me)

        vm.events.test {
            vm.linkParent(7L)
            advanceUntilIdle()
            expectNoEvents()
        }
        coVerify { plantRepo.linkParent(childId = 1L, parentId = 7L) }
    }

    @Test
    fun `unlinkParent and unlinkChild clear exactly one link each`() = runTest {
        val me = plant(1L, "Me", parent = 7L)
        coEvery { plantRepo.unlinkParent(any()) } just runs
        val vm = makeVm(me)

        vm.unlinkParent()
        vm.unlinkChild(3L)
        advanceUntilIdle()

        coVerify(exactly = 1) { plantRepo.unlinkParent(1L) }
        coVerify(exactly = 1) { plantRepo.unlinkParent(3L) }
    }
}
