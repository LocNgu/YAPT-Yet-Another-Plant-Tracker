package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
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
}
