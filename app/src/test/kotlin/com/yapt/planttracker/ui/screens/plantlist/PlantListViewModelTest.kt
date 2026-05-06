package com.yapt.planttracker.ui.screens.plantlist

import app.cash.turbine.test
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
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

    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
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
        coEvery { careLogRepo.getLastLogOfType(any(), any()) } returns null
        coEvery { careLogRepo.getCareLogCount(any()) } returns 0
    }

    @Test
    fun `empty plant list emits empty status list`() = runTest {
        every { plantRepo.getAllPlants() } returns flowOf(emptyList())
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        vm = PlantListViewModel(plantRepo, careLogRepo)

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
        vm = PlantListViewModel(plantRepo, careLogRepo)

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
        vm = PlantListViewModel(plantRepo, careLogRepo)

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
        vm = PlantListViewModel(plantRepo, careLogRepo)

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
        vm = PlantListViewModel(plantRepo, careLogRepo)

        vm.plantsWithStatus.test {
            val items = awaitItem()
            assertEquals(1, items.size)
            assertEquals(5, items[0].totalCareLogs)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
