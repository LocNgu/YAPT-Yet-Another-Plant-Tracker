package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlantDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockDataStore: DataStore<Preferences> = mockk<DataStore<Preferences>>().also {
        every { it.data } returns flowOf(emptyPreferences())
    }

    private val mockQuickLogUseCase: QuickLogUseCase = mockk(relaxed = true)

    private fun makeViewModel(plant: Plant): PlantDetailViewModel {
        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        val plantPhotoRepo = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        return PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo, plant.id, mockDataStore, mockQuickLogUseCase)
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

        // Plant name appears in the content body.
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
        val plantPhotoRepo3 = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(careLogs)
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { plantPhotoRepo3.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        val viewModel = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo3, plant.id, mockDataStore, mockQuickLogUseCase)

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
        val plantPhotoRepo5 = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(careLogs)
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { plantPhotoRepo5.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        val viewModel = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo5, plant.id, mockDataStore, mockQuickLogUseCase)

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
        val plantPhotoRepo4 = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(careLogs)
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { plantPhotoRepo4.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        val viewModel = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo4, plant.id, mockDataStore, mockQuickLogUseCase)

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

    @Test
    fun coverPhoto_tapOpensFullScreenViewer() {
        val plant = Plant(id = 6L, name = "Monstera", coverPhotoUri = "content://fake/photo", createdAt = 0L, updatedAt = 0L)
        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        val plantPhotoRepo6 = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { plantPhotoRepo6.getPhotosForPlant(plant.id) } returns flowOf(listOf(
            PlantPhoto(id = 1L, plantId = 6L, uri = "content://fake/photo", capturedAt = 0L)
        ))
        val viewModel = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo6, plant.id, mockDataStore, mockQuickLogUseCase)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Plant cover photo").performClick()
        composeTestRule.onNodeWithContentDescription("Close photo viewer").assertIsDisplayed()
    }

    @Test
    fun coverPhoto_placeholderTapDoesNotOpenFullScreenViewer() {
        val plant = Plant(id = 7L, name = "Cactus", coverPhotoUri = null, createdAt = 0L, updatedAt = 0L)
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

        composeTestRule.onRoot().performClick()
        assertTrue(
            composeTestRule.onAllNodesWithContentDescription("Close photo viewer")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun skipWateringButton_isDisplayedWhenOverdue() {
        // wateringDueDateOverride = 0L (epoch, Jan 1 1970) is long before today → isOverdue = true
        val plant = Plant(
            id = 10L,
            name = "Overdue Plant",
            wateringIntervalDays = 7,
            wateringDueDateOverride = 0L,
            createdAt = 0L,
            updatedAt = 0L
        )
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

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Skip watering")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    @Test
    fun skipWateringButton_isHiddenWhenNotDue() {
        // No wateringIntervalDays → button condition fails, button not composed
        val plant = Plant(id = 11L, name = "No Schedule", createdAt = 0L, updatedAt = 0L)
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

        composeTestRule.waitForIdle()
        assertTrue(
            composeTestRule.onAllNodesWithText("Skip watering")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun wateringChip_tapOpensWaterFeedbackSheet() {
        val plant = Plant(id = 20L, name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
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

        composeTestRule.onNodeWithText("Watering").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("How was the soil?")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText("Water Fern?").assertIsDisplayed()
    }

    @Test
    fun fertilizingChip_liquidPlant_tapOpensCombinedSheet() {
        val plant = Plant(
            id = 21L,
            name = "Ivy",
            useLiquidFertilizer = true,
            fertilizingIntervalDays = 30,
            wateringIntervalDays = 7,
            createdAt = 0L,
            updatedAt = 0L
        )
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

        composeTestRule.onNodeWithText("Fertilizing").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Water & fertilize Ivy?")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText("Water & fertilize Ivy?").assertIsDisplayed()
    }

    @Test
    fun fertilizingChip_regularPlant_tapLogsDirectlyWithoutSheet() {
        val plant = Plant(
            id = 22L,
            name = "Basil",
            fertilizingIntervalDays = 30,
            wateringIntervalDays = 7,
            createdAt = 0L,
            updatedAt = 0L
        )
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

        composeTestRule.onNodeWithText("Fertilizing").performClick()
        // A regular plant logs the fertilizing directly and shows a snackbar; no feedback sheet opens.
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Fertilized Basil")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        assertTrue(
            composeTestRule.onAllNodesWithText("How was the soil?")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }
}
