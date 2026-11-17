package com.yapt.planttracker.ui.screens.addplant

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.Plant
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AddPlantWizardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val plantRepo = mockk<PlantRepository>()
    private var navigatedBack = false

    private fun str(id: Int, vararg args: Any): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private fun launch(): AddEditPlantViewModel {
        every { plantRepo.getAllRooms() } returns flowOf(emptyList())
        val photoRepo = mockk<PlantPhotoRepository>()
        coEvery { photoRepo.addPhotos(any()) } returns Unit
        val viewModel = AddEditPlantViewModel(plantRepo, photoRepo, plantId = null)
        composeTestRule.setContent {
            AddEditPlantScreen(viewModel = viewModel, onNavigateBack = { navigatedBack = true })
        }
        return viewModel
    }

    private fun next() = composeTestRule.onNodeWithText(str(R.string.add_plant_next)).performClick()

    private fun assertStep(n: Int) =
        composeTestRule.onNodeWithText(str(R.string.add_plant_step_progress, n, 4)).assertIsDisplayed()

    @Test
    fun startsOnStepOneAndNextWalksThroughTheSteps() {
        launch()
        assertStep(1)
        composeTestRule.onNodeWithText(str(R.string.field_plant_name)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.cd_add_photo)).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.field_plant_name)).performTextInput("Monstera")

        next()
        assertStep(2)
        composeTestRule.onNodeWithContentDescription(str(R.string.cd_add_photo)).assertIsDisplayed()

        next()
        assertStep(3)
        composeTestRule.onNodeWithText(str(R.string.watering_reminder_label)).assertIsDisplayed()

        next()
        assertStep(4)
        composeTestRule.onNodeWithText(str(R.string.add_plant_finish)).assertIsDisplayed()
    }

    @Test
    fun blankNameBlocksNextAndShowsTheMessage() {
        launch()

        next()

        composeTestRule.onNodeWithText(str(R.string.add_plant_name_required)).assertIsDisplayed()
        assertStep(1)
    }

    @Test
    fun backButtonsReturnToThePreviousStep() {
        val viewModel = launch()
        composeTestRule.runOnIdle { viewModel.goToStep(AddPlantStep.CARE) }
        assertStep(3)

        composeTestRule.onNodeWithContentDescription(str(R.string.cd_back)).performClick()
        assertStep(2)

        composeTestRule.runOnIdle { viewModel.goToStep(AddPlantStep.CARE) }
        Espresso.pressBack()
        assertStep(2)
        assertFalse(navigatedBack)
    }

    @Test
    fun backOnTheFirstStepWithNothingEnteredLeavesWithoutADialog() {
        launch()

        composeTestRule.onNodeWithContentDescription(str(R.string.cd_back)).performClick()

        assertTrue(navigatedBack)
        composeTestRule.onNodeWithText(str(R.string.add_plant_discard_title)).assertDoesNotExist()
    }

    @Test
    fun backOnTheFirstStepWithInputAsksBeforeDiscarding() {
        val viewModel = launch()
        composeTestRule.runOnIdle { viewModel.setName("Fern") }

        composeTestRule.onNodeWithContentDescription(str(R.string.cd_back)).performClick()

        composeTestRule.onNodeWithText(str(R.string.add_plant_discard_title)).assertIsDisplayed()
        assertFalse(navigatedBack)

        composeTestRule.onNodeWithText(str(R.string.add_plant_discard_keep_editing)).performClick()
        composeTestRule.onNodeWithText(str(R.string.add_plant_discard_title)).assertDoesNotExist()
        assertFalse(navigatedBack)

        Espresso.pressBack()
        composeTestRule.onNodeWithText(str(R.string.add_plant_discard_confirm)).performClick()
        assertTrue(navigatedBack)
    }

    @Test
    fun longTextTypedIntoNameAndNotesIsKeptInFull() {
        val viewModel = launch()
        val longName = "Monstera deliciosa variegata from grandma"
        val longNotes = "Keep out of direct sun, rotate a quarter turn each week, mist rarely."

        composeTestRule.onNodeWithText(str(R.string.field_plant_name)).performTextInput(longName)
        composeTestRule.onNodeWithText(longName).assertIsDisplayed()
        assertEquals(longName, viewModel.name)

        next()
        next()
        next()
        composeTestRule.onNodeWithText(str(R.string.field_notes)).performTextInput(longNotes)
        composeTestRule.onNodeWithText(longNotes).assertIsDisplayed()
        assertEquals(longNotes, viewModel.notes)
    }

    @Test
    fun reviewShowsTheSummaryAndALineJumpsBackToItsStep() {
        val viewModel = launch()
        composeTestRule.runOnIdle {
            viewModel.setName("Fern")
            viewModel.setWateringIntervalEnabled(true)
            viewModel.setWateringIntervalDays(7)
            viewModel.goToStep(AddPlantStep.REVIEW)
        }

        composeTestRule.onNodeWithText(str(R.string.add_plant_summary_name, "Fern")).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.add_plant_summary_watering_on, 7)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.add_plant_summary_fertilizing_off)).assertIsDisplayed()

        composeTestRule.onNodeWithText(str(R.string.add_plant_summary_watering_on, 7)).performClick()

        assertStep(3)
    }

    @Test
    fun addPlantOnReviewSavesOnceAndLeaves() {
        val saved = slot<Plant>()
        coEvery { plantRepo.addPlant(capture(saved)) } returns 9L
        val viewModel = launch()
        composeTestRule.runOnIdle {
            viewModel.setName("Fern")
            viewModel.goToStep(AddPlantStep.REVIEW)
        }
        coVerify(exactly = 0) { plantRepo.addPlant(any()) }

        composeTestRule.onNodeWithText(str(R.string.field_notes)).performScrollTo().performTextInput("Gift")
        composeTestRule.onNodeWithText(str(R.string.add_plant_finish)).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) { navigatedBack }
        coVerify(exactly = 1) { plantRepo.addPlant(any()) }
        assertEquals("Fern", saved.captured.name)
        assertEquals("Gift", saved.captured.notes)
    }

    private fun stateIs(id: Int) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, str(id))

    private fun moreOptionsHeader() = composeTestRule.onNodeWithText(str(R.string.add_plant_more_options))

    private fun goToCare(viewModel: AddEditPlantViewModel) {
        composeTestRule.runOnIdle {
            viewModel.setName("Fern")
            viewModel.goToStep(AddPlantStep.CARE)
        }
    }

    @Test
    fun careStepHidesTheRareSettingsUntilMoreOptionsIsOpened() {
        val viewModel = launch()
        goToCare(viewModel)

        composeTestRule.onNodeWithText(str(R.string.watering_reminder_label)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.fertilizing_reminder_label)).assertIsDisplayed()
        moreOptionsHeader().assertIsDisplayed()
            .assert(stateIs(R.string.add_plant_more_options_collapsed))
        composeTestRule.onNodeWithContentDescription("Dormancy window").assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.repotting_reminder_label)).assertDoesNotExist()

        moreOptionsHeader().performClick()

        moreOptionsHeader()
            .assert(stateIs(R.string.add_plant_more_options_expanded))
        composeTestRule.onNodeWithContentDescription("Dormancy window").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.repotting_reminder_label)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun valuesSetInMoreOptionsSurviveCollapsingAndShowOnReview() {
        val viewModel = launch()
        goToCare(viewModel)
        moreOptionsHeader().performClick()
        composeTestRule.runOnIdle {
            viewModel.setDormancyWindow(11, 2)
            viewModel.setRepottingIntervalEnabled(true)
            viewModel.setRepottingIntervalMonths(18)
        }
        moreOptionsHeader().performClick()
        composeTestRule.onNodeWithContentDescription("Dormancy window").assertDoesNotExist()

        composeTestRule.runOnIdle { viewModel.goToStep(AddPlantStep.REVIEW) }

        composeTestRule.onNodeWithText(str(R.string.add_plant_summary_dormancy, "Nov", "Feb")).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext.resources
                .getQuantityString(R.plurals.add_plant_summary_repotting_on, 18, 18)
        ).assertIsDisplayed()
    }

    @Test
    fun reviewShowsNoMoreOptionsLinesForDefaults() {
        val viewModel = launch()
        composeTestRule.runOnIdle {
            viewModel.setName("Fern")
            viewModel.goToStep(AddPlantStep.REVIEW)
        }

        composeTestRule.onNodeWithText("Dormancy", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Repotting", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(str(R.string.add_plant_summary_watering_off)).assertIsDisplayed()
    }

    @Test
    fun aMoreOptionsReviewLineJumpsBackToCareWithTheSectionOpen() {
        val viewModel = launch()
        composeTestRule.runOnIdle {
            viewModel.setName("Fern")
            viewModel.setDormancyWindow(11, 2)
            viewModel.goToStep(AddPlantStep.REVIEW)
        }

        composeTestRule.onNodeWithText(str(R.string.add_plant_summary_dormancy, "Nov", "Feb")).performClick()

        assertStep(3)
        composeTestRule.onNodeWithContentDescription("Dormancy window").performScrollTo().assertIsDisplayed()
    }
}
