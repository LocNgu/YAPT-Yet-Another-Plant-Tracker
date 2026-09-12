package com.yapt.planttracker.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PostWateringReminderDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dialog_displaysReminderAndDismisses() {
        var dismissed = false
        composeTestRule.setContent {
            PostWateringReminderDialog(onDismiss = { dismissed = true })
        }

        composeTestRule.onNodeWithText("Check for standing water").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pour away any excess water collected in your plant saucers.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Dismiss").performClick()

        assertTrue(dismissed)
    }
}
