package com.yapt.planttracker.ui.screens.graveyard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.Plant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GraveyardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun plant(id: Long, name: String) = Plant(
        id = id,
        name = name,
        createdAt = 1000L,
        updatedAt = 1000L,
        archivedAt = 5000L
    )

    private fun makeViewModel(plants: List<Plant> = emptyList()): GraveyardViewModel {
        val plantRepo = mockk<PlantRepository>()
        every { plantRepo.getArchivedPlants() } returns MutableStateFlow(plants)
        coEvery { plantRepo.restorePlant(any()) } just runs
        coEvery { plantRepo.deletePlant(any()) } just runs
        coEvery { plantRepo.deleteAllArchived() } just runs
        return GraveyardViewModel(plantRepo)
    }

    @Test
    fun emptyState_isDisplayed_whenNoArchivedPlants() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            GraveyardScreen(viewModel = viewModel, onNavigateBack = {})
        }

        composeTestRule.onNodeWithText("No plants in the graveyard").assertIsDisplayed()
    }

    @Test
    fun archivedPlantName_isDisplayed() {
        val viewModel = makeViewModel(plants = listOf(plant(1L, "Dead Fern")))

        composeTestRule.setContent {
            GraveyardScreen(viewModel = viewModel, onNavigateBack = {})
        }

        composeTestRule.onNodeWithText("Dead Fern").assertIsDisplayed()
    }

    @Test
    fun restoreButton_callsViewModel_restorePlant() {
        val plantRepo = mockk<PlantRepository>()
        every { plantRepo.getArchivedPlants() } returns MutableStateFlow(listOf(plant(1L, "Fern")))
        coEvery { plantRepo.restorePlant(any()) } just runs
        val viewModel = GraveyardViewModel(plantRepo)

        composeTestRule.setContent {
            GraveyardScreen(viewModel = viewModel, onNavigateBack = {})
        }

        composeTestRule.onNodeWithText("Restore").performClick()

        coVerify { plantRepo.restorePlant(1L) }
    }

    @Test
    fun deleteForeverButton_showsConfirmationDialog() {
        val viewModel = makeViewModel(plants = listOf(plant(1L, "Orchid")))

        composeTestRule.setContent {
            GraveyardScreen(viewModel = viewModel, onNavigateBack = {})
        }

        composeTestRule.onNodeWithContentDescription("Delete permanently").performClick()

        composeTestRule.onNodeWithText("Delete Permanently?").assertIsDisplayed()
    }

    @Test
    fun overflowMenu_emptyGraveyard_showsDialog() {
        val viewModel = makeViewModel(plants = listOf(plant(1L, "Cactus")))

        composeTestRule.setContent {
            GraveyardScreen(viewModel = viewModel, onNavigateBack = {})
        }

        composeTestRule.onNodeWithContentDescription("More options").performClick()
        composeTestRule.onNodeWithText("Empty Graveyard").performClick()

        composeTestRule.onNodeWithText("Empty Graveyard?").assertIsDisplayed()
    }
}
