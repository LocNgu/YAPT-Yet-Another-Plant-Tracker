package com.yapt.planttracker.ui.screens.plantlist

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
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
        val dataStore = mockk<DataStore<Preferences>> {
            every { data } returns flowOf(emptyPreferences())
        }
        every { plantRepo.getAllPlants() } returns flowOf(plants)
        every { plantRepo.getAllRooms() } returns flowOf(rooms)
        every { careLogRepo.logCount } returns flowOf(0)
        coEvery { careLogRepo.getLastLogOfType(any(), CareType.WATER) } returns null
        coEvery { careLogRepo.getLastLogOfType(any(), CareType.FERTILIZE) } returns null
        coEvery { careLogRepo.getCareLogCount(any()) } returns 0
        return PlantListViewModel(
            ApplicationProvider.getApplicationContext<Application>(),
            plantRepo,
            careLogRepo,
            dataStore
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
}
