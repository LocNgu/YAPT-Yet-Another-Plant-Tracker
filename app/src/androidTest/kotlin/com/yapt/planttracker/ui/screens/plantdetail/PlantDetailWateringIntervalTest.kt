package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantCareStatus
import com.yapt.planttracker.domain.model.WateringScheduleMode
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.LiveWateringInterval
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** #927 (product ADR-0068): the live-interval hero line and the Water-tab seasonal-adjustment line. */
@RunWith(AndroidJUnit4::class)
class PlantDetailWateringIntervalTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun status(mode: WateringScheduleMode = WateringScheduleMode.NORMAL) = PlantCareStatus(
        plant = Plant(id = 1L, name = "Fern", createdAt = 0L, updatedAt = 0L),
        lastWateredAt = null,
        lastFertilizedAt = null,
        daysSinceLastWatering = null,
        nextWateringDueAt = null,
        isOverdue = false,
        isDueSoon = false,
        nextFertilizingDueAt = null,
        isFertilizingOverdue = false,
        isFertilizingDueSoon = false,
        totalCareLogs = 0,
        wateringScheduleMode = mode
    )

    private val adjusted = LiveWateringInterval(
        effectiveDays = 9,
        adjustment = LiveWateringInterval.SeasonalAdjustment(FertilizingSeason.WINTER, baseDays = 8)
    )
    private val unadjusted = LiveWateringInterval(effectiveDays = 9)

    private fun showHero(
        status: PlantCareStatus? = status(),
        live: LiveWateringInterval? = adjusted,
        dormantDays: Int? = null
    ) = composeTestRule.setContent {
        WateringHeroLine(status = status, live = live, dormantWateringIntervalDays = dormantDays)
    }

    @Test
    fun hero_adjustedDay_showsLiveValueSeasonAndBase() {
        showHero()

        composeTestRule.onNodeWithText("💧 Every 9 days · winter (base 8d)").assertIsDisplayed()
    }

    @Test
    fun hero_unadjustedDay_showsOnlyTheLiveValue() {
        showHero(live = unadjusted)

        composeTestRule.onNodeWithText("💧 Every 9 days").assertIsDisplayed()
        composeTestRule.onNodeWithText("base", substring = true).assertDoesNotExist()
    }

    @Test
    fun hero_suspendedDormancy_showsDormantWithoutSeasonalText() {
        showHero(status = status(WateringScheduleMode.DORMANT_SUSPENDED))

        composeTestRule.onNodeWithText("💧 Dormant").assertIsDisplayed()
        composeTestRule.onNodeWithText("base", substring = true).assertDoesNotExist()
    }

    @Test
    fun hero_dormantCadence_showsWeeksWithPlurals() {
        showHero(status = status(WateringScheduleMode.DORMANT_CADENCE), dormantDays = 21)

        composeTestRule.onNodeWithText("💧 Every 3 weeks · dormant").assertIsDisplayed()
        composeTestRule.onNodeWithText("base", substring = true).assertDoesNotExist()
    }

    @Test
    fun hero_dormantCadenceOfOneWeek_isSingular() {
        showHero(status = status(WateringScheduleMode.DORMANT_CADENCE), dormantDays = 7)

        composeTestRule.onNodeWithText("💧 Every 1 week · dormant").assertIsDisplayed()
    }

    @Test
    fun hero_withoutLoadedStatus_showsNothing() {
        showHero(status = null)

        composeTestRule.onNodeWithText("Every", substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText("Dormant", substring = true).assertDoesNotExist()
    }

    @Test
    fun hero_withoutAWateringInterval_isHidden() {
        showHero(live = null)

        composeTestRule.onNodeWithText("Every", substring = true).assertDoesNotExist()
    }

    private fun showAdjustmentLine(
        status: PlantCareStatus? = status(),
        live: LiveWateringInterval? = adjusted,
        onWhyThisDate: (() -> Unit)? = {}
    ) = composeTestRule.setContent {
        WateringSeasonalAdjustmentLine(status = status, live = live, onWhyThisDate = onWhyThisDate)
    }

    @Test
    fun adjustmentLine_namesTheSeasonAndTheBase() {
        showAdjustmentLine()

        composeTestRule.onNodeWithText("Adjusted for winter · base 8 days").assertIsDisplayed()
    }

    @Test
    fun adjustmentLine_aBaseOfOneDayIsSingular() {
        showAdjustmentLine(
            live = LiveWateringInterval(
                effectiveDays = 2,
                adjustment = LiveWateringInterval.SeasonalAdjustment(FertilizingSeason.SUMMER, baseDays = 1)
            )
        )

        composeTestRule.onNodeWithText("Adjusted for summer · base 1 day").assertIsDisplayed()
    }

    @Test
    fun adjustmentLine_tapOpensWhyThisDateWhenThereIsADueDate() {
        var taps = 0
        showAdjustmentLine(onWhyThisDate = { taps++ })

        composeTestRule.onNodeWithText("Adjusted for winter · base 8 days").performClick()

        assertEquals(1, taps)
    }

    @Test
    fun adjustmentLine_isActionableWhenThereIsADueDate() {
        showAdjustmentLine()

        composeTestRule.onNodeWithText("Adjusted for winter · base 8 days").assertHasClickAction()
    }

    @Test
    fun adjustmentLine_isPlainTextWithoutADueDate() {
        showAdjustmentLine(onWhyThisDate = null)

        composeTestRule.onNodeWithText("Adjusted for winter · base 8 days").assertIsDisplayed()
        composeTestRule.onNodeWithText("Adjusted for winter · base 8 days").assertHasNoClickAction()
    }

    @Test
    fun adjustmentLine_isHiddenWhenThereIsNoAdjustment() {
        showAdjustmentLine(live = unadjusted)

        composeTestRule.onNodeWithText("Adjusted for", substring = true).assertDoesNotExist()
    }

    @Test
    fun adjustmentLine_isHiddenWhileDormant() {
        showAdjustmentLine(status = status(WateringScheduleMode.DORMANT_CADENCE))

        composeTestRule.onNodeWithText("Adjusted for", substring = true).assertDoesNotExist()
    }

    @Test
    fun adjustmentLine_isHiddenUntilTheStatusLoads() {
        showAdjustmentLine(status = null)

        composeTestRule.onNodeWithText("Adjusted for", substring = true).assertDoesNotExist()
    }
}
