package com.yapt.planttracker.ui.screens.plantlist

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import app.cash.turbine.test
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
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

class PlantListViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val application: Application = mockk {
        every { getString(R.string.quick_log_watered, any()) } answers { "Watered ${arg<String>(1)}" }
        every { getString(R.string.quick_log_fertilized, any()) } answers { "Fertilized ${arg<String>(1)}" }
        every { getString(R.string.quick_log_watered_and_fertilized, any()) } answers { "Watered and fertilized ${arg<String>(1)}" }
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
}
