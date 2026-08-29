package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantIssue
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PlantDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val mockQuickLogUseCase: QuickLogUseCase = mockk(relaxed = true)

    /**
     * Real in-memory Room database so `reportIssue()`'s `database.withTransaction { ... }` (fix for
     * #567's orphan-`CustomReminder` review finding) has a real `PlantDatabase` to run its
     * transaction against — the repos passed to each ViewModel stay mockk stubs, mirroring how
     * `QuickLogUseCaseBulkLogTest`/`DemoDataSeederTest` never mock `withTransaction` itself.
     */
    private lateinit var database: PlantDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PlantDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    private val wateringAdjustmentRepo: WateringAdjustmentRepository by lazy {
        WateringAdjustmentRepository(database.wateringAdjustmentDao())
    }

    /**
     * Tabs (#436) are behind [FeatureFlagRegistry.PLANT_DETAIL_TABS], which defaults to off, so the
     * shared DataStore stub reports it ON — these tests exercise the flag-on tabbed UI.
     * `tabsFlagOff_showsClassicLayoutWithoutTabs` supplies its own flag-off store instead.
     */
    private val mockDataStore: DataStore<Preferences> = mockk<DataStore<Preferences>>().also {
        every { it.data } returns flowOf(
            mutablePreferencesOf(
                FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.PLANT_DETAIL_TABS) to true
            )
        )
    }

    private val flagsOffDataStore: DataStore<Preferences> = mockk<DataStore<Preferences>>().also {
        every { it.data } returns flowOf(emptyPreferences())
    }

    /**
     * Both [FeatureFlagRegistry.PLANT_DETAIL_TABS] and [FeatureFlagRegistry.SEASONAL_WATERING] on,
     * for the seasonal-curve preview chart tests (#579) — the chart only renders in the tabbed Water
     * layout, alongside the "Pin interval" switch from #578.
     */
    private val mockDataStoreWithSeasonal: DataStore<Preferences> = mockk<DataStore<Preferences>>().also {
        every { it.data } returns flowOf(
            mutablePreferencesOf(
                FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.PLANT_DETAIL_TABS) to true,
                FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING) to true
            )
        )
    }

    /**
     * [FeatureFlagRegistry.PLANT_DETAIL_TABS] and [FeatureFlagRegistry.ADAPTIVE_WATERING] on, for the
     * "Why this date?" sheet tests (#572).
     */
    private val mockDataStoreWithAdaptive: DataStore<Preferences> = mockk<DataStore<Preferences>>().also {
        every { it.data } returns flowOf(
            mutablePreferencesOf(
                FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.PLANT_DETAIL_TABS) to true,
                FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING) to true
            )
        )
    }

    /** Only [FeatureFlagRegistry.PLANT_DETAIL_TABS] on — `adaptive_watering` stays off (#572 degrade). */
    private val mockDataStoreTabsOnly: DataStore<Preferences> = mockk<DataStore<Preferences>>().also {
        every { it.data } returns flowOf(
            mutablePreferencesOf(
                FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.PLANT_DETAIL_TABS) to true
            )
        )
    }

    private val mockCustomReminderRepo: CustomReminderRepository = mockk<CustomReminderRepository>().also {
        every { it.getRemindersForPlant(any()) } returns flowOf(emptyList())
    }

    private val mockPlantIssueRepo: PlantIssueRepository = mockk<PlantIssueRepository>().also {
        every { it.getActiveIssuesForPlant(any()) } returns flowOf(emptyList())
    }

    /**
     * A WATER log far enough in the past to put a 7-day plant clearly outside
     * `CareSchedule.GAP_AGREEMENT_TOLERANCE`, so `PlantCareStatus.isWateringOnSchedule` is false and
     * the #586 reason prompt appears instead of the watering being logged straight away.
     */
    private fun str(id: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun offScheduleWaterLog(plantId: Long) = CareLog(
        id = 99L,
        plantId = plantId,
        careType = CareType.WATER,
        loggedAt = System.currentTimeMillis() - (14 * 24 * 60 * 60 * 1000L)
    )

    private fun makeViewModel(plant: Plant, careLogs: List<CareLog> = emptyList()): PlantDetailViewModel {
        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        val plantPhotoRepo = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(careLogs)
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        return PlantDetailViewModel(
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            plant.id,
            mockDataStore,
            mockQuickLogUseCase,
            mockCustomReminderRepo,
            mockPlantIssueRepo,
            database,
            wateringAdjustmentRepo
        )
    }

    @Test
    fun plantName_isDisplayed() {
        val plant = Plant(id = 1L, name = "Ficus", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        // Plant name appears in the content body.
        composeTestRule.onAllNodesWithText("Ficus")[0].assertIsDisplayed()
    }

    @Test
    fun logCareFab_isDisplayed() {
        val plant = Plant(id = 2L, name = "Pothos", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Log care").assertIsDisplayed()
    }

    @Test
    fun wateringChart_displaysWithMultipleWateringLogs() {
        val plant = Plant(id = 3L, name = "Snake Plant", createdAt = 0L, updatedAt = 0L)
        val dayInMs = 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()

        val careLogs = listOf(
            CareLog(
                id = 1L,
                plantId = 3L,
                careType = CareType.WATER,
                loggedAt = now - (5 * dayInMs)
            ),
            CareLog(
                id = 2L,
                plantId = 3L,
                careType = CareType.WATER,
                loggedAt = now - (3 * dayInMs)
            ),
            CareLog(
                id = 3L,
                plantId = 3L,
                careType = CareType.WATER,
                loggedAt = now
            )
        )

        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        val plantPhotoRepo3 = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(careLogs)
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { plantPhotoRepo3.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        val viewModel = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo3, plant.id, mockDataStore, mockQuickLogUseCase, mockCustomReminderRepo, mockPlantIssueRepo, database, wateringAdjustmentRepo)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Watering History"))
        composeTestRule.onNodeWithText("Watering History").assertIsDisplayed()
    }

    @Test
    fun wateringChart_displaysWithTwoWateringLogs() {
        val plant = Plant(id = 5L, name = "Spider Plant", createdAt = 0L, updatedAt = 0L)
        val dayInMs = 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()

        val careLogs = listOf(
            CareLog(
                id = 1L,
                plantId = 5L,
                careType = CareType.WATER,
                loggedAt = now - dayInMs
            ),
            CareLog(
                id = 2L,
                plantId = 5L,
                careType = CareType.WATER,
                loggedAt = now
            )
        )

        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        val plantPhotoRepo5 = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(careLogs)
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { plantPhotoRepo5.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        val viewModel = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo5, plant.id, mockDataStore, mockQuickLogUseCase, mockCustomReminderRepo, mockPlantIssueRepo, database, wateringAdjustmentRepo)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Watering History"))
        composeTestRule.onNodeWithText("Watering History").assertIsDisplayed()
    }

    @Test
    fun wateringChart_showsEmptyStateWithFewerThanTwoWateringLogs() {
        val plant = Plant(id = 4L, name = "Succulent", createdAt = 0L, updatedAt = 0L)
        val now = System.currentTimeMillis()

        val careLogs = listOf(
            CareLog(
                id = 1L,
                plantId = 4L,
                careType = CareType.WATER,
                loggedAt = now
            )
        )

        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        val plantPhotoRepo4 = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(careLogs)
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { plantPhotoRepo4.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        val viewModel = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo4, plant.id, mockDataStore, mockQuickLogUseCase, mockCustomReminderRepo, mockPlantIssueRepo, database, wateringAdjustmentRepo)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        // The chart lives under the Water tab, below the tab strip; scroll the list to it before asserting.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Need at least 2 watering logs to display watering history."))
        composeTestRule.onNodeWithText("Need at least 2 watering logs to display watering history.")
            .assertIsDisplayed()
    }

    @Test
    fun coverPhoto_tapOpensFullScreenViewer() {
        val plant = Plant(id = 6L, name = "Monstera", coverPhotoUri = "content://fake/photo", createdAt = 0L, updatedAt = 0L)
        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        val plantPhotoRepo6 = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { plantPhotoRepo6.getPhotosForPlant(plant.id) } returns flowOf(listOf(
            PlantPhoto(id = 1L, plantId = 6L, uri = "content://fake/photo", capturedAt = 0L)
        ))
        val viewModel = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo6, plant.id, mockDataStore, mockQuickLogUseCase, mockCustomReminderRepo, mockPlantIssueRepo, database, wateringAdjustmentRepo)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Plant cover photo").performClick()
        composeTestRule.onNodeWithContentDescription("Close photo viewer").assertIsDisplayed()
    }

    @Test
    fun fullScreenViewer_showsPhotoDateLabel() {
        val plant = Plant(id = 8L, name = "Fiddle Leaf", coverPhotoUri = "content://fake/photo", createdAt = 0L, updatedAt = 0L)
        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        val plantPhotoRepo8 = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { plantPhotoRepo8.getPhotosForPlant(plant.id) } returns flowOf(listOf(
            PlantPhoto(id = 1L, plantId = 8L, uri = "content://fake/photo", capturedAt = 0L)
        ))
        val viewModel = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo8, plant.id, mockDataStore, mockQuickLogUseCase, mockCustomReminderRepo, mockPlantIssueRepo, database, wateringAdjustmentRepo)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithContentDescription("Plant cover photo").performClick()
        // The exact date is timezone-dependent; assert the labelled date chip is present via its
        // content-description prefix rather than a hard-coded date string.
        composeTestRule.onNodeWithContentDescription("Photo taken", substring = true).assertIsDisplayed()
    }

    @Test
    fun coverPhoto_placeholderTapDoesNotOpenFullScreenViewer() {
        val plant = Plant(id = 7L, name = "Cactus", coverPhotoUri = null, createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onRoot().performClick()
        assertTrue(
            composeTestRule.onAllNodesWithContentDescription("Close photo viewer")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun wateringDueActionsRow_isDisplayedWhenDueSoon() {
        // A never-watered plant's `nextWateringDueAt` is `maxOf(now, wateringDueDateOverride)`
        // (CareSchedule stays due-today, never overdue, until the first WATER log) — a past override
        // of 0L (epoch) alone makes this plant `isDueSoon` (due today), not `isOverdue`. The row's
        // visibility condition is `isOverdue || isDueSoon`, so this covers the due-soon half; the
        // overdue half is covered by `rescheduleDialog_todayOption_enabledWhenOverdue`, which requires
        // a real past `WATER` log to genuinely make a plant overdue.
        val plant = Plant(
            id = 10L,
            name = "Due Soon Plant",
            wateringIntervalDays = 7,
            wateringDueDateOverride = 0L,
            createdAt = 0L,
            updatedAt = 0L
        )
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        // The Water tab content pushes the actions row below the fold; scroll the list to it (this
        // composes the off-screen item, which a waitUntil-on-existence check never would).
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Reschedule watering"))
        composeTestRule.onNodeWithText("Reschedule watering").assertIsDisplayed()
        composeTestRule.onNodeWithTag(WATERING_DUE_WATER_BUTTON_TEST_TAG).assertIsDisplayed()
        // #586: exactly two actions — "Still moist" is now an answer to the Reschedule prompt, not
        // a third button.
        assertTrue(
            composeTestRule.onAllNodesWithText("Still moist")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun wateringDueActionsRow_isHiddenWhenNotDue() {
        // No wateringIntervalDays → row condition fails, row not composed
        val plant = Plant(id = 11L, name = "No Schedule", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.waitForIdle()
        assertTrue(
            composeTestRule.onAllNodesWithText("Reschedule watering")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
        assertTrue(
            composeTestRule.onAllNodesWithText("Still moist")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun rescheduleButton_promptsForAReasonBeforeShowingTheDatePicker() {
        val plant = Plant(
            id = 12L,
            name = "Pilea",
            wateringIntervalDays = 7,
            wateringDueDateOverride = 0L,
            createdAt = 0L,
            updatedAt = 0L
        )
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Reschedule watering"))
        composeTestRule.onNodeWithText("Reschedule watering").performClick()

        // #586: the reason prompt comes first — both answers offered, the date options not yet.
        composeTestRule.onNodeWithText("Why put it off?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Soil still moist").assertIsDisplayed()
        composeTestRule.onNodeWithText("I can't right now").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText("Custom date…")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun rescheduleReasonPrompt_soilStillMoistRoutesThroughQuickLogUseCase() {
        val plant = Plant(
            id = 16L,
            name = "Pilea",
            wateringIntervalDays = 7,
            wateringDueDateOverride = 0L,
            createdAt = 0L,
            updatedAt = 0L
        )
        coEvery { mockQuickLogUseCase.suggestedStillMoistDeferralDays(plant) } returns 2
        coEvery { mockQuickLogUseCase.recordStillMoistCheck(plant, any()) } returns true
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Reschedule watering"))
        composeTestRule.onNodeWithText("Reschedule watering").performClick()
        composeTestRule.onNodeWithText("Soil still moist").performClick()

        // The picker opens on the derived suggestion rather than #570's flat +1 day.
        composeTestRule.onNodeWithText("In 2 days (suggested)").assertIsDisplayed()
        composeTestRule.onNodeWithText("In 2 days (suggested)").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Checked Pilea — still moist")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText("Checked Pilea — still moist").assertIsDisplayed()
        coVerify { mockQuickLogUseCase.recordStillMoistCheck(plant, any()) }
    }

    @Test
    fun rescheduleDialog_showsAllFiveOptions_afterAnsweringTheReasonPrompt() {
        val plant = Plant(
            id = 13L,
            name = "Overdue Reschedule",
            wateringIntervalDays = 7,
            wateringDueDateOverride = 0L,
            createdAt = 0L,
            updatedAt = 0L
        )
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Reschedule watering"))
        composeTestRule.onNodeWithText("Reschedule watering").performClick()
        composeTestRule.onNodeWithText("I can't right now").performClick()

        composeTestRule.onNodeWithText("Today").assertIsDisplayed()
        composeTestRule.onNodeWithText("+1 day").assertIsDisplayed()
        composeTestRule.onNodeWithText("+2 days").assertIsDisplayed()
        composeTestRule.onNodeWithText("+3 days").assertIsDisplayed()
        composeTestRule.onNodeWithText("Custom date…").assertIsDisplayed()
    }

    @Test
    fun rescheduleDialog_todayOption_enabledWhenOverdue() {
        // A never-watered plant's `nextWateringDueAt` is `maxOf(now, wateringDueDateOverride)`
        // (CareSchedule stays due-today, never overdue, until the first WATER log) — a past override
        // alone can't make it overdue. A real WATER log 10 days ago against a 7-day interval does.
        val plant = Plant(
            id = 14L,
            name = "Overdue Today Option",
            wateringIntervalDays = 7,
            createdAt = 0L,
            updatedAt = 0L
        )
        val dayInMs = 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        val careLogs = listOf(
            CareLog(id = 1L, plantId = plant.id, careType = CareType.WATER, loggedAt = now - (10 * dayInMs))
        )

        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        val plantPhotoRepo = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(careLogs)
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        val viewModel = PlantDetailViewModel(
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            plant.id,
            mockDataStore,
            mockQuickLogUseCase,
            mockCustomReminderRepo,
            mockPlantIssueRepo,
            database,
            wateringAdjustmentRepo
        )

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Reschedule watering"))
        composeTestRule.onNodeWithText("Reschedule watering").performClick()
        composeTestRule.onNodeWithText("I can't right now").performClick()

        composeTestRule.onNodeWithText("Today").assertIsEnabled()
    }

    @Test
    fun rescheduleDialog_todayOption_disabledWhenDueSoon() {
        // Never watered, wateringIntervalDays set, no override → due today (isDueSoon), not overdue.
        val plant = Plant(id = 15L, name = "Due Today", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Reschedule watering"))
        composeTestRule.onNodeWithText("Reschedule watering").performClick()
        composeTestRule.onNodeWithText("I can't right now").performClick()

        composeTestRule.onNodeWithText("Today").assertIsNotEnabled()
    }

    /**
     * The fixture waters 14 days ago on a 7-day interval, so the gap ran **long** — this asserts the
     * late variant of the prompt (#586). Labels come from resources, not literals, so a wording
     * change can never leave this test passing against text the app no longer shows.
     */
    @Test
    fun wateringChip_offScheduleAndLate_tapOpensTheLateReasonPrompt() {
        val plant = Plant(id = 20L, name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant, listOf(offScheduleWaterLog(plant.id)))

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithText("Watering").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Water Fern?")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText("Water Fern?").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.water_reason_question_late)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.water_reason_plant_needed_it_late)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.water_reason_just_my_timing_late)).assertIsDisplayed()
    }

    /** #586: an on-schedule watering prompts for nothing — the quick-log fast path. */
    @Test
    fun wateringChip_onSchedule_tapLogsDirectlyWithoutTheReasonPrompt() {
        val plant = Plant(id = 23L, name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val onScheduleLog = CareLog(
            id = 98L,
            plantId = plant.id,
            careType = CareType.WATER,
            loggedAt = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        )
        coEvery {
            mockQuickLogUseCase.quickWaterWithReason(plant, null)
        } returns QuickLogUseCase.QuickLogOutcome(message = "", logged = true)
        val viewModel = makeViewModel(plant, listOf(onScheduleLog))

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithText("Watering").performClick()
        composeTestRule.waitForIdle()

        assertTrue(
            composeTestRule.onAllNodesWithText("Why now?")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
        coVerify { mockQuickLogUseCase.quickWaterWithReason(plant, null) }
    }

    @Test
    fun fertilizingChip_liquidPlant_offSchedule_tapOpensCombinedReasonPrompt() {
        val plant = Plant(
            id = 21L,
            name = "Ivy",
            useLiquidFertilizer = true,
            fertilizingIntervalDays = 30,
            wateringIntervalDays = 7,
            createdAt = 0L,
            updatedAt = 0L
        )
        val viewModel = makeViewModel(plant, listOf(offScheduleWaterLog(plant.id)))

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithText("Fertilizing").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Water & fertilize Ivy?")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText("Water & fertilize Ivy?").assertIsDisplayed()
    }

    @Test
    fun fertilizingChip_regularPlant_tapLogsDirectlyWithoutSheet() {
        val plant = Plant(
            id = 22L,
            name = "Basil",
            fertilizingIntervalDays = 30,
            wateringIntervalDays = 7,
            createdAt = 0L,
            updatedAt = 0L
        )
        val viewModel = makeViewModel(plant)
        // The shared mockQuickLogUseCase is relaxed, and QuickLogOutcome.logged has no default —
        // relaxed mocking fills unstubbed Booleans with false, which would read as "already logged
        // today" here. Stub the outcome explicitly so this test exercises the logged-successfully path.
        coEvery {
            mockQuickLogUseCase.quickLog(plant, CareType.FERTILIZE)
        } returns QuickLogUseCase.QuickLogOutcome(message = "", logged = true)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithText("Fertilizing").performClick()
        // A regular plant logs the fertilizing directly and shows a snackbar; no feedback sheet opens.
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Fertilized Basil")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        assertTrue(
            composeTestRule.onAllNodesWithText("Plant was dry / stressed")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun careTabs_areDisplayed() {
        val plant = Plant(id = 30L, name = "Aloe", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        // The hero/name-header/StatsRow sections push the tab strip below the fold on CI's 320x640
        // emulator; scroll to it first. Custom Reminders/Active Issues moved into their own hidden
        // tabs (#590, product ADR-0030), so they no longer push this any further.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Water"))
        composeTestRule.onNodeWithText("Water").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fertilize").assertIsDisplayed()
        composeTestRule.onNodeWithText("Repot").assertIsDisplayed()
        composeTestRule.onNodeWithText("Photo").assertIsDisplayed()
    }

    // Standalone Tab()s inside PlantDetailTabStrip's FlowRow draw no indicator of their own
    // (unlike a TabRow/PrimaryTabRow) — this asserts the underlying selected/not-selected semantic
    // state each Tab exposes stays wired correctly (#591).
    @Test
    fun careTabs_selectingTabMarksItSelectedAndDeselectsOthers() {
        val plant = Plant(id = 95L, name = "Pothos", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Water"))
        composeTestRule.onNodeWithText("Water").assertIsSelected()
        composeTestRule.onNodeWithText("Fertilize").assertIsNotSelected()

        composeTestRule.onNodeWithText("Fertilize").performClick()

        composeTestRule.onNodeWithText("Fertilize").assertIsSelected()
        composeTestRule.onNodeWithText("Water").assertIsNotSelected()
    }

    @Test
    fun fertilizeTab_showsEmptyState_onlyAfterSelected() {
        val plant = Plant(id = 31L, name = "Sage", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        // The Fertilize empty state is unique to the Fertilize tab, so it proves the tab switched.
        assertTrue(
            composeTestRule.onAllNodesWithText("No fertilizing logged yet.")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
        // The hero/name-header/StatsRow sections push the tab strip below the fold on CI's 320x640
        // emulator; scroll to it first. Custom Reminders/Active Issues moved into their own hidden
        // tabs (#590, product ADR-0030), so they no longer push this any further.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Fertilize"))
        composeTestRule.onNodeWithText("Fertilize").performClick()
        // On CI's 320x640 emulator the empty state sits below the fold; scroll the list to it.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("No fertilizing logged yet."))
        composeTestRule.onNodeWithText("No fertilizing logged yet.").assertIsDisplayed()
    }

    @Test
    fun photoTab_showsEmptyState_whenNoPhotos() {
        val plant = Plant(id = 32L, name = "Ivy", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        // The hero/name-header/StatsRow sections push the tab strip below the fold on CI's 320x640
        // emulator; scroll to it first. Custom Reminders/Active Issues moved into their own hidden
        // tabs (#590, product ADR-0030), so they no longer push this any further.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Photo"))
        composeTestRule.onNodeWithText("Photo").performClick()
        // On CI's 320x640 emulator the empty state sits below the fold; scroll the list to it.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("No photos yet."))
        composeTestRule.onNodeWithText("No photos yet.").assertIsDisplayed()
    }

    @Test
    fun waterTab_showsInlineWateringIntervalControl() {
        val plant = Plant(id = 33L, name = "Calathea", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        // The inline watering-interval header sits at the top of the default Water tab.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Water every 7 days"))
        composeTestRule.onNodeWithText("Water every 7 days").assertIsDisplayed()
    }

    @Test
    fun fertilizeTab_showsInlineScheduleControl() {
        // No fertilizing interval → the inline control shows its disabled "Fertilizing reminder" header,
        // which is unique to this control (the fertilizing stat chip is absent without an interval).
        val plant = Plant(id = 34L, name = "Oregano", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        // The hero/name-header/StatsRow sections push the tab strip below the fold on CI's 320x640
        // emulator; scroll to it first. Custom Reminders/Active Issues moved into their own hidden
        // tabs (#590, product ADR-0030), so they no longer push this any further.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Fertilize"))
        composeTestRule.onNodeWithText("Fertilize").performClick()
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Fertilizing reminder"))
        composeTestRule.onNodeWithText("Fertilizing reminder").assertIsDisplayed()
    }

    @Test
    fun repotTab_showsInsightsForMultipleRepots() {
        val day = 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        val plant = Plant(id = 40L, name = "Yucca", createdAt = 0L, updatedAt = 0L)
        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        val plantPhotoRepo = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(
            listOf(
                CareLog(id = 1L, plantId = 40L, careType = CareType.REPOT, loggedAt = now - 60 * day),
                CareLog(id = 2L, plantId = 40L, careType = CareType.REPOT, loggedAt = now)
            )
        )
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        val viewModel =
            PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo, plant.id, mockDataStore, mockQuickLogUseCase, mockCustomReminderRepo, mockPlantIssueRepo, database, wateringAdjustmentRepo)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        // Two repots → the Repot tab's insights card shows the count and an average interval.
        // The hero/name-header/StatsRow sections push the tab strip below the fold on CI's 320x640
        // emulator; scroll to it first. Custom Reminders/Active Issues moved into their own hidden
        // tabs (#590, product ADR-0030), so they no longer push this any further.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Repot"))
        composeTestRule.onNodeWithText("Repot").performClick()
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Repottings"))
        composeTestRule.onNodeWithText("Repottings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Avg. interval").assertIsDisplayed()
    }

    @Test
    fun tabsFlagOff_showsClassicLayoutWithoutTabs() {
        val plant = Plant(id = 41L, name = "Basil", createdAt = 0L, updatedAt = 0L)
        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        val plantPhotoRepo = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { plantPhotoRepo.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        val viewModel =
            PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo, plant.id, flagsOffDataStore, mockQuickLogUseCase, mockCustomReminderRepo, mockPlantIssueRepo, database, wateringAdjustmentRepo)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        // Classic layout: the watering chart renders inline, and no tab labels are present.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Watering History"))
        composeTestRule.onNodeWithText("Watering History").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText("Repot")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    // ---- Tab row collapse/expand + attention badge (#590, product ADR-0030) ----

    @Test
    fun tabRow_collapsedByDefault_hidesCustomRemindersAndIssuesTabs() {
        val plant = Plant(id = 90L, name = "Peperomia", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("plant_detail_tabs_toggle"))
        composeTestRule.onNodeWithContentDescription(tabsExpandCd()).assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText(customRemindersTabLabel())
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
        assertTrue(
            composeTestRule.onAllNodesWithText(issuesTabLabel())
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun tabRow_expandToggle_revealsHiddenTabsAndFlipsDescription() {
        val plant = Plant(id = 91L, name = "Philodendron", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("plant_detail_tabs_toggle"))
        composeTestRule.onNodeWithTag("plant_detail_tabs_toggle").performClick()

        composeTestRule.onNodeWithContentDescription(tabsCollapseCd()).assertIsDisplayed()
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(customRemindersTabLabel()))
        composeTestRule.onNodeWithText(customRemindersTabLabel()).assertIsDisplayed()
        composeTestRule.onNodeWithText(issuesTabLabel()).assertIsDisplayed()
    }

    @Test
    fun tabRow_attentionBadge_visibleWhenCollapsedWithActiveIssue_hiddenOnceExpanded() {
        val plant = Plant(id = 92L, name = "Snake Plant Two", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModelWithReminderRepo(
            plant,
            reactiveCustomReminderRepo(),
            plantIssueRepo = reactivePlantIssueRepo(listOf(PlantIssue(id = 10L, plantId = 92L, name = "Aphids")))
        )

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        // The Badge itself has no semantics of its own (#591) and now sits under the toggle's own
        // clickable — a merging ancestor, so its testTag doesn't survive into the merged tree (#420).
        // Assert the announcement instead: the toggle's content description is what actually signals
        // attention to a screen-reader user, and is what's left once collapsed/expanded flip it.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("plant_detail_tabs_toggle"))
        composeTestRule.onNodeWithContentDescription(tabsExpandAttentionCd()).assertIsDisplayed()

        composeTestRule.onNodeWithTag("plant_detail_tabs_toggle").performClick()

        composeTestRule.onNodeWithContentDescription(tabsCollapseCd()).assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithContentDescription(tabsExpandAttentionCd())
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    // The Badge dot itself has no contentDescription, so a screen-reader user's only signal that
    // something needs attention is the toggle's own announced description (#591).
    @Test
    fun tabRow_expandToggleDescription_mentionsAttentionWhenCollapsedWithActiveIssue() {
        val plant = Plant(id = 97L, name = "Snake Plant Three", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModelWithReminderRepo(
            plant,
            reactiveCustomReminderRepo(),
            plantIssueRepo = reactivePlantIssueRepo(listOf(PlantIssue(id = 13L, plantId = 97L, name = "Scale")))
        )

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("plant_detail_tabs_toggle"))
        composeTestRule.onNodeWithContentDescription(tabsExpandAttentionCd()).assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithContentDescription(tabsExpandCd())
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )

        composeTestRule.onNodeWithTag("plant_detail_tabs_toggle").performClick()

        composeTestRule.onNodeWithContentDescription(tabsCollapseCd()).assertIsDisplayed()
    }

    @Test
    fun tabRow_attentionBadge_visibleWithOverdueCustomReminder() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val now = System.currentTimeMillis()
        val plant = Plant(id = 93L, name = "Rubber Plant", createdAt = 0L, updatedAt = 0L)
        val overdueReminder = CustomReminder(
            id = 11L,
            plantId = 93L,
            name = "Wipe leaves",
            intervalDays = 1,
            createdAt = now - (5 * dayInMs)
        )
        val viewModel = makeViewModelWithReminderRepo(plant, reactiveCustomReminderRepo(listOf(overdueReminder)))

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        // See the comment in tabRow_attentionBadge_visibleWhenCollapsedWithActiveIssue_hiddenOnceExpanded
        // for why this asserts the toggle's content description rather than the Badge's testTag.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("plant_detail_tabs_toggle"))
        composeTestRule.onNodeWithContentDescription(tabsExpandAttentionCd()).assertIsDisplayed()
    }

    @Test
    fun tabRow_collapsingWhileOnHiddenTab_resetsSelectionToWater() {
        val plant = Plant(id = 94L, name = "ZZ Plant", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModelWithReminderRepo(plant, reactiveCustomReminderRepo())

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        selectPlantDetailTab(customRemindersTabLabel())
        // Selecting a tab doesn't auto-scroll its content into view (mirrors
        // fertilizeTab_showsEmptyState_onlyAfterSelected/resolvingIssue_removesItFromActiveList).
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(customRemindersSectionLabel()))
        composeTestRule.onNodeWithText(customRemindersSectionLabel()).assertIsDisplayed()

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("plant_detail_tabs_toggle"))
        composeTestRule.onNodeWithTag("plant_detail_tabs_toggle").performClick()

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Water every 7 days"))
        composeTestRule.onNodeWithText("Water every 7 days").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText(customRemindersSectionLabel())
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    private fun customRemindersSectionLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.custom_reminders_section)

    private fun customRemindersEmptyLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.custom_reminders_empty)

    private fun addReminderCd(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.cd_add_custom_reminder)

    private fun editReminderCd(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.cd_edit_custom_reminder)

    private fun deleteReminderCd(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.cd_delete_custom_reminder)

    private fun markReminderDoneCd(name: String): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.cd_mark_custom_reminder_done, name)

    private fun reminderNameFieldLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.custom_reminder_name_label)

    private fun deleteReminderTitle(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.custom_reminder_delete_title)

    private fun saveLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.save)

    private fun deleteLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.delete)

    private fun plantIssuesSectionLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.plant_issues_section)

    private fun plantIssuesEmptyLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.plant_issues_empty)

    private fun reportIssueCd(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.cd_report_plant_issue)

    private fun resolveIssueCd(name: String): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.cd_resolve_plant_issue, name)

    private fun issueNameFieldLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.plant_issue_name_label)

    private fun setReminderToggleLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.plant_issue_set_reminder_toggle)

    private fun resolveIssueTitle(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.plant_issue_resolve_title)

    private fun resolveIssueActionLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.plant_issue_resolve_action)

    private fun customRemindersTabLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.plant_detail_tab_custom_reminders)

    private fun issuesTabLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.plant_detail_tab_issues)

    private fun tabsExpandCd(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.plant_detail_tabs_expand_cd)

    private fun tabsExpandAttentionCd(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.plant_detail_tabs_expand_attention_cd)

    private fun tabsCollapseCd(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.plant_detail_tabs_collapse_cd)

    /**
     * Custom Reminders/Active Issues moved from always-visible cards into their own tabs (#590,
     * product ADR-0030) — hidden behind the collapsed tab row by default. Scrolls to and taps the
     * collapse/expand toggle, then scrolls to and taps [tabLabel] to select that tab.
     *
     * `waitUntil` + `fetchSemanticsNodes` (rather than an `assertIsDisplayed()` on the toggle) confirms
     * [tabLabel]'s node has been *composed* before asking `performScrollToNode` to scroll it into view —
     * expanding grows the tab strip's single lazy `item`, which can legitimately push the toggle itself
     * below the viewport, so asserting the toggle stays visible isn't a safe sync point.
     *
     * Selecting a tab does **not** scroll its content into view — callers must do that themselves before
     * asserting on/interacting with it, exactly like [fertilizeTab_showsEmptyState_onlyAfterSelected],
     * [photoTab_showsEmptyState_whenNoPhotos], and [resolvingIssue_removesItFromActiveList] already do
     * for the pre-existing tabs.
     */
    private fun selectPlantDetailTab(tabLabel: String) {
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("plant_detail_tabs_toggle"))
        composeTestRule.onNodeWithTag("plant_detail_tabs_toggle").performClick()
        // 10s, not the usual 5s: tabRow_collapsingWhileOnHiddenTab_resetsSelectionToWater timed out here
        // twice in CI with "Failed to find ColorBuffer" emulator-rendering warnings logged immediately
        // before it in both runs — consistent with transient emulator rendering slowness at that point in
        // the suite, not app/test logic (every other selectPlantDetailTab() call reliably clears 5s).
        composeTestRule.waitUntil(timeoutMillis = 10000) {
            composeTestRule.onAllNodesWithText(tabLabel)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(tabLabel))
        composeTestRule.onNodeWithText(tabLabel).performClick()
    }

    /**
     * A [CustomReminderRepository] mock whose [CustomReminderRepository.getRemindersForPlant] flow is
     * backed by a live [MutableStateFlow], and whose add/update/delete mutate that same state — so the
     * Compose UI (which observes [PlantDetailViewModel.customReminders]) reflects CRUD operations the
     * way the real Room-backed repository would, unlike the class-level [mockCustomReminderRepo] stub.
     */
    private fun reactiveCustomReminderRepo(initial: List<CustomReminder> = emptyList()): CustomReminderRepository {
        val state = MutableStateFlow(initial)
        var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1
        val repo = mockk<CustomReminderRepository>()
        every { repo.getRemindersForPlant(any()) } returns state
        coEvery { repo.addReminder(any()) } answers {
            val reminder = (it.invocation.args[0] as CustomReminder).copy(id = nextId++)
            state.value = state.value + reminder
            reminder.id
        }
        coEvery { repo.updateReminder(any()) } answers {
            val updated = it.invocation.args[0] as CustomReminder
            state.value = state.value.map { existing -> if (existing.id == updated.id) updated else existing }
        }
        coEvery { repo.deleteReminder(any()) } answers {
            val deleted = it.invocation.args[0] as CustomReminder
            state.value = state.value.filterNot { existing -> existing.id == deleted.id }
        }
        return repo
    }

    /**
     * A [PlantRepository] mock whose [PlantRepository.getPlantById] flow is backed by a live
     * [MutableStateFlow], and whose [PlantRepository.updatePlant] mutates that same state — so the
     * seasonal-curve chart's "Pin interval" toggle (#579) is reflected in the Compose UI the way the
     * real Room-backed repository would, mirroring [reactiveCustomReminderRepo]/[reactivePlantIssueRepo].
     */
    private fun reactivePlantRepo(initial: Plant): PlantRepository {
        val state = MutableStateFlow(initial)
        val repo = mockk<PlantRepository>()
        every { repo.getPlantById(initial.id) } returns state
        coEvery { repo.updatePlant(any()) } answers {
            state.value = it.invocation.args[0] as Plant
        }
        return repo
    }

    private fun makeViewModelWithPlantRepo(
        plant: Plant,
        plantRepo: PlantRepository,
        dataStore: DataStore<Preferences> = mockDataStoreWithSeasonal
    ): PlantDetailViewModel {
        val careLogRepo = mockk<CareLogRepository>().also {
            every { it.getLogsForPlant(plant.id) } returns flowOf(emptyList())
            every { it.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        }
        val plantPhotoRepo = mockk<PlantPhotoRepository>().also {
            every { it.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        }
        return PlantDetailViewModel(
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            plant.id,
            dataStore,
            mockQuickLogUseCase,
            mockCustomReminderRepo,
            mockPlantIssueRepo,
            database,
            wateringAdjustmentRepo
        )
    }

    private fun makeViewModelWithReminderRepo(
        plant: Plant,
        customReminderRepo: CustomReminderRepository,
        careLogRepo: CareLogRepository = mockk<CareLogRepository>().also {
            every { it.getLogsForPlant(plant.id) } returns flowOf(emptyList())
            every { it.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
            coEvery { it.addLog(any()) } returns 1L
        },
        plantIssueRepo: PlantIssueRepository = mockPlantIssueRepo
    ): PlantDetailViewModel {
        val plantRepo = mockk<PlantRepository>()
        val plantPhotoRepo = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { plantPhotoRepo.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        return PlantDetailViewModel(
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            plant.id,
            mockDataStore,
            mockQuickLogUseCase,
            customReminderRepo,
            plantIssueRepo,
            database,
            wateringAdjustmentRepo
        )
    }

    /**
     * A [PlantIssueRepository] mock whose [PlantIssueRepository.getActiveIssuesForPlant] flow is
     * backed by a live [MutableStateFlow], and whose add/update mutate that same state — mirrors
     * [reactiveCustomReminderRepo] so plant-issue CRUD tests observe the Compose UI reacting to
     * ViewModel calls the way the real Room-backed repository would.
     */
    private fun reactivePlantIssueRepo(initial: List<PlantIssue> = emptyList()): PlantIssueRepository {
        val state = MutableStateFlow(initial)
        var nextId = (initial.maxOfOrNull { it.id } ?: 0L) + 1
        val repo = mockk<PlantIssueRepository>()
        every { repo.getActiveIssuesForPlant(any()) } returns state
        coEvery { repo.addIssue(any()) } answers {
            val issue = (it.invocation.args[0] as PlantIssue).copy(id = nextId++)
            state.value = state.value + issue
            issue.id
        }
        coEvery { repo.updateIssue(any()) } answers {
            val updated = it.invocation.args[0] as PlantIssue
            state.value = if (updated.resolvedAt != null) {
                state.value.filterNot { existing -> existing.id == updated.id }
            } else {
                state.value.map { existing -> if (existing.id == updated.id) updated else existing }
            }
        }
        return repo
    }

    @Test
    fun customRemindersCard_isDisplayedWithEmptyState() {
        val plant = Plant(id = 50L, name = "Bonsai", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModelWithReminderRepo(plant, reactiveCustomReminderRepo())

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        selectPlantDetailTab(customRemindersTabLabel())
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(customRemindersSectionLabel()))
        composeTestRule.onNodeWithText(customRemindersSectionLabel()).assertIsDisplayed()
        // The empty-state message sits below the header within the same card — scrolling to the
        // header alone doesn't guarantee it's in the (short, 320x640 CI) viewport too.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(customRemindersEmptyLabel()))
        composeTestRule.onNodeWithText(customRemindersEmptyLabel()).assertIsDisplayed()
    }

    @Test
    fun addingCustomReminder_appearsInList() {
        val plant = Plant(id = 51L, name = "Fern", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModelWithReminderRepo(plant, reactiveCustomReminderRepo())

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        selectPlantDetailTab(customRemindersTabLabel())
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(customRemindersSectionLabel()))
        composeTestRule.onNodeWithContentDescription(addReminderCd()).performClick()
        composeTestRule.onNodeWithText(reminderNameFieldLabel()).performTextInput("Neem oil treatment")
        composeTestRule.onNodeWithText(saveLabel()).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Neem oil treatment")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        // The empty-state message is replaced by the new reminder, which can land below the fold.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Neem oil treatment"))
        composeTestRule.onNodeWithText("Neem oil treatment").assertIsDisplayed()
    }

    @Test
    fun editingCustomReminder_updatesDisplayedText() {
        val plant = Plant(id = 52L, name = "Aloe", createdAt = 0L, updatedAt = 0L)
        val existing = CustomReminder(id = 1L, plantId = 52L, name = "Neem oil treatment", intervalDays = 7)
        val viewModel = makeViewModelWithReminderRepo(plant, reactiveCustomReminderRepo(listOf(existing)))

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        selectPlantDetailTab(customRemindersTabLabel())
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Neem oil treatment"))
        composeTestRule.onNodeWithText("Neem oil treatment").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(editReminderCd()).performClick()
        composeTestRule.onNodeWithText(reminderNameFieldLabel()).performTextClearance()
        composeTestRule.onNodeWithText(reminderNameFieldLabel()).performTextInput("Fungicide spray")
        composeTestRule.onNodeWithText(saveLabel()).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Fungicide spray")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText("Fungicide spray").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText("Neem oil treatment")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun deletingCustomReminder_removesItFromList() {
        val plant = Plant(id = 53L, name = "Cactus", createdAt = 0L, updatedAt = 0L)
        val existing = CustomReminder(id = 2L, plantId = 53L, name = "Rotate pot", intervalDays = 30)
        val viewModel = makeViewModelWithReminderRepo(plant, reactiveCustomReminderRepo(listOf(existing)))

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        selectPlantDetailTab(customRemindersTabLabel())
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Rotate pot"))
        composeTestRule.onNodeWithText("Rotate pot").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(deleteReminderCd()).performClick()
        composeTestRule.onNodeWithText(deleteReminderTitle()).assertIsDisplayed()
        composeTestRule.onNodeWithText(deleteLabel()).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Rotate pot")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        composeTestRule.onNodeWithText(customRemindersEmptyLabel()).assertIsDisplayed()
    }

    @Test
    fun markCustomReminderDoneButton_isActionableAndLogsCompletion() {
        val plant = Plant(id = 54L, name = "Pothos", createdAt = 0L, updatedAt = 0L)
        val existing = CustomReminder(id = 3L, plantId = 54L, name = "Fungicide spray", intervalDays = 14)
        val customReminderRepo = reactiveCustomReminderRepo(listOf(existing))
        val careLogRepo = mockk<CareLogRepository>()
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        coEvery { careLogRepo.addLog(any()) } returns 1L
        val viewModel = makeViewModelWithReminderRepo(plant, customReminderRepo, careLogRepo)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        selectPlantDetailTab(customRemindersTabLabel())
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasContentDescription(markReminderDoneCd("Fungicide spray")))
        composeTestRule.onNodeWithContentDescription(markReminderDoneCd("Fungicide spray"))
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        coVerify {
            careLogRepo.addLog(
                match { it.plantId == 54L && it.careType == CareType.CUSTOM && it.customReminderId == 3L }
            )
        }
    }

    // ---- Plant issues (#564) ----

    @Test
    fun plantIssuesCard_isDisplayedWithEmptyState() {
        val plant = Plant(id = 60L, name = "Jade", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModelWithReminderRepo(
            plant,
            reactiveCustomReminderRepo(),
            plantIssueRepo = reactivePlantIssueRepo()
        )

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        selectPlantDetailTab(issuesTabLabel())
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(plantIssuesSectionLabel()))
        composeTestRule.onNodeWithText(plantIssuesSectionLabel()).assertIsDisplayed()
        // The empty-state message sits below the header within the same card — scrolling to the
        // header alone doesn't guarantee it's in the (short, 320x640 CI) viewport too.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(plantIssuesEmptyLabel()))
        composeTestRule.onNodeWithText(plantIssuesEmptyLabel()).assertIsDisplayed()
    }

    @Test
    fun reportingIssue_withoutReminder_appearsInListAndDoesNotCreateReminder() {
        val plant = Plant(id = 61L, name = "Basil", createdAt = 0L, updatedAt = 0L)
        val customReminderRepo = reactiveCustomReminderRepo()
        val viewModel = makeViewModelWithReminderRepo(
            plant,
            customReminderRepo,
            plantIssueRepo = reactivePlantIssueRepo()
        )

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        selectPlantDetailTab(issuesTabLabel())
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(plantIssuesSectionLabel()))
        composeTestRule.onNodeWithContentDescription(reportIssueCd()).performClick()
        composeTestRule.onNodeWithText(issueNameFieldLabel()).performTextInput("Spider mites")
        composeTestRule.onNodeWithText(saveLabel()).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Spider mites")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        // The empty-state message is replaced by the new issue, which can land below the fold.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Spider mites"))
        composeTestRule.onNodeWithText("Spider mites").assertIsDisplayed()
        coVerify(exactly = 0) { customReminderRepo.addReminder(any()) }
    }

    @Test
    fun reportingIssue_withReminderToggleOn_createsLinkedReminder() {
        val plant = Plant(id = 62L, name = "Monstera", createdAt = 0L, updatedAt = 0L)
        val customReminderRepo = reactiveCustomReminderRepo()
        val viewModel = makeViewModelWithReminderRepo(
            plant,
            customReminderRepo,
            plantIssueRepo = reactivePlantIssueRepo()
        )

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        selectPlantDetailTab(issuesTabLabel())
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(plantIssuesSectionLabel()))
        composeTestRule.onNodeWithContentDescription(reportIssueCd()).performClick()
        composeTestRule.onNodeWithText(issueNameFieldLabel()).performTextInput("Root rot")
        composeTestRule.onNodeWithText(setReminderToggleLabel()).assertIsDisplayed()
        composeTestRule.onNodeWithTag("plant_issue_set_reminder_switch").performClick()
        composeTestRule.onNodeWithText(saveLabel()).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Root rot")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        coVerify {
            customReminderRepo.addReminder(match { it.plantId == 62L && it.name == "Root rot" && it.intervalDays == 7 })
        }
    }

    @Test
    fun resolvingIssue_removesItFromActiveList() {
        val plant = Plant(id = 63L, name = "Cactus", createdAt = 0L, updatedAt = 0L)
        val existing = PlantIssue(id = 4L, plantId = 63L, name = "Mealybugs")
        val viewModel = makeViewModelWithReminderRepo(
            plant,
            reactiveCustomReminderRepo(),
            plantIssueRepo = reactivePlantIssueRepo(listOf(existing))
        )

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        selectPlantDetailTab(issuesTabLabel())
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Mealybugs"))
        composeTestRule.onNodeWithText("Mealybugs").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(resolveIssueCd("Mealybugs")).performClick()
        composeTestRule.onNodeWithText(resolveIssueTitle()).assertIsDisplayed()
        composeTestRule.onNodeWithText(resolveIssueActionLabel()).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Mealybugs")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        composeTestRule.onNodeWithText(plantIssuesEmptyLabel()).assertIsDisplayed()
    }

    private fun seasonalCurveTodayText(multiplier: Double): String =
        InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.seasonal_curve_today, multiplier)

    private fun seasonalCurvePinnedNoteText(): String =
        InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.seasonal_curve_pinned_note)

    /**
     * The seasonal-curve preview chart (#579) renders in the Water tab's inline settings card
     * alongside the "Pin interval" switch, only while [FeatureFlagRegistry.SEASONAL_WATERING] is on.
     * Asserts the visible "Today" caption text, computed the same way the chart itself does — never
     * chart canvas/tree structure, per #420.
     */
    @Test
    fun seasonalCurveChart_todayCaption_isDisplayed_whenSeasonalWateringEnabled() {
        val plant = Plant(id = 70L, name = "Aloe", createdAt = 0L, updatedAt = 0L, wateringIntervalDays = 7)
        val viewModel = makeViewModelWithPlantRepo(plant, reactivePlantRepo(plant))

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        val expectedMultiplier = SeasonalWatering.season(
            LocalDate.now(),
            SeasonalAmplitude.STANDARD.value,
            SeasonalWatering.currentHemisphere()
        )

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(seasonalCurveTodayText(expectedMultiplier)))
        composeTestRule.onNodeWithText(seasonalCurveTodayText(expectedMultiplier)).assertIsDisplayed()
        composeTestRule.onNodeWithText(seasonalCurvePinnedNoteText())
            .assertDoesNotExist()
    }

    /** Toggling "Pin interval" (#578) surfaces the chart's pinned-state note (#579). */
    @Test
    fun seasonalCurveChart_pinnedNote_appearsWhenPinToggled() {
        val plant = Plant(id = 71L, name = "Fern", createdAt = 0L, updatedAt = 0L, wateringIntervalDays = 10)
        val viewModel = makeViewModelWithPlantRepo(plant, reactivePlantRepo(plant))

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("pin_interval_switch"))
        composeTestRule.onNodeWithTag("pin_interval_switch").assertIsOff()
        composeTestRule.onNodeWithText(seasonalCurvePinnedNoteText()).assertDoesNotExist()

        composeTestRule.onNodeWithTag("pin_interval_switch").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(seasonalCurvePinnedNoteText())
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithTag("pin_interval_switch").assertIsOn()
    }

    /**
     * "Why this date?" sheet (#572): confidence renders as a labelled indicator — the dots are
     * decorative, the bucket label ("Dialed in") is the accessible content. Asserts the label text
     * appears, never the dot topology, per #420.
     */
    @Test
    fun wateringExplanationSheet_confidenceLabel_isDisplayed() {
        val plant = Plant(
            id = 80L,
            name = "Pilea",
            createdAt = 0L,
            updatedAt = 0L,
            wateringIntervalDays = 7,
            wateringConfidence = 4
        )
        val viewModel = makeViewModelWithPlantRepo(plant, reactivePlantRepo(plant), dataStore = mockDataStoreWithAdaptive)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("why_this_date_button"))
        composeTestRule.onNodeWithTag("why_this_date_button").performClick()

        composeTestRule.onNodeWithTag("watering_explanation_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.confidence_dialed_in)
        ).assertIsDisplayed()
    }

    /** ADAPTIVE_WATERING off (#572): the sheet shows only the plain interval — no invented rows. */
    @Test
    fun wateringExplanationSheet_degradesToPlainInterval_whenAdaptiveWateringOff() {
        val plant = Plant(id = 81L, name = "Snake Plant", createdAt = 0L, updatedAt = 0L, wateringIntervalDays = 9)
        val viewModel = makeViewModelWithPlantRepo(plant, reactivePlantRepo(plant), dataStore = mockDataStoreTabsOnly)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("why_this_date_button"))
        composeTestRule.onNodeWithTag("why_this_date_button").performClick()

        composeTestRule.onNodeWithTag("watering_explanation_sheet").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.watering_explanation_base_interval)
        ).assertDoesNotExist()
        composeTestRule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.watering_explanation_confidence)
        ).assertDoesNotExist()
    }
}
