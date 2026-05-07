package com.yapt.planttracker.ui.screens.addplant

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.data.repository.PlantRepository
import io.mockk.mockk
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddEditPlantScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeViewModel(): AddEditPlantViewModel {
        val plantRepo = mockk<PlantRepository>()
        return AddEditPlantViewModel(plantRepo, plantId = null)
    }

    @Test
    fun saveFab_isDisplayedInInitialState() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddEditPlantScreen(
                viewModel = viewModel,
                onNavigateBack = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Save").assertIsDisplayed()
    }

    @Test
    fun emptyName_showsValidationSnackbar() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddEditPlantScreen(
                viewModel = viewModel,
                onNavigateBack = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Save").performClick()

        composeTestRule.onNodeWithText("Plant name is required").assertIsDisplayed()
    }
}
