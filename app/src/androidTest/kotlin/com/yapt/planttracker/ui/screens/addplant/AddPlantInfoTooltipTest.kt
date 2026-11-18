package com.yapt.planttracker.ui.screens.addplant

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class AddPlantInfoTooltipTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun str(id: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun plantRepo(): PlantRepository {
        val repo = mockk<PlantRepository>()
        every { repo.getAllRooms() } returns flowOf(emptyList())
        return repo
    }

    private fun launchWizard(): AddEditPlantViewModel {
        val viewModel = AddEditPlantViewModel(plantRepo(), mockk<PlantPhotoRepository>(), plantId = null)
        composeTestRule.setContent { AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {}) }
        return viewModel
    }

    private fun launchEdit(): AddEditPlantViewModel {
        val repo = plantRepo()
        val plant = Plant(
            id = 1L,
            name = "Monstera",
            wateringIntervalDays = 7,
            wateringBaseIntervalDays = 8.0,
            createdAt = 0L,
            updatedAt = 0L
        )
        every { repo.getPlantById(1L) } returns flowOf(plant)
        val dataStore = mockk<DataStore<Preferences>> {
            every { data } returns flowOf(
                preferencesOf(SettingsKeys.SEASONAL_AMPLITUDE to SeasonalAmplitude.STANDARD.name)
            )
        }
        val viewModel = AddEditPlantViewModel(
            repo,
            mockk<PlantPhotoRepository>(),
            1L,
            dataStore,
            null,
            { Hemisphere.NORTHERN },
            { LocalDate.of(2026, 1, 15).atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        )
        composeTestRule.setContent { AddEditPlantScreen(viewModel = viewModel, onNavigateBack = {}) }
        return viewModel
    }

    private fun goToStep(viewModel: AddEditPlantViewModel, step: AddPlantStep) {
        composeTestRule.runOnIdle {
            viewModel.setName("Fern")
            viewModel.goToStep(step)
        }
    }

    private fun assertTooltipOnTap(contentDescription: Int, text: Int, viewModel: AddEditPlantViewModel) {
        val before = viewModel.uiState.value
        composeTestRule.onNodeWithContentDescription(str(contentDescription)).performScrollTo().performClick()
        composeTestRule.onNodeWithText(str(text)).assertIsDisplayed()
        assertEquals(before, viewModel.uiState.value)
    }

    @Test
    fun eachWizardStepShowsItsIntro() {
        val viewModel = launchWizard()
        composeTestRule.onNodeWithText(str(R.string.add_plant_intro_details)).assertIsDisplayed()

        goToStep(viewModel, AddPlantStep.PHOTO)
        composeTestRule.onNodeWithText(str(R.string.add_plant_intro_photo)).assertIsDisplayed()

        goToStep(viewModel, AddPlantStep.CARE)
        composeTestRule.onNodeWithText(str(R.string.add_plant_intro_care)).assertIsDisplayed()

        goToStep(viewModel, AddPlantStep.REVIEW)
        composeTestRule.onNodeWithText(str(R.string.add_plant_intro_review)).assertIsDisplayed()
    }

    @Test
    fun wizardCareStepInfoIconsShowTheirTextAndChangeNothing() {
        val viewModel = launchWizard()
        composeTestRule.runOnIdle {
            viewModel.setMoreOptionsExpanded(true)
            viewModel.setRepottingIntervalEnabled(true)
        }
        goToStep(viewModel, AddPlantStep.CARE)

        assertTooltipOnTap(R.string.info_strength_cd, R.string.info_strength_text, viewModel)
        dismissTooltip()
        assertTooltipOnTap(R.string.info_dormancy_cd, R.string.info_dormancy_text, viewModel)
        dismissTooltip()
        assertTooltipOnTap(R.string.info_repotting_cd, R.string.info_repotting_text, viewModel)
        dismissTooltip()
        assertTooltipOnTap(R.string.info_repotting_seasons_cd, R.string.info_repotting_seasons_text, viewModel)
    }

    @Test
    fun editPlantShowsTheSameInfoIcons() {
        val viewModel = launchEdit()
        composeTestRule.runOnIdle { viewModel.setRepottingIntervalEnabled(true) }

        assertTooltipOnTap(R.string.info_strength_cd, R.string.info_strength_text, viewModel)
        dismissTooltip()
        assertTooltipOnTap(R.string.info_dormancy_cd, R.string.info_dormancy_text, viewModel)
        dismissTooltip()
        assertTooltipOnTap(R.string.info_repotting_cd, R.string.info_repotting_text, viewModel)
        dismissTooltip()
        assertTooltipOnTap(R.string.info_repotting_seasons_cd, R.string.info_repotting_seasons_text, viewModel)
    }

    private fun dismissTooltip() {
        Espresso.pressBack()
        composeTestRule.waitForIdle()
    }
}
