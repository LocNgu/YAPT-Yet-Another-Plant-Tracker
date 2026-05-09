package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlantDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeViewModel(plant: Plant): PlantDetailViewModel {
        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        return PlantDetailViewModel(plantRepo, careLogRepo, plant.id)
    }

    @Test
    fun plantName_isDisplayed() {
        val plant = Plant(id = 1L, name = "Ficus", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        // Plant name appears in both the TopAppBar title and the content body; asserting at least one is displayed.
        composeTestRule.onAllNodesWithText("Ficus")[0].assertIsDisplayed()
    }

    @Test
    fun logCareFab_isDisplayed() {
        val plant = Plant(id = 2L, name = "Pothos", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Log care").assertIsDisplayed()
    }

    @Test
    fun wateringChart_displaysWithMultipleWateringLogs() {
        val plant = Plant(id = 3L, name = "Snake Plant", createdAt = 0L, updatedAt = 0L)
        val dayInMs = 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()

        val careLogs = listOf(
            CareLog(
                id = 1L,
                plantId = 3L,
                careType = CareType.WATER,
                loggedAt = now - (5 * dayInMs)
            ),
            CareLog(
                id = 2L,
                plantId = 3L,
                careType = CareType.WATER,
                loggedAt = now - (3 * dayInMs)
            ),
            CareLog(
                id = 3L,
                plantId = 3L,
                careType = CareType.WATER,
                loggedAt = now
            )
        )

        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(careLogs)
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        val viewModel = PlantDetailViewModel(plantRepo, careLogRepo, plant.id)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithText("Watering History").assertIsDisplayed()
    }

    @Test
    fun wateringChart_displaysWithTwoWateringLogs() {
        val plant = Plant(id = 5L, name = "Spider Plant", createdAt = 0L, updatedAt = 0L)
        val dayInMs = 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()

        val careLogs = listOf(
            CareLog(
                id = 1L,
                plantId = 5L,
                careType = CareType.WATER,
                loggedAt = now - dayInMs
            ),
            CareLog(
                id = 2L,
                plantId = 5L,
                careType = CareType.WATER,
                loggedAt = now
            )
        )

        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(careLogs)
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        val viewModel = PlantDetailViewModel(plantRepo, careLogRepo, plant.id)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithText("Watering History").assertIsDisplayed()
    }

    @Test
    fun wateringChart_showsEmptyStateWithFewerThanTwoWateringLogs() {
        val plant = Plant(id = 4L, name = "Succulent", createdAt = 0L, updatedAt = 0L)
        val now = System.currentTimeMillis()

        val careLogs = listOf(
            CareLog(
                id = 1L,
                plantId = 4L,
                careType = CareType.WATER,
                loggedAt = now
            )
        )

        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(careLogs)
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        val viewModel = PlantDetailViewModel(plantRepo, careLogRepo, plant.id)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithText("Need at least 2 watering logs to display watering history.").assertIsDisplayed()
    }
}
