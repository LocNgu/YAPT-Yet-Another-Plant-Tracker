package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
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

    private fun makeViewModel(plant: Plant): PlantDetailViewModel {
        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        val plantPhotoRepo = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(emptyList())
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
    fun skipWateringButton_isDisplayedWhenOverdue() {
        // wateringDueDateOverride = 0L (epoch, Jan 1 1970) is long before today → isOverdue = true
        val plant = Plant(
            id = 10L,
            name = "Overdue Plant",
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

        // The Water tab content pushes the skip button below the fold; scroll the list to it (this
        // composes the off-screen item, which a waitUntil-on-existence check never would).
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Skip watering"))
        composeTestRule.onNodeWithText("Skip watering").assertIsDisplayed()
    }

    @Test
    fun skipWateringButton_isHiddenWhenNotDue() {
        // No wateringIntervalDays → button condition fails, button not composed
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
            composeTestRule.onAllNodesWithText("Skip watering")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun wateringChip_tapOpensWaterFeedbackSheet() {
        val plant = Plant(id = 20L, name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
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

        composeTestRule.onNodeWithText("Watering").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Water Fern?")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText("Water Fern?").assertIsDisplayed()
    }

    @Test
    fun fertilizingChip_liquidPlant_tapOpensCombinedSheet() {
        val plant = Plant(
            id = 21L,
            name = "Ivy",
            useLiquidFertilizer = true,
            fertilizingIntervalDays = 30,
            wateringIntervalDays = 7,
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

        // The always-visible CustomRemindersCard/PlantIssuesCard sections push the tab strip below
        // the fold on CI's 320x640 emulator; scroll to it first (#232, #564).
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Water"))
        composeTestRule.onNodeWithText("Water").assertIsDisplayed()
        composeTestRule.onNodeWithText("Fertilize").assertIsDisplayed()
        composeTestRule.onNodeWithText("Repot").assertIsDisplayed()
        composeTestRule.onNodeWithText("Photo").assertIsDisplayed()
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
        // The always-visible CustomRemindersCard/PlantIssuesCard sections push the tab strip below
        // the fold on CI's 320x640 emulator; scroll to it first (#232, #564).
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

        // The always-visible CustomRemindersCard/PlantIssuesCard sections push the tab strip below
        // the fold on CI's 320x640 emulator; scroll to it first (#232, #564).
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

        // The always-visible CustomRemindersCard/PlantIssuesCard sections push the tab strip below
        // the fold on CI's 320x640 emulator; scroll to it first (#232, #564).
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
        // The always-visible CustomRemindersCard/PlantIssuesCard sections push the tab strip below
        // the fold on CI's 320x640 emulator; scroll to it first (#232, #564).
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

        composeTestRule.onNodeWithText(customRemindersSectionLabel()).assertIsDisplayed()
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

        composeTestRule.onNodeWithContentDescription(addReminderCd()).performClick()
        composeTestRule.onNodeWithText(reminderNameFieldLabel()).performTextInput("Neem oil treatment")
        composeTestRule.onNodeWithText(saveLabel()).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Neem oil treatment")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
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

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(plantIssuesSectionLabel()))
        composeTestRule.onNodeWithText(plantIssuesSectionLabel()).assertIsDisplayed()
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

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(plantIssuesSectionLabel()))
        composeTestRule.onNodeWithContentDescription(reportIssueCd()).performClick()
        composeTestRule.onNodeWithText(issueNameFieldLabel()).performTextInput("Spider mites")
        composeTestRule.onNodeWithText(saveLabel()).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Spider mites")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
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
