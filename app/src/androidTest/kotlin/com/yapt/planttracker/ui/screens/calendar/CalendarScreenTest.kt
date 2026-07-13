package com.yapt.planttracker.ui.screens.calendar

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class CalendarScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var careLogRepo: CareLogRepository

    private fun makeViewModel(plants: List<Plant> = emptyList()): CalendarViewModel {
        val plantRepo = mockk<PlantRepository>()
        careLogRepo = mockk()
        val plantPhotoRepo = mockk<PlantPhotoRepository>()
        val dataStore = mockk<DataStore<Preferences>> {
            every { data } returns flowOf(emptyPreferences())
        }
        every { plantRepo.getAllPlants() } returns flowOf(plants)
        every { careLogRepo.logCount } returns flowOf(0)
        coEvery { careLogRepo.getLastLogOfType(any(), CareType.WATER) } returns null
        coEvery { careLogRepo.getLastLogOfType(any(), CareType.FERTILIZE) } returns null
        coEvery { careLogRepo.getCareLogCount(any()) } returns 0
        val application = ApplicationProvider.getApplicationContext<Application>()
        val quickLogUseCase = QuickLogUseCase(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore)
        return CalendarViewModel(
            application,
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            dataStore,
            quickLogUseCase
        )
    }

    private val todayTag get() = "calendar_day_${LocalDate.now()}"
    private val todayBadgeTag get() = "calendar_badge_${LocalDate.now()}"
    private val overdueStateMatcher = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "overdue")

    @Test
    fun emptyMonth_showsEmptyState() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            CalendarScreen(viewModel = viewModel, onNavigateToPlant = {})
        }

        composeTestRule.onNodeWithText("No care scheduled this month.").assertIsDisplayed()
    }

    @Test
    fun dayDueToday_showsBadgeWithCount() {
        val plant = Plant(id = 1L, name = "Monstera", wateringIntervalDays = 5, createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(listOf(plant))
        val fiveDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(5)
        coEvery { careLogRepo.getLastLogOfType(1L, CareType.WATER) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fiveDaysAgo)

        composeTestRule.setContent {
            CalendarScreen(viewModel = viewModel, onNavigateToPlant = {})
        }

        composeTestRule
            .onNode(
                hasTestTag(todayBadgeTag).and(hasContentDescription("1 plant due")),
                useUnmergedTree = true
            )
            .assertExists()
    }

    @Test
    fun dayDueToday_badgeCollapsesToSingleSemanticsNode() {
        // Regression coverage: before clearAndSetSemantics, the badge's plural contentDescription
        // and the inner count Text's own text node were two separate accessibility nodes, so
        // TalkBack announced the count twice (e.g. "1 plant due", then "1"). Asserting the badge
        // node has zero semantics children proves the subtree collapsed to a single node.
        val plant = Plant(id = 1L, name = "Monstera", wateringIntervalDays = 5, createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(listOf(plant))
        val fiveDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(5)
        coEvery { careLogRepo.getLastLogOfType(1L, CareType.WATER) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fiveDaysAgo)

        composeTestRule.setContent {
            CalendarScreen(viewModel = viewModel, onNavigateToPlant = {})
        }

        // Deliberately queries the merged tree (no useUnmergedTree): SemanticsNode.getChildren()
        // only hides a clearAndSetSemantics node's descendants when includeReplacedSemantics is
        // false, which is the merged-tree default. The unmerged tree always exposes replaced
        // semantics (by design, for debugging), so it would report the inner Text as a child
        // regardless of clearAndSetSemantics and defeat this regression check.
        composeTestRule
            .onNode(hasTestTag(todayBadgeTag))
            .onChildren()
            .assertCountEquals(0)
    }

    @Test
    fun todayBadge_isRed_whenOverdue() {
        val plant = Plant(id = 1L, name = "Monstera", wateringIntervalDays = 1, createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(listOf(plant))
        val threeDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3)
        coEvery { careLogRepo.getLastLogOfType(1L, CareType.WATER) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = threeDaysAgo)

        composeTestRule.setContent {
            CalendarScreen(viewModel = viewModel, onNavigateToPlant = {})
        }

        composeTestRule
            .onNode(
                hasTestTag(todayBadgeTag).and(overdueStateMatcher),
                useUnmergedTree = true
            )
            .assertExists()
    }

    @Test
    fun todayBadge_isGreen_whenDueButNotOverdue() {
        val plant = Plant(id = 1L, name = "Monstera", wateringIntervalDays = 5, createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(listOf(plant))
        val fiveDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(5)
        coEvery { careLogRepo.getLastLogOfType(1L, CareType.WATER) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fiveDaysAgo)

        composeTestRule.setContent {
            CalendarScreen(viewModel = viewModel, onNavigateToPlant = {})
        }

        composeTestRule
            .onNode(
                hasTestTag(todayBadgeTag).and(overdueStateMatcher),
                useUnmergedTree = true
            )
            .assertDoesNotExist()
    }

    @Test
    fun tappingMarkedDay_opensDaySheetWithPlant() {
        val plant = Plant(id = 1L, name = "Monstera", wateringIntervalDays = 5, createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(listOf(plant))
        val fiveDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(5)
        coEvery { careLogRepo.getLastLogOfType(1L, CareType.WATER) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fiveDaysAgo)

        composeTestRule.setContent {
            CalendarScreen(viewModel = viewModel, onNavigateToPlant = {})
        }

        composeTestRule.onNodeWithTag(todayTag).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Monstera").assertIsDisplayed()
    }

    @Test
    fun monthNavArrows_changeVisibleMonth() {
        val viewModel = makeViewModel()

        composeTestRule.setContent {
            CalendarScreen(viewModel = viewModel, onNavigateToPlant = {})
        }

        val nextMonthLabel = YearMonth.now().plusMonths(1).atDay(1)
            .format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))

        composeTestRule.onNodeWithContentDescription("Next month").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText(nextMonthLabel).assertIsDisplayed()
    }
}
