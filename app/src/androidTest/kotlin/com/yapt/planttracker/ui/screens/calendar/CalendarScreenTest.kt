package com.yapt.planttracker.ui.screens.calendar

import android.app.Application
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
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
import com.yapt.planttracker.data.db.PlantDatabase
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
import org.junit.Assert.assertEquals
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
        val quickLogUseCase = QuickLogUseCase(application, plantRepo, careLogRepo, plantPhotoRepo, dataStore, mockk<PlantDatabase>(), mockk(relaxed = true))
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
    fun dayDueToday_badgeCountNotAnnouncedAsSeparateText() {
        // Regression coverage: before clearAndSetSemantics, the badge's plural contentDescription
        // and the inner count Text were two separate accessibility announcements, so TalkBack
        // read the count twice (e.g. "1 plant due", then "1").
        //
        // The badge node itself cannot be asserted on directly in either semantics tree: the
        // day cell's clickable merges descendants, and TestTag never merges into ancestors, so
        // the badge tag is absent from the merged tree; the unmerged tree in turn exposes
        // clearAndSetSemantics-replaced descendants by design, so a children-count check can
        // never pass there. Assert on the merged day-cell node instead — it must carry the
        // badge's contentDescription, and its merged text must contain only the day number
        // (proving the badge digit was cleared and is not announced separately).
        val plant = Plant(id = 1L, name = "Monstera", wateringIntervalDays = 5, createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(listOf(plant))
        val fiveDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(5)
        coEvery { careLogRepo.getLastLogOfType(1L, CareType.WATER) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fiveDaysAgo)

        composeTestRule.setContent {
            CalendarScreen(viewModel = viewModel, onNavigateToPlant = {})
        }

        val dayCell = composeTestRule.onNodeWithTag(todayTag)
        dayCell.assert(hasContentDescription("1 plant due"))
        val mergedTexts = dayCell.fetchSemanticsNode().config[SemanticsProperties.Text].map { it.text }
        assertEquals(listOf(LocalDate.now().dayOfMonth.toString()), mergedTexts)
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
    fun liquidFertilizerPlant_daySheet_showsBothWaterOnlyAndCombinedButtons() {
        // Regression: a liquid-fertilizer plant landing on a day (via its watering due date)
        // must offer a water-only quick-log button in addition to the combined
        // water+fertilize button, mirroring PlantCard, so the user can water without
        // fertilizing when only watering is due.
        val plant = Plant(
            id = 1L,
            name = "Pothos",
            wateringIntervalDays = 5,
            fertilizingIntervalDays = 30,
            useLiquidFertilizer = true,
            createdAt = 0L,
            updatedAt = 0L
        )
        val viewModel = makeViewModel(listOf(plant))
        val fiveDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(5)
        coEvery { careLogRepo.getLastLogOfType(1L, CareType.WATER) } returns
            CareLog(plantId = 1L, careType = CareType.WATER, loggedAt = fiveDaysAgo)

        composeTestRule.setContent {
            CalendarScreen(viewModel = viewModel, onNavigateToPlant = {})
        }

        composeTestRule.onNodeWithTag(todayTag).performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Quick water").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Quick fertilize").assertIsDisplayed()
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
