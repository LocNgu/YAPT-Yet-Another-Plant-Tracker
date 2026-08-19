package com.yapt.planttracker.ui.screens.plantlist

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlantListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeViewModel(
        plants: List<Plant> = emptyList(),
        rooms: List<String> = emptyList(),
        activeIssueCounts: Map<Long, Int> = emptyMap()
    ): PlantListViewModel {
        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        val plantPhotoRepo = mockk<PlantPhotoRepository>()
        val plantIssueRepo = mockk<PlantIssueRepository>()
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
        coEvery { plantIssueRepo.getActiveIssueCountForPlant(any()) } answers {
            activeIssueCounts[firstArg<Long>()] ?: 0
        }
        val application = ApplicationProvider.getApplicationContext<Application>()
        val quickLogUseCase = QuickLogUseCase(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, mockk<PlantDatabase>())
        return PlantListViewModel(
            application,
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            dataStore,
            quickLogUseCase,
            plantIssueRepo
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
    fun longPressPlant_showsBulkActionBarImmediately() {
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

        // The bulk action bar slides up on marking — no intermediate button. `assertExists`
        // checks tree membership (reliable) rather than pixel display of the bottom bar.
        composeTestRule.onNodeWithText("Move to Graveyard").assertExists()
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
    fun selectionMode_quickLogButtonsAreInert_soTapsCannotMisfireAQuickLog() {
        val monstera = Plant(id = 1L, name = "Monstera", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plants = listOf(monstera))

        composeTestRule.setContent {
            PlantListScreen(
                viewModel = viewModel,
                onNavigateToPlant = {},
                onNavigateToAdd = {},
                onNavigateToSettings = {}
            )
        }

        // Outside selection mode the quick-log buttons are present and actionable.
        composeTestRule.onNodeWithContentDescription("Quick water").assertExists()
        composeTestRule.onNodeWithContentDescription("Quick fertilize").assertExists()

        // Enter selection mode by long-pressing the plant.
        composeTestRule.onNodeWithText("Monstera").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()

        // While selecting, the quick-log buttons stay composed (so the card keeps its height) but
        // are disabled and dropped from the semantics tree (alpha 0 + clearAndSetSemantics), so
        // they expose no announced/actionable target. A tap over that region therefore can't
        // trigger a quick-log — it can only fall through to the card's selection toggle. We assert
        // the user-visible contract (the affordance is inert) rather than pixel-level tap geometry,
        // per the Compose-testing convention in CLAUDE.md.
        composeTestRule.onNodeWithContentDescription("Quick water").assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("Quick fertilize").assertDoesNotExist()
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

    @Test
    fun plantWithOneActiveIssue_showsIssueBadge() {
        val plant = Plant(id = 1L, name = "Monstera", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plants = listOf(plant), activeIssueCounts = mapOf(1L to 1))

        composeTestRule.setContent {
            PlantListScreen(
                viewModel = viewModel,
                onNavigateToPlant = {},
                onNavigateToAdd = {},
                onNavigateToSettings = {}
            )
        }

        val expectedDescription = InstrumentationRegistry.getInstrumentation().targetContext.resources
            .getQuantityString(R.plurals.cd_plant_card_active_issues, 1, 1)
        composeTestRule.onNodeWithContentDescription(expectedDescription).assertIsDisplayed()
    }

    @Test
    fun plantWithNoActiveIssues_hasNoIssueBadge() {
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

        val unexpectedDescription = InstrumentationRegistry.getInstrumentation().targetContext.resources
            .getQuantityString(R.plurals.cd_plant_card_active_issues, 1, 1)
        assertTrue(
            composeTestRule.onAllNodesWithContentDescription(unexpectedDescription)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }
}
