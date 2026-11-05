package com.yapt.planttracker.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.wateringStrengthMode
import com.yapt.planttracker.ui.theme.YaptTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The 3-way per-plant strength control (#700): asserts selected state and labels, never structure. */
@RunWith(AndroidJUnit4::class)
class WateringStrengthSettingTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun setContent(pinned: Boolean = false, override: SeasonalAmplitude? = null) {
        composeTestRule.setContent {
            var isPinned by mutableStateOf(pinned)
            var custom by mutableStateOf(override)
            YaptTheme {
                WateringStrengthSetting(
                    mode = wateringStrengthMode(isPinned, custom),
                    customStrength = custom,
                    callbacks = WateringStrengthCallbacks(
                        onSelectAppDefault = {
                            isPinned = false
                            custom = null
                        },
                        onSelectCustom = {
                            isPinned = false
                            custom = it
                        },
                        onSelectPin = {
                            isPinned = true
                            custom = null
                        }
                    )
                )
            }
        }
    }

    @Test
    fun appDefaultIsSelectedByDefaultAndHidesTheStrengthOptions() {
        setContent()

        composeTestRule.onNodeWithText("App default").assertIsSelected()
        composeTestRule.onNodeWithText("Custom").assertIsNotSelected()
        composeTestRule.onNodeWithText("Pin").assertIsNotSelected()
        composeTestRule.onNodeWithText("Mild").assertDoesNotExist()
    }

    @Test
    fun customOffersMildStandardStrongAndNeverOff() {
        setContent(override = SeasonalAmplitude.MILD)

        composeTestRule.onNodeWithText("Custom").assertIsSelected()
        composeTestRule.onNodeWithText("Mild").assertIsSelected()
        composeTestRule.onNodeWithText("Standard").assertIsNotSelected()
        composeTestRule.onNodeWithText("Strong").assertIsNotSelected()
        composeTestRule.onNodeWithText("Off").assertDoesNotExist()
    }

    @Test
    fun choosingCustomStartsAtStandardAndThenPinReplacesIt() {
        setContent()

        composeTestRule.onNodeWithText("Custom").performClick()
        composeTestRule.onNodeWithText("Standard").assertIsSelected()
        composeTestRule.onNodeWithText("Strong").performClick()
        composeTestRule.onNodeWithText("Strong").assertIsSelected()

        composeTestRule.onNodeWithText("Pin").performClick()
        composeTestRule.onNodeWithText("Pin").assertIsSelected()
        composeTestRule.onNodeWithText("Custom").assertIsNotSelected()
        composeTestRule.onNodeWithText("Strong").assertDoesNotExist()
    }

    @Test
    fun aPinnedPlantShowsPinSelected() {
        setContent(pinned = true)

        composeTestRule.onNodeWithText("Pin").assertIsSelected()
        composeTestRule.onNodeWithText("App default").assertIsNotSelected()
    }

    @Test
    fun aStrayCustomOffReadsAsPin() {
        setContent(override = SeasonalAmplitude.OFF)

        composeTestRule.onNodeWithText("Pin").assertIsSelected()
        composeTestRule.onNodeWithText("Off").assertDoesNotExist()
    }
}
