package com.yapt.planttracker.ui.screens.plantlist

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlantListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val application: Application = mockk {
        every { getString(R.string.quick_log_watered, any()) } returns "Watered Monstera"
        every { getString(R.string.quick_log_fertilized, any()) } returns "Fertilized Monstera"
        every { getString(R.string.quick_log_watered_and_fertilized, any()) } returns "Watered and fertilized Monstera"
        every { getString(R.string.quick_log_other, any(), any()) } returns "Pruned Monstera"
    }
    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val dataStore: DataStore<Preferences> = mockk {
        every { data } returns flowOf(emptyPreferences())
    }
    private lateinit var vm: PlantListViewModel

    private fun plant(id: Long, name: String, room: String? = null) = Plant(
        id = id,
        name = name,
        room = room,
        createdAt = 0L,
        updatedAt = 0L
    )

    @Before
    fun setup() {
        every { careLogRepo.logCount } returns flowOf(0)
        coEvery { careLogRepo.getLastLogOfType(any(), any()) } returns null
        coEvery { careLogRepo.getCareLogCount(any()) } returns 0
        coEvery { dataStore.edit(any()) } answers {
            firstArg<suspend (MutablePreferences) -> Unit>().invoke(mockk(relaxed = true))
            mockk()
        }
    }

    @Test
    fun `empty plant list emits empty status list`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.plantsWithStatus.test {
            val items = awaitItem()
            assertEquals(0, items.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `one plant with no logs emits status with isOverdue false and null daysSinceLastWatering`() = runTest {
        val monstera = plant(id = 1L, name = "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.plantsWithStatus.test {
            val items = awaitItem()
            assertEquals(1, items.size)
            val status = items[0]
            assertFalse(status.isOverdue)
            assertNull(status.daysSinceLastWatering)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `room filter shows only matching plant`() = runTest {
        val kitchen = plant(id = 1L, name = "Basil", room = "Kitchen")
        val bedroom = plant(id = 2L, name = "Snake Plant", room = "Bedroom")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(kitchen, bedroom))
        every { plantRepo.getAllRooms() } returns flowOf(listOf("Kitchen", "Bedroom"))
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.selectRoom("Kitchen")

        vm.plantsWithStatus.test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals("Basil", items[0].plant.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `selecting null room shows all plants`() = runTest {
        val kitchen = plant(id = 1L, name = "Basil", room = "Kitchen")
        val bedroom = plant(id = 2L, name = "Snake Plant", room = "Bedroom")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(kitchen, bedroom))
        every { plantRepo.getAllRooms() } returns flowOf(listOf("Kitchen", "Bedroom"))
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.selectRoom(null)

        vm.plantsWithStatus.test {
            val items = awaitItem()
            assertEquals(2, items.size)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `plant with watering interval and recent log emits correct care log count`() = runTest {
        val fern = Plant(
            id = 3L,
            name = "Fern",
            wateringIntervalDays = 7,
            createdAt = 0L,
            updatedAt = 0L
        )
        every { plantRepo.getAllPlants() } returns flowOf(listOf(fern))
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { careLogRepo.getCareLogCount(3L) } returns 5
        coEvery { careLogRepo.getLastLogOfType(3L, CareType.WATER) } returns null
        coEvery { careLogRepo.getLastLogOfType(3L, CareType.FERTILIZE) } returns null
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.plantsWithStatus.test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals(5, items[0].totalCareLogs)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `quickLog water emits correct snackbar message`() = runTest {
        val monstera = plant(id = 1L, name = "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { careLogRepo.addLog(any()) } returns 1L
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.quickLogEvent.test {
            vm.plantsWithStatus.test {
                awaitItem()
                vm.quickLog(1L, CareType.WATER)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals("Watered Monstera", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.WATER && it.wateringFeedback == WateringFeedback.JUST_RIGHT })
        }
    }

    @Test
    fun `quickLog fertilize emits correct snackbar message`() = runTest {
        val monstera = plant(id = 1L, name = "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { careLogRepo.addLog(any()) } returns 1L
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.quickLogEvent.test {
            vm.plantsWithStatus.test {
                awaitItem()
                vm.quickLog(1L, CareType.FERTILIZE)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals("Fertilized Monstera", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.FERTILIZE && it.wateringFeedback == null })
        }
    }

    @Test
    fun `quickLog fertilize liquid plant emits watered-and-fertilized message and creates two logs`() = runTest {
        val monstera = Plant(id = 1L, name = "Monstera", useLiquidFertilizer = true, createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { careLogRepo.addLog(any()) } returns 1L
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.quickLogEvent.test {
            vm.plantsWithStatus.test {
                awaitItem()
                vm.quickLog(1L, CareType.FERTILIZE)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals("Watered and fertilized Monstera", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 2) { careLogRepo.addLog(any()) }
        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.FERTILIZE && it.fertilizerType == FertilizerType.LIQUID })
        }
        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.WATER && it.wateringFeedback == WateringFeedback.JUST_RIGHT })
        }
    }

    @Test
    fun `quickLog water clears wateringDueDateOverride when override is active`() = runTest {
        val override = System.currentTimeMillis() + 86_400_000L
        val monstera = Plant(id = 1L, name = "Monstera", wateringDueDateOverride = override, createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { careLogRepo.addLog(any()) } returns 1L
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } returns Unit
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.quickLogEvent.test {
            vm.plantsWithStatus.test {
                awaitItem()
                vm.quickLog(1L, CareType.WATER)
                cancelAndIgnoreRemainingEvents()
            }
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            plantRepo.updatePlant(match { it.wateringDueDateOverride == null })
        }
    }

    @Test
    fun `quickLog water does not call updatePlant when no override is active`() = runTest {
        val monstera = plant(id = 1L, name = "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { careLogRepo.addLog(any()) } returns 1L
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } returns Unit
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.quickLogEvent.test {
            vm.plantsWithStatus.test {
                awaitItem()
                vm.quickLog(1L, CareType.WATER)
                cancelAndIgnoreRemainingEvents()
            }
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    @Test
    fun `quickLog liquid fertilize clears wateringDueDateOverride when override is active`() = runTest {
        val override = System.currentTimeMillis() + 86_400_000L
        val monstera = Plant(id = 1L, name = "Monstera", useLiquidFertilizer = true, wateringDueDateOverride = override, createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { careLogRepo.addLog(any()) } returns 1L
        every { plantRepo.getPlantById(1L) } returns flowOf(monstera)
        coEvery { plantRepo.updatePlant(any()) } returns Unit
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.quickLogEvent.test {
            vm.plantsWithStatus.test {
                awaitItem()
                vm.quickLog(1L, CareType.FERTILIZE)
                cancelAndIgnoreRemainingEvents()
            }
            awaitItem()
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            plantRepo.updatePlant(match { it.wateringDueDateOverride == null })
        }
    }

    @Test
    fun `quickLog other care type emits correct snackbar message`() = runTest {
        val monstera = plant(id = 1L, name = "Monstera")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(monstera))
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { careLogRepo.addLog(any()) } returns 1L
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.quickLogEvent.test {
            vm.plantsWithStatus.test {
                awaitItem()
                vm.quickLog(1L, CareType.PRUNE)
                cancelAndIgnoreRemainingEvents()
            }
            assertEquals("Pruned Monstera", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        coVerify {
            careLogRepo.addLog(match { it.careType == CareType.PRUNE && it.wateringFeedback == null })
        }
    }

    @Test
    fun `unassigned filter shows only plants with null room`() = runTest {
        val kitchen = plant(id = 1L, name = "Basil", room = "Kitchen")
        val unassigned = plant(id = 2L, name = "Snake Plant", room = null)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(kitchen, unassigned))
        every { plantRepo.getAllRooms() } returns flowOf(listOf("Kitchen"))
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.selectRoom(PlantListViewModel.UNASSIGNED_ROOM)

        vm.plantsWithStatus.test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals("Snake Plant", items[0].plant.name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hasUnassignedPlants is true when at least one plant has null room`() = runTest {
        val unassigned = plant(id = 1L, name = "Mystery Plant", room = null)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(unassigned))
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.hasUnassignedPlants.test {
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `hasUnassignedPlants is false when all plants have rooms`() = runTest {
        val kitchen = plant(id = 1L, name = "Basil", room = "Kitchen")
        every { plantRepo.getAllPlants() } returns flowOf(listOf(kitchen))
        every { plantRepo.getAllRooms() } returns flowOf(listOf("Kitchen"))
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.hasUnassignedPlants.test {
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `auto-fallback resets selection to All when last unassigned plant gains a room`() = runTest {
        val plantsFlow = MutableStateFlow(listOf(plant(id = 1L, name = "Snake Plant", room = null)))
        every { plantRepo.getAllPlants() } returns plantsFlow
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.selectRoom(PlantListViewModel.UNASSIGNED_ROOM)
        assertEquals(PlantListViewModel.UNASSIGNED_ROOM, vm.selectedRoom.value)

        plantsFlow.value = listOf(plant(id = 1L, name = "Snake Plant", room = "Kitchen"))
        advanceUntilIdle()

        assertNull(vm.selectedRoom.value)
    }

    // toggleSort direction tests

    @Test
    fun `toggleSort ALPHABETICAL first tap sets ASC direction`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)
        advanceUntilIdle()
        vm.toggleSort(SortOption.WATERING_DUE)

        vm.toggleSort(SortOption.ALPHABETICAL)

        assertEquals(SortOption.ALPHABETICAL, vm.sortOrder.value.option)
        assertEquals(SortDirection.ASC, vm.sortOrder.value.direction)
    }

    @Test
    fun `toggleSort ALPHABETICAL second tap sets DESC direction`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)
        advanceUntilIdle()
        vm.toggleSort(SortOption.WATERING_DUE)

        vm.toggleSort(SortOption.ALPHABETICAL)
        vm.toggleSort(SortOption.ALPHABETICAL)

        assertEquals(SortOption.ALPHABETICAL, vm.sortOrder.value.option)
        assertEquals(SortDirection.DESC, vm.sortOrder.value.direction)
    }

    @Test
    fun `toggleSort ALPHABETICAL third tap cycles back to ASC`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)
        advanceUntilIdle()
        vm.toggleSort(SortOption.WATERING_DUE)

        vm.toggleSort(SortOption.ALPHABETICAL)
        vm.toggleSort(SortOption.ALPHABETICAL)
        vm.toggleSort(SortOption.ALPHABETICAL)

        assertEquals(SortOption.ALPHABETICAL, vm.sortOrder.value.option)
        assertEquals(SortDirection.ASC, vm.sortOrder.value.direction)
    }

    @Test
    fun `toggleSort WATERING_DUE first tap sets DESC direction`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)
        advanceUntilIdle()

        vm.toggleSort(SortOption.WATERING_DUE)

        assertEquals(SortOption.WATERING_DUE, vm.sortOrder.value.option)
        assertEquals(SortDirection.DESC, vm.sortOrder.value.direction)
    }

    @Test
    fun `toggleSort WATERING_DUE second tap sets ASC direction`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)
        advanceUntilIdle()

        vm.toggleSort(SortOption.WATERING_DUE)
        vm.toggleSort(SortOption.WATERING_DUE)

        assertEquals(SortOption.WATERING_DUE, vm.sortOrder.value.option)
        assertEquals(SortDirection.ASC, vm.sortOrder.value.direction)
    }

    @Test
    fun `toggleSort WATERING_DUE third tap cycles back to DESC`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)
        advanceUntilIdle()

        vm.toggleSort(SortOption.WATERING_DUE)
        vm.toggleSort(SortOption.WATERING_DUE)
        vm.toggleSort(SortOption.WATERING_DUE)

        assertEquals(SortOption.WATERING_DUE, vm.sortOrder.value.option)
        assertEquals(SortDirection.DESC, vm.sortOrder.value.direction)
    }

    @Test
    fun `toggleSort RECENTLY_ADDED repeated taps do not change direction`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)
        advanceUntilIdle()

        vm.toggleSort(SortOption.RECENTLY_ADDED)
        vm.toggleSort(SortOption.RECENTLY_ADDED)

        assertEquals(SortOption.RECENTLY_ADDED, vm.sortOrder.value.option)
        assertEquals(SortDirection.DESC, vm.sortOrder.value.direction)
    }

    @Test
    fun `toggleSort switching from ALPHABETICAL to WATERING_DUE resets to DESC`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)
        advanceUntilIdle()

        vm.toggleSort(SortOption.ALPHABETICAL)
        vm.toggleSort(SortOption.WATERING_DUE)

        assertEquals(SortOption.WATERING_DUE, vm.sortOrder.value.option)
        assertEquals(SortDirection.DESC, vm.sortOrder.value.direction)
    }

    @Test
    fun `toggleSort switching from WATERING_DUE to ALPHABETICAL resets to ASC`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)
        advanceUntilIdle()

        vm.toggleSort(SortOption.WATERING_DUE)
        vm.toggleSort(SortOption.ALPHABETICAL)

        assertEquals(SortOption.ALPHABETICAL, vm.sortOrder.value.option)
        assertEquals(SortDirection.ASC, vm.sortOrder.value.direction)
    }

    // applySortOrder ordering tests

    @Test
    fun `applySortOrder ALPHABETICAL ASC orders plants case-insensitively A to Z`() = runTest {
        val zebra = Plant(id = 1L, name = "Zebra", createdAt = 0L, updatedAt = 0L)
        val apple = Plant(id = 2L, name = "apple", createdAt = 0L, updatedAt = 0L)
        val monstera = Plant(id = 3L, name = "Monstera", createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(zebra, apple, monstera))
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)
        advanceUntilIdle()

        vm.plantsWithStatus.test {
            val items = awaitItem()
            assertEquals(listOf("apple", "Monstera", "Zebra"), items.map { it.plant.name })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `applySortOrder WATERING_DUE DESC puts most overdue first with null last`() = runTest {
        val oneDayMs = TimeUnit.DAYS.toMillis(1)
        val p1 = Plant(id = 1L, name = "P1", wateringIntervalDays = 1, createdAt = 0L, updatedAt = 0L)
        val p2 = Plant(id = 2L, name = "P2", wateringIntervalDays = 1, createdAt = 0L, updatedAt = 0L)
        val p3 = Plant(id = 3L, name = "P3", createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(p1, p2, p3))
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { careLogRepo.getLastLogOfType(1L, CareType.WATER) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = 200L - oneDayMs)
        coEvery { careLogRepo.getLastLogOfType(2L, CareType.WATER) } returns
            CareLog(plantId = 2L, careType = CareType.WATER, loggedAt = 100L - oneDayMs)
        coEvery { careLogRepo.getLastLogOfType(3L, CareType.WATER) } returns null
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.toggleSort(SortOption.WATERING_DUE)
        advanceUntilIdle()

        vm.plantsWithStatus.test {
            val items = awaitItem()
            assertEquals(listOf(2L, 1L, 3L), items.map { it.plant.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `applySortOrder WATERING_DUE ASC puts latest due first with null still last`() = runTest {
        val oneDayMs = TimeUnit.DAYS.toMillis(1)
        val p1 = Plant(id = 1L, name = "P1", wateringIntervalDays = 1, createdAt = 0L, updatedAt = 0L)
        val p2 = Plant(id = 2L, name = "P2", wateringIntervalDays = 1, createdAt = 0L, updatedAt = 0L)
        val p3 = Plant(id = 3L, name = "P3", createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(p1, p2, p3))
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { careLogRepo.getLastLogOfType(1L, CareType.WATER) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = 200L - oneDayMs)
        coEvery { careLogRepo.getLastLogOfType(2L, CareType.WATER) } returns
            CareLog(plantId = 2L, careType = CareType.WATER, loggedAt = 100L - oneDayMs)
        coEvery { careLogRepo.getLastLogOfType(3L, CareType.WATER) } returns null
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.toggleSort(SortOption.WATERING_DUE)
        vm.toggleSort(SortOption.WATERING_DUE)
        advanceUntilIdle()

        vm.plantsWithStatus.test {
            val items = awaitItem()
            assertEquals(listOf(1L, 2L, 3L), items.map { it.plant.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `applySortOrder WATERING_DUE DESC tiebreaks by plant id descending`() = runTest {
        val oneDayMs = TimeUnit.DAYS.toMillis(1)
        val sameTs = 150L - oneDayMs
        val p1 = Plant(id = 1L, name = "P1", wateringIntervalDays = 1, createdAt = 0L, updatedAt = 0L)
        val p2 = Plant(id = 2L, name = "P2", wateringIntervalDays = 1, createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(p1, p2))
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        coEvery { careLogRepo.getLastLogOfType(1L, CareType.WATER) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = sameTs)
        coEvery { careLogRepo.getLastLogOfType(2L, CareType.WATER) } returns
            CareLog(plantId = 2L, careType = CareType.WATER, loggedAt = sameTs)
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.toggleSort(SortOption.WATERING_DUE)
        advanceUntilIdle()

        vm.plantsWithStatus.test {
            val items = awaitItem()
            assertEquals(listOf(2L, 1L), items.map { it.plant.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `applySortOrder RECENTLY_ADDED sorts by plant id descending`() = runTest {
        val p1 = Plant(id = 1L, name = "P1", createdAt = 0L, updatedAt = 0L)
        val p2 = Plant(id = 2L, name = "P2", createdAt = 0L, updatedAt = 0L)
        val p3 = Plant(id = 3L, name = "P3", createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getAllPlants() } returns flowOf(listOf(p1, p2, p3))
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        vm.toggleSort(SortOption.RECENTLY_ADDED)
        advanceUntilIdle()

        vm.plantsWithStatus.test {
            val items = awaitItem()
            assertEquals(listOf(3L, 2L, 1L), items.map { it.plant.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `empty list does not crash for any sort option`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(application, plantRepo, careLogRepo, dataStore)

        for (option in SortOption.entries) {
            vm.toggleSort(option)
            advanceUntilIdle()
        }

        vm.plantsWithStatus.test {
            val items = awaitItem()
            assertEquals(0, items.size)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
