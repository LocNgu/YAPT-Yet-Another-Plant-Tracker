package com.yapt.planttracker.ui.screens.plantlist

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlantListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeViewModel(
        plants: List<Plant> = emptyList(),
        rooms: List<String> = emptyList()
    ): PlantListViewModel {
        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        val plantPhotoRepo = mockk<PlantPhotoRepository>()
        val dataStore = mockk<DataStore<Preferences>> {
            every { data } returns flowOf(emptyPreferences())
        }
        coEvery { dataStore.updateData(any()) } returns emptyPreferences()
        every { plantRepo.getAllPlants() } returns flowOf(plants)
        every { plantRepo.getAllRooms() } returns flowOf(rooms)
        every { careLogRepo.logCount } returns flowOf(0)
        coEvery { careLogRepo.getLastLogOfType(any(), CareType.WATER) } returns null
        coEvery { careLogRepo.getLastLogOfType(any(), CareType.FERTILIZE) } returns null
        coEvery { careLogRepo.getCareLogCount(any()) } returns 0
        val application = ApplicationProvider.getApplicationContext<Application>()
        val quickLogUseCase = QuickLogUseCase(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore)
        return PlantListViewModel(
            application,
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            dataStore,
            quickLogUseCase
        )
    }

    @Test
    fun emptyState_showsNoPlantsMessage() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            PlantListScreen(
                viewModel = viewModel,
                onNavigateToPlant = {},
                onNavigateToAdd = {},
                onNavigateToSettings = {}
            )
        }

        composeTestRule
            .onNode(hasText("No plants yet!", substring = true))
            .assertIsDisplayed()
    }

    @Test
    fun withPlants_showsPlantName() {
        val plant = Plant(id = 1L, name = "Monstera", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plants = listOf(plant))

        composeTestRule.setContent {
            PlantListScreen(
                viewModel = viewModel,
                onNavigateToPlant = {},
                onNavigateToAdd = {},
                onNavigateToSettings = {}
            )
        }

        composeTestRule.onNodeWithText("Monstera").assertIsDisplayed()
    }

    @Test
    fun sortingByWateringDue_showsDateGroupHeader() {
        val plant = Plant(id = 1L, name = "Monstera", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plants = listOf(plant))

        composeTestRule.setContent {
            PlantListScreen(
                viewModel = viewModel,
                onNavigateToPlant = {},
                onNavigateToAdd = {},
                onNavigateToSettings = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Sort plants").performClick()
        composeTestRule.onNodeWithText("Watering due").performClick()

        composeTestRule.onNodeWithText("Not scheduled").assertIsDisplayed()
    }

    @Test
    fun sortingAlphabetically_showsNoDateGroupHeader() {
        val plant = Plant(id = 1L, name = "Monstera", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plants = listOf(plant))

        composeTestRule.setContent {
            PlantListScreen(
                viewModel = viewModel,
                onNavigateToPlant = {},
                onNavigateToAdd = {},
                onNavigateToSettings = {}
            )
        }

        composeTestRule.onNode(hasText("Not scheduled", substring = true)).assertDoesNotExist()
    }

    @Test
    fun longPressPlant_entersSelectionMode() {
        val plant = Plant(id = 1L, name = "Monstera", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plants = listOf(plant))

        composeTestRule.setContent {
            PlantListScreen(
                viewModel = viewModel,
                onNavigateToPlant = {},
                onNavigateToAdd = {},
                onNavigateToSettings = {}
            )
        }

        composeTestRule.onNodeWithText("Monstera").performTouchInput { longClick() }

        // Long-press enters multi-select mode: the top bar switches to the contextual
        // selection bar showing the count and a clear-selection button. (The bulk-action
        // sheet's contents and per-action behaviour are covered by PlantListViewModelTest.)
        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Clear selection").assertIsDisplayed()
    }

    @Test
    fun selectionMode_selectAll_selectsEveryPlant() {
        val monstera = Plant(id = 1L, name = "Monstera", createdAt = 0L, updatedAt = 0L)
        val fern = Plant(id = 2L, name = "Fern", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plants = listOf(monstera, fern))

        composeTestRule.setContent {
            PlantListScreen(
                viewModel = viewModel,
                onNavigateToPlant = {},
                onNavigateToAdd = {},
                onNavigateToSettings = {}
            )
        }

        composeTestRule.onNodeWithText("Monstera").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()

        // "Select all" is the DoneAll action in the contextual bar; a plain icon-button click.
        composeTestRule.onNodeWithContentDescription("Select all").performClick()
        composeTestRule.onNodeWithText("2 selected").assertIsDisplayed()
    }

    @Test
    fun selectionMode_clearSelection_returnsToNormalBar() {
        val plant = Plant(id = 1L, name = "Monstera", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plants = listOf(plant))

        composeTestRule.setContent {
            PlantListScreen(
                viewModel = viewModel,
                onNavigateToPlant = {},
                onNavigateToAdd = {},
                onNavigateToSettings = {}
            )
        }

        composeTestRule.onNodeWithText("Monstera").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("Clear selection").performClick()

        // Selection mode exits: the contextual bar is gone and the normal top-bar sort
        // action returns. (Asserted via top-bar elements, not the bottom FAB, whose text
        // isn't reliably "displayed" in the emulator viewport.)
        composeTestRule.onNodeWithText("1 selected").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Sort plants").assertIsDisplayed()
    }

    @Test
    fun neverWateredPlantWithInterval_showsNeverWateredLabel() {
        val plant = Plant(id = 1L, name = "Monstera", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plants = listOf(plant))

        composeTestRule.setContent {
            PlantListScreen(
                viewModel = viewModel,
                onNavigateToPlant = {},
                onNavigateToAdd = {},
                onNavigateToSettings = {}
            )
        }

        composeTestRule.onNodeWithText("Never watered").assertIsDisplayed()
    }

    @Test
    fun neverFertilizedPlantWithInterval_showsNeverFertilizedLabel() {
        val plant = Plant(
            id = 1L,
            name = "Monstera",
            fertilizingIntervalDays = 14,
            createdAt = 0L,
            updatedAt = 0L
        )
        val viewModel = makeViewModel(plants = listOf(plant))

        composeTestRule.setContent {
            PlantListScreen(
                viewModel = viewModel,
                onNavigateToPlant = {},
                onNavigateToAdd = {},
                onNavigateToSettings = {}
            )
        }

        composeTestRule.onNodeWithText("Never fertilized").assertIsDisplayed()
    }
}
