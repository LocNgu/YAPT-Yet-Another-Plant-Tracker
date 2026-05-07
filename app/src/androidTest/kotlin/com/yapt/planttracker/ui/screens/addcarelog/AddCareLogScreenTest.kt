package com.yapt.planttracker.ui.screens.addcarelog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringFeedback
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

// All fields have defaults — no validation error path exists; this test verifies the happy
// path is always reachable.
@RunWith(AndroidJUnit4::class)
class AddCareLogScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun makeViewModel(): AddCareLogViewModel {
        val careLogRepo = mockk<CareLogRepository>()
        val plantRepo = mockk<PlantRepository>()
        val plant = Plant(id = 1L, name = "TestPlant", createdAt = 0L, updatedAt = 0L)
        every { plantRepo.getPlantById(1L) } returns flowOf(plant)
        coEvery { careLogRepo.addLog(any()) } returns 1L
        coEvery { careLogRepo.getLastTwoWaterings(any()) } returns emptyList()
        return AddCareLogViewModel(careLogRepo, plantRepo, plantId = 1L, careLogId = 0L)
    }

    @Test
    fun waterCareType_isSelectedByDefault() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddCareLogScreen(
                viewModel = viewModel,
                onNavigateBack = {}
            )
        }

        composeTestRule
            .onNode(hasText("Water", substring = true) and isSelected())
            .assertIsDisplayed()
    }

    @Test
    fun justRightFeedbackChip_isSelectedByDefault() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            AddCareLogScreen(
                viewModel = viewModel,
                onNavigateBack = {}
            )
        }

        composeTestRule
            .onNode(hasText(WateringFeedback.JUST_RIGHT.displayName, substring = true) and isSelected())
            .assertIsDisplayed()
    }
}
