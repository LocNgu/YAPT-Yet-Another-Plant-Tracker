package com.yapt.planttracker.ui.screens.plantdetail

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.yapt.planttracker.R
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantIssue
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.SeasonalRepotting
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.ui.util.labelRes
import com.yapt.planttracker.ui.util.repotPlanLabelRes
import com.yapt.planttracker.util.DateUtils
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/** Upper bound for [PlantDetailScreenTest.scrollDetailToEnd]'s swipes; a few viewports tall at most on a 320x640 screen. */
private const val MAX_END_SWIPES = 10

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

    private val mockDataStore: DataStore<Preferences> = mockk<DataStore<Preferences>>().also {
        every { it.data } returns flowOf(emptyPreferences())
    }

    /**
     * Amplitude explicitly Off — for tests whose exact on/off-schedule boundary math must stay
     * date-independent (an unset amplitude now defaults to Standard since seasonal watering
     * graduated, #656, which would otherwise shift the effective interval and make the boundary
     * flaky by date).
     */
    private val mockDataStoreAmplitudeOff: DataStore<Preferences> = mockk<DataStore<Preferences>>().also {
        every { it.data } returns flowOf(
            mutablePreferencesOf(
                SettingsKeys.SEASONAL_AMPLITUDE to "OFF"
            )
        )
    }

    /**
     * The tabbed Water layout, where the seasonal-curve preview chart (#579) and "Pin interval"
     * switch (#578) always render (seasonal watering graduated, #656) alongside the "Why this date?"
     * sheet entry point.
     */
    private val mockDataStoreWithSeasonal: DataStore<Preferences> = mockk<DataStore<Preferences>>().also {
        every { it.data } returns flowOf(emptyPreferences())
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

    /**
     * #654: every Water/Water+Fertilize quick-log entry point now opens the "Log watering" date
     * picker first, pre-selected to today. Confirming it with "OK" (no date change) reproduces the
     * old instant-tap behaviour, so most existing quick-log tests just need this one extra step
     * inserted between the button tap and whatever they assert next.
     */
    private fun confirmLogWateringDatePickerWithToday() {
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(LOG_WATERING_DATE_PICKER_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText(str(R.string.ok)).performClick()
    }

    private fun offScheduleWaterLog(plantId: Long) = CareLog(
        id = 99L,
        plantId = plantId,
        careType = CareType.WATER,
        loggedAt = System.currentTimeMillis() - (14 * 24 * 60 * 60 * 1000L)
    )

    private fun makeViewModel(
        plant: Plant,
        careLogs: List<CareLog> = emptyList(),
        dataStore: DataStore<Preferences> = mockDataStore
    ): PlantDetailViewModel {
        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        val plantPhotoRepo = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(careLogs)
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        // #679: PlantDetailViewModel.previousWateringBefore() calls this from the "Log watering"
        // date picker's onConfirm. A blanket `returns null` here made every scenario using this
        // shared helper look like "no prior watering" to the on/off-schedule gate regardless of
        // what `careLogs` actually set up — e.g. offScheduleWaterLog()'s 14-day-old WATER log was
        // invisible to the gate, which then always took the trivial "no predecessor -> on schedule"
        // branch (CareSchedule.wateringOnScheduleNow's `lastWateredAt == null` early return) instead
        // of opening the reason prompt those tests assert on. Mirror the real DAO's "newest WATER
        // log strictly before `beforeMillis`" query against this fixture's own `careLogs` instead.
        coEvery { careLogRepo.getLastWateringBefore(plant.id, any()) } answers {
            val before = it.invocation.args[1] as Long
            careLogs.filter { log -> log.careType == CareType.WATER && log.loggedAt < before }
                .maxByOrNull { log -> log.loggedAt }
        }
        every { plantPhotoRepo.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
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
    fun initialTab_customReminders_expandsTabRowAndSelectsIt() {
        val plant = Plant(id = 21L, name = "Ficus", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.CUSTOM_REMINDERS
            )
        }

        composeTestRule.onNode(hasText(str(R.string.plant_detail_tab_custom_reminders)) and isSelectable())
            .assertIsSelected()
        composeTestRule.onNodeWithContentDescription(str(R.string.plant_detail_tabs_collapse_cd)).assertExists()
    }

    @Test
    fun initialTab_issues_expandsTabRowAndSelectsIt() {
        val plant = Plant(id = 22L, name = "Ficus", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.ISSUES
            )
        }

        composeTestRule.onNode(hasText(str(R.string.plant_detail_tab_issues)) and isSelectable())
            .assertIsSelected()
    }

    @Test
    fun noInitialTab_opensOnHomeWithRowCollapsed() {
        val plant = Plant(id = 23L, name = "Ficus", createdAt = 0L, updatedAt = 0L)
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

        composeTestRule.onNode(hasText(str(R.string.plant_detail_tab_home)) and isSelectable())
            .assertIsSelected()
        composeTestRule.onAllNodesWithText(str(R.string.plant_detail_tab_issues)).assertCountEquals(0)
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
        coEvery { careLogRepo.getLastWateringBefore(any(), any()) } returns null
        every { plantPhotoRepo3.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        val viewModel = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo3, plant.id, mockDataStore, mockQuickLogUseCase, mockCustomReminderRepo, mockPlantIssueRepo, database, wateringAdjustmentRepo)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
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
        coEvery { careLogRepo.getLastWateringBefore(any(), any()) } returns null
        every { plantPhotoRepo5.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        val viewModel = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo5, plant.id, mockDataStore, mockQuickLogUseCase, mockCustomReminderRepo, mockPlantIssueRepo, database, wateringAdjustmentRepo)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
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
        coEvery { careLogRepo.getLastWateringBefore(any(), any()) } returns null
        every { plantPhotoRepo4.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        val viewModel = PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo4, plant.id, mockDataStore, mockQuickLogUseCase, mockCustomReminderRepo, mockPlantIssueRepo, database, wateringAdjustmentRepo)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
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
        coEvery { careLogRepo.getLastWateringBefore(any(), any()) } returns null
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
        coEvery { careLogRepo.getLastWateringBefore(any(), any()) } returns null
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
        // of 0L (epoch) alone makes this plant `isDueSoon` (due today), not `isOverdue`. The row is
        // now always visible whenever `wateringIntervalDays` is set (#603) — this is one due-state
        // sample of that; `wateringDueActionsRow_isDisplayedWhenNotYetDue` covers a plant that isn't
        // due at all, and the overdue state is covered by `rescheduleDialog_todayOption_enabledWhenOverdue`.
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
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        // The Water tab content pushes the actions row below the fold; scroll the list to it (this
        // composes the off-screen item, which a waitUntil-on-existence check never would).
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasContentDescription("Reschedule watering"))
        composeTestRule.onNodeWithContentDescription("Reschedule watering").assertIsDisplayed()
        composeTestRule.onNodeWithTag(WATERING_DUE_WATER_BUTTON_TEST_TAG).assertIsDisplayed()
        // #586, then #738: exactly two actions — "Still moist" is retired entirely, not a third
        // button and not an answer to a reason prompt either.
        assertTrue(
            composeTestRule.onAllNodesWithText("Still moist")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun wateringDueActionsRow_noScheduleSet_hidesRescheduleButShowsWater() {
        // No wateringIntervalDays → Reschedule has nothing to reschedule and stays absent, but Water
        // renders regardless (product ADR-0040): a plant with no configured schedule still needs a
        // one-tap way to log an occasional watering, now that StatsRow's always-on chip fallback for
        // that case is gone (#704).
        val plant = Plant(id = 11L, name = "No Schedule", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(WATERING_DUE_WATER_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithTag(WATERING_DUE_WATER_BUTTON_TEST_TAG).assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithContentDescription("Reschedule watering")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
        assertTrue(
            composeTestRule.onAllNodesWithText("Still moist")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun wateringDueActionsRow_isDisplayedWhenNotYetDue() {
        // #603 regression case: the row used to be gated on `isOverdue || isDueSoon`; a plant
        // watered today against a long interval is neither, so this is the state that used to hide
        // the row (and made Reschedule fully unreachable) before this fix.
        val plant = Plant(
            id = 17L,
            name = "Not Due Plant",
            wateringIntervalDays = 30,
            createdAt = 0L,
            updatedAt = 0L
        )
        val recentLog = CareLog(
            id = 97L,
            plantId = plant.id,
            careType = CareType.WATER,
            loggedAt = System.currentTimeMillis()
        )
        val viewModel = makeViewModel(plant, listOf(recentLog))

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasContentDescription("Reschedule watering"))
        composeTestRule.onNodeWithContentDescription("Reschedule watering").assertIsDisplayed()
        composeTestRule.onNodeWithTag(WATERING_DUE_WATER_BUTTON_TEST_TAG).assertIsDisplayed()
    }

    @Test
    fun rescheduleButton_opensTheDatePickerDirectly_withNoReasonPrompt() {
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
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasContentDescription("Reschedule watering"))
        // performScrollToNode's minimal-scroll lands the row flush against the bottom-edge FAB —
        // scrolling only as far as the interval label right below it isn't enough margin to clear
        // the FAB's reach. "why_this_date_button" sits further down (past the label + slider), which
        // is enough real scroll distance to bring Reschedule to rest mid-viewport instead of flush.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("why_this_date_button"))
        composeTestRule.onNodeWithContentDescription("Reschedule watering").performClick()

        // #738 (product ADR-0039): no reason prompt — the date dialog opens directly.
        composeTestRule.onNodeWithText("Custom date…").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText("Why put it off?")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun rescheduleDialog_showsAllFiveOptions() {
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
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasContentDescription("Reschedule watering"))
        // performScrollToNode's minimal-scroll lands the row flush against the bottom-edge FAB —
        // scrolling only as far as the interval label right below it isn't enough margin to clear
        // the FAB's reach. "why_this_date_button" sits further down (past the label + slider), which
        // is enough real scroll distance to bring Reschedule to rest mid-viewport instead of flush.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("why_this_date_button"))
        composeTestRule.onNodeWithContentDescription("Reschedule watering").performClick()

        composeTestRule.onNodeWithText("Today").assertIsDisplayed()
        composeTestRule.onNodeWithText("+1 day ·", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("+2 days ·", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("+3 days ·", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Custom date…").assertIsDisplayed()
    }

    @Test
    fun rescheduleDialog_showsTheResultingDueDateForEachRelativeOption() {
        val now = LocalDate.of(2026, 9, 21).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val due = LocalDate.of(2026, 9, 25).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        composeTestRule.setContent {
            RescheduleWateringDialog(
                todayEnabled = true,
                actions = RescheduleDialogActions({}, {}, {}, {}),
                effectiveNextWateringDueAt = due,
                now = now
            )
        }

        composeTestRule.onNodeWithText("Today").assertIsDisplayed()
        for (days in 1..3) {
            val label = if (days == 1) "+1 day" else "+$days days"
            val date = DateUtils.formatDate(due + TimeUnit.DAYS.toMillis(days.toLong()))
            composeTestRule.onNodeWithText("$label · $date").assertIsDisplayed()
        }
        composeTestRule.onNodeWithText("Custom date…").assertIsDisplayed()
    }

    @Test
    fun rescheduleDialog_passesTheRenderedRelativeDateToItsCallback() {
        val shownNow = LocalDate.of(2026, 9, 21).atTime(23, 59)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val overdue = LocalDate.of(2026, 9, 18).atStartOfDay(ZoneId.systemDefault())
            .toInstant().toEpochMilli()
        var relativeDueAt: Long? = null
        composeTestRule.setContent {
            RescheduleWateringDialog(
                todayEnabled = true,
                actions = RescheduleDialogActions(
                    onDismiss = {},
                    onToday = {},
                    onRelativeDate = { relativeDueAt = it },
                    onCustomDate = {}
                ),
                effectiveNextWateringDueAt = overdue,
                now = shownNow
            )
        }

        val tomorrow = shownNow + TimeUnit.DAYS.toMillis(1)
        composeTestRule.onNodeWithText("+1 day · ${DateUtils.formatDate(tomorrow)}").performClick()

        assertEquals(tomorrow, relativeDueAt)
    }

    @Test
    fun rescheduleDialog_defaultClockPreviewUpdatesWithEffectiveDueDate() {
        val initialDueAt = System.currentTimeMillis() + TimeUnit.DAYS.toMillis(5)
        val updatedDueAt = initialDueAt + TimeUnit.DAYS.toMillis(3)
        val effectiveDueAt = mutableLongStateOf(initialDueAt)
        var committedDueAt: Long? = null
        composeTestRule.setContent {
            RescheduleWateringDialog(
                todayEnabled = true,
                actions = RescheduleDialogActions(
                    onDismiss = {},
                    onToday = {},
                    onRelativeDate = { committedDueAt = it },
                    onCustomDate = {}
                ),
                effectiveNextWateringDueAt = effectiveDueAt.longValue
            )
        }

        composeTestRule.runOnIdle { effectiveDueAt.longValue = updatedDueAt }
        val expected = updatedDueAt + TimeUnit.DAYS.toMillis(1)
        composeTestRule.onNodeWithText("+1 day · ${DateUtils.formatDate(expected)}").performClick()

        assertEquals(expected, committedDueAt)
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
        coEvery { careLogRepo.getLastWateringBefore(any(), any()) } returns null
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
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasContentDescription("Reschedule watering"))
        // performScrollToNode's minimal-scroll lands the row flush against the bottom-edge FAB —
        // scrolling only as far as the interval label right below it isn't enough margin to clear
        // the FAB's reach. "why_this_date_button" sits further down (past the label + slider), which
        // is enough real scroll distance to bring Reschedule to rest mid-viewport instead of flush.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("why_this_date_button"))
        composeTestRule.onNodeWithContentDescription("Reschedule watering").performClick()

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
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasContentDescription("Reschedule watering"))
        // performScrollToNode's minimal-scroll lands the row flush against the bottom-edge FAB —
        // scrolling only as far as the interval label right below it isn't enough margin to clear
        // the FAB's reach. "why_this_date_button" sits further down (past the label + slider), which
        // is enough real scroll distance to bring Reschedule to rest mid-viewport instead of flush.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("why_this_date_button"))
        composeTestRule.onNodeWithContentDescription("Reschedule watering").performClick()

        composeTestRule.onNodeWithText("Today").assertIsNotEnabled()
    }

    // ---- Care history CHECK-row filter (#738, product ADR-0039) ----

    /**
     * Existing `CareType.CHECK` rows are hidden, not deleted, from Home's combined
     * care-history list — a display filter, since new code no longer writes them but old rows
     * persist on disk. The count text, the visible rows, and the "N more" arithmetic must all agree:
     * only the WATER row is visible and counted, even though two logs exist.
     */
    @Test
    fun careHistory_hidesCheckRows_fromTheListAndTheCount() {
        val plant = Plant(id = 25L, name = "Filtered Plant", createdAt = 0L, updatedAt = 0L)
        val careLogs = listOf(
            CareLog(id = 1L, plantId = plant.id, careType = CareType.WATER, loggedAt = System.currentTimeMillis()),
            CareLog(
                id = 2L,
                plantId = plant.id,
                careType = CareType.CHECK,
                loggedAt = System.currentTimeMillis() - 1000L
            )
        )
        val viewModel = makeViewModel(plant, careLogs)

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
            .performScrollToNode(hasText(str(R.string.care_history)))
        composeTestRule.onNodeWithText(String.format(str(R.string.plant_detail_care_logs_count), 1))
            .assertIsDisplayed()
        // Home's actions and summary sit above the log, so scrolling to the header alone can leave the
        // first row below the fold on a short viewport.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(str(R.string.care_type_watered)))
        composeTestRule.onNodeWithText(str(R.string.care_type_watered)).assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText(str(R.string.care_type_check))
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    // ---- History relocation: combined log on Home only, the Water tab's own WATER list (#530) ----

    private fun historyLog(id: Long, plantId: Long, type: CareType, note: String? = null) = CareLog(
        id = id,
        plantId = plantId,
        careType = type,
        loggedAt = System.currentTimeMillis() - id * TimeUnit.DAYS.toMillis(1),
        notes = note
    )

    private fun scrollDetailTo(matcher: SemanticsMatcher) {
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG).performScrollToNode(matcher)
    }

    /**
     * Swipes the detail list up until its scroll position stops changing, i.e. to the very end.
     * `performScrollToNode` pages a viewport at a time and then scrolls the *minimum* needed to make the
     * target fully visible, so a target first composed while cut off at the bottom lands flush with the
     * viewport's bottom edge and anything rendered after it stays below the fold, uncomposed. An "absent"
     * assertion made at that point passes vacuously; call this first so whatever would follow the tab's
     * content is on screen. Also parks the last item above the bottom content padding that clears the
     * "+" FAB, which sits over the row's trailing Edit/Delete icons on a 320dp-wide screen.
     */
    private fun scrollDetailToEnd() {
        val content = composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
        var previousPosition: Float? = null
        repeat(MAX_END_SWIPES) {
            content.performTouchInput { swipeUp(startY = height * 0.85f, endY = height * 0.15f) }
            composeTestRule.waitForIdle()
            val position = content.fetchSemanticsNode().config
                .getOrNull(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()
            if (position != null && position == previousPosition) return
            previousPosition = position
        }
    }

    private fun assertNoNodeWithText(text: String) {
        assertTrue(
            composeTestRule.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    private fun showMoreLabel(hidden: Int): String = InstrumentationRegistry.getInstrumentation().targetContext
        .resources.getQuantityString(R.plurals.care_history_show_more, hidden, hidden)

    /**
     * A lazy list only composes what is on screen, so "absent" is only meaningful once the list is
     * scrolled to its very end ([scrollDetailToEnd]): scrolling to the tab's own last item is not enough,
     * because it can land flush with the bottom edge with the combined log (if it leaked back under this
     * tab) still below the fold.
     */
    private fun assertCombinedCareHistoryAbsentOn(tab: PlantDetailTab, lastTabItem: SemanticsMatcher, plantId: Long) {
        val plant = Plant(id = plantId, name = "History plant", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val logs = listOf(
            historyLog(1L, plant.id, CareType.WATER),
            historyLog(2L, plant.id, CareType.PRUNE),
            historyLog(3L, plant.id, CareType.NOTE)
        )
        showDetail(makeViewModel(plant, logs), initialTab = tab)

        scrollDetailTo(lastTabItem)
        scrollDetailToEnd()
        assertNoNodeWithText(str(R.string.care_history))
        assertNoNodeWithText(str(R.string.care_type_pruned))
        assertNoNodeWithText(str(R.string.care_type_note))
    }

    @Test
    fun careHistory_home_showsTheCombinedLogWithEveryCareTypeIncludingMist() {
        val plant = Plant(id = 130L, name = "History plant", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val logs = listOf(
            historyLog(1L, plant.id, CareType.WATER),
            historyLog(2L, plant.id, CareType.PRUNE),
            historyLog(3L, plant.id, CareType.NOTE),
            historyLog(4L, plant.id, CareType.MIST)
        )
        showDetail(makeViewModel(plant, logs))

        scrollDetailTo(hasText(str(R.string.care_history)))
        composeTestRule.onNodeWithText(str(R.string.care_history)).assertIsDisplayed()
        listOf(
            R.string.care_type_pruned,
            R.string.care_type_note,
            R.string.care_type_misted
        ).forEach { labelRes ->
            scrollDetailTo(hasText(str(labelRes)))
            composeTestRule.onNodeWithText(str(labelRes)).assertIsDisplayed()
        }
    }

    @Test
    fun careHistory_waterTab_hasNoCombinedLog() {
        assertCombinedCareHistoryAbsentOn(
            PlantDetailTab.WATER,
            hasText(str(R.string.care_type_watered)),
            plantId = 131L
        )
    }

    @Test
    fun careHistory_fertilizeTab_hasNoCombinedLog() {
        assertCombinedCareHistoryAbsentOn(
            PlantDetailTab.FERTILIZE,
            hasText(str(R.string.plant_detail_tab_fertilize_empty)),
            plantId = 132L
        )
    }

    @Test
    fun careHistory_photoTab_hasNoCombinedLog() {
        assertCombinedCareHistoryAbsentOn(
            PlantDetailTab.PHOTO,
            hasText(str(R.string.plant_detail_tab_photo_empty)),
            plantId = 133L
        )
    }

    @Test
    fun careHistory_repotTab_hasNoCombinedLog() {
        assertCombinedCareHistoryAbsentOn(
            PlantDetailTab.REPOT,
            hasText(str(R.string.plant_detail_tab_repot_empty)),
            plantId = 134L
        )
    }

    @Test
    fun waterTab_listsOnlyItsOwnWateringEntries_andNoMistingList() {
        val plant = Plant(id = 135L, name = "History plant", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val logs = listOf(
            historyLog(1L, plant.id, CareType.WATER),
            historyLog(2L, plant.id, CareType.PRUNE),
            historyLog(3L, plant.id, CareType.FERTILIZE),
            historyLog(4L, plant.id, CareType.NOTE),
            historyLog(5L, plant.id, CareType.MIST)
        )
        showDetail(makeViewModel(plant, logs), initialTab = PlantDetailTab.WATER)

        scrollDetailTo(hasText(str(R.string.plant_detail_watering_section)))
        composeTestRule.onNodeWithText(str(R.string.plant_detail_watering_section)).assertIsDisplayed()
        scrollDetailTo(hasText(str(R.string.care_type_watered)))
        composeTestRule.onNodeWithText(str(R.string.care_type_watered)).assertIsDisplayed()
        scrollDetailToEnd()
        assertNoNodeWithText(str(R.string.care_type_pruned))
        assertNoNodeWithText(str(R.string.care_type_fertilized))
        assertNoNodeWithText(str(R.string.care_type_note))
        // The Water tab's "Recent misting" list was retired (#530); misting entries live in Home's log only.
        assertNoNodeWithText(str(R.string.care_type_misted))
        assertNoNodeWithText("Recent misting")
    }

    @Test
    fun waterTab_waterList_collapsesToFiveAndExpandsWithShowMore() {
        val plant = Plant(id = 136L, name = "History plant", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val logs = (1L..7L).map { historyLog(it, plant.id, CareType.WATER, note = "water note $it") }
        showDetail(makeViewModel(plant, logs), initialTab = PlantDetailTab.WATER)

        scrollDetailTo(hasText(showMoreLabel(2)))
        composeTestRule.onNodeWithText(showMoreLabel(2)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.watering_history_expand_cd)).assertIsDisplayed()
        composeTestRule.onNodeWithText("water note 5").assertIsDisplayed()
        scrollDetailToEnd()
        assertNoNodeWithText("water note 6")
        assertNoNodeWithText("water note 7")

        composeTestRule.onNodeWithText(showMoreLabel(2)).performClick()

        scrollDetailTo(hasText(str(R.string.care_history_show_less)))
        composeTestRule.onNodeWithText(str(R.string.care_history_show_less)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.watering_history_collapse_cd)).assertIsDisplayed()
        composeTestRule.onNodeWithText("water note 7").assertIsDisplayed()
    }

    @Test
    fun waterTab_waterList_atFiveEntries_hasNoShowMoreChip() {
        val plant = Plant(id = 137L, name = "History plant", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val logs = (1L..5L).map { historyLog(it, plant.id, CareType.WATER, note = "water note $it") }
        showDetail(makeViewModel(plant, logs), initialTab = PlantDetailTab.WATER)

        scrollDetailTo(hasText("water note 5"))
        composeTestRule.onNodeWithText("water note 5").assertIsDisplayed()
        scrollDetailToEnd()
        composeTestRule.onAllNodesWithContentDescription(str(R.string.watering_history_expand_cd)).assertCountEquals(0)
    }

    @Test
    fun waterTab_expandingTheWaterList_doesNotExpandHomesCombinedLog() {
        val plant = Plant(id = 138L, name = "History plant", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val logs = (1L..7L).map { historyLog(it, plant.id, CareType.WATER, note = "water note $it") }
        showDetail(makeViewModel(plant, logs), initialTab = PlantDetailTab.WATER)

        scrollDetailTo(hasText(showMoreLabel(2)))
        composeTestRule.onNodeWithText(showMoreLabel(2)).performClick()
        scrollDetailTo(hasText(str(R.string.care_history_show_less)))
        composeTestRule.onNodeWithText(str(R.string.care_history_show_less)).assertIsDisplayed()

        scrollDetailTo(homeTabMatcher)
        composeTestRule.onNode(homeTabMatcher).performClick()

        scrollDetailTo(hasText(showMoreLabel(2)))
        composeTestRule.onNodeWithText(showMoreLabel(2)).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.care_history_expand_cd)).assertIsDisplayed()
    }

    @Test
    fun waterTab_waterRow_editOpensThatLogInTheEditor() {
        val plant = Plant(id = 139L, name = "History plant", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val logs = listOf(
            historyLog(7L, plant.id, CareType.WATER),
            historyLog(8L, plant.id, CareType.FERTILIZE)
        )
        val viewModel = makeViewModel(plant, logs)
        var editedLogId: Long? = null

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = { editedLogId = it },
                initialTab = PlantDetailTab.WATER
            )
        }

        scrollDetailTo(hasContentDescription(str(R.string.cd_edit_log)))
        scrollDetailToEnd()
        composeTestRule.onNodeWithContentDescription(str(R.string.cd_edit_log)).performClick()

        assertEquals(7L, editedLogId)
    }

    @Test
    fun waterTab_waterRow_deleteRemovesThatLogThroughTheRepository() {
        val plant = Plant(id = 140L, name = "History plant", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val waterLog = historyLog(7L, plant.id, CareType.WATER)
        val careLogRepo = mockk<CareLogRepository>().also {
            every { it.getLogsForPlant(plant.id) } returns flowOf(listOf(waterLog))
            every { it.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
            coEvery { it.getLastWateringBefore(any(), any()) } returns null
            coEvery { it.deleteLog(any()) } returns Unit
        }
        showDetail(
            makeViewModelWithReminderRepo(plant, mockCustomReminderRepo, careLogRepo),
            initialTab = PlantDetailTab.WATER
        )

        scrollDetailTo(hasContentDescription(str(R.string.cd_delete_log)))
        scrollDetailToEnd()
        composeTestRule.onNodeWithContentDescription(str(R.string.cd_delete_log)).performClick()

        coVerify(timeout = 5000) { careLogRepo.deleteLog(waterLog) }
    }

    @Test
    fun brandNewPlant_home_showsTheCareHistoryEmptyState() {
        val plant = Plant(id = 141L, name = "Brand new", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        showDetail(makeViewModel(plant))

        scrollDetailTo(hasText(str(R.string.no_care_logs_detail)))
        composeTestRule.onNodeWithText(str(R.string.no_care_logs_detail)).assertIsDisplayed()
    }

    @Test
    fun brandNewPlant_waterTab_hasNoCareHistoryEmptyStateAndNoWateringList() {
        val plant = Plant(id = 142L, name = "Brand new", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        showDetail(makeViewModel(plant), initialTab = PlantDetailTab.WATER)

        // The chart's own message is the Water tab's empty state; nothing may follow it.
        scrollDetailTo(hasText(str(R.string.insufficient_watering_logs)))
        composeTestRule.onNodeWithText(str(R.string.insufficient_watering_logs)).assertIsDisplayed()
        scrollDetailToEnd()
        assertNoNodeWithText(str(R.string.no_care_logs_detail))
        assertNoNodeWithText(str(R.string.care_history))
        assertNoNodeWithText(str(R.string.plant_detail_watering_section))
    }

    @Test
    fun brandNewPlant_fertilizeTab_showsOnlyItsOwnEmptyState() {
        val plant = Plant(id = 143L, name = "Brand new", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        showDetail(makeViewModel(plant), initialTab = PlantDetailTab.FERTILIZE)

        scrollDetailTo(hasText(str(R.string.plant_detail_tab_fertilize_empty)))
        composeTestRule.onNodeWithText(str(R.string.plant_detail_tab_fertilize_empty)).assertIsDisplayed()
        scrollDetailToEnd()
        assertNoNodeWithText(str(R.string.no_care_logs_detail))
        assertNoNodeWithText(str(R.string.care_history))
    }

    // ---- Reschedule delta chip + revert (#630) ----

    @Test
    fun rescheduleDeltaChip_isDisplayedWhenOverrideExceedsSchedule() {
        // Never watered → computedNextDueAt is `now`; a future override beyond that is the actual
        // maxOf() winner, so the chip should show the gap between them.
        val dayInMs = 24 * 60 * 60 * 1000L
        val plant = Plant(
            id = 24L,
            name = "Deferred Plant",
            wateringIntervalDays = 7,
            wateringDueDateOverride = System.currentTimeMillis() + (3 * dayInMs),
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
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasContentDescription("Reschedule watering"))
        composeTestRule.onNodeWithText("Rescheduled +3 days").assertIsDisplayed()
    }

    @Test
    fun rescheduleDeltaChip_isHiddenWhenNoOverride() {
        val plant = Plant(id = 25L, name = "No Override", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasContentDescription("Reschedule watering"))
        assertTrue(
            composeTestRule.onAllNodesWithTag(RESCHEDULE_DELTA_CHIP_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun rescheduleDeltaChip_isHiddenDuringDormancy() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val currentMonth = LocalDate.now().monthValue
        val plant = Plant(
            id = 26L,
            name = "Dormant Deferred Plant",
            wateringIntervalDays = 7,
            wateringDueDateOverride = System.currentTimeMillis() + (3 * dayInMs),
            dormancyStartMonth = currentMonth,
            dormancyEndMonth = currentMonth,
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
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasContentDescription("Reschedule watering"))
        assertTrue(
            composeTestRule.onAllNodesWithTag(RESCHEDULE_DELTA_CHIP_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun rescheduleDeltaChip_isHiddenWhenOverrideIsStale() {
        // A past override the schedule has already caught up with and exceeded is not the maxOf()
        // winner (mirrors wateringDueActionsRow_isDisplayedWhenDueSoon's fixture).
        val plant = Plant(
            id = 27L,
            name = "Stale Override",
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
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasContentDescription("Reschedule watering"))
        assertTrue(
            composeTestRule.onAllNodesWithTag(RESCHEDULE_DELTA_CHIP_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    @Test
    fun rescheduleDeltaChip_tapRevertsOverride_andShowsUndoSnackbar() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val originalOverride = System.currentTimeMillis() + (3 * dayInMs)
        val plant = Plant(
            id = 28L,
            name = "Deferred Plant",
            wateringIntervalDays = 7,
            wateringDueDateOverride = originalOverride,
            createdAt = 0L,
            updatedAt = 0L
        )
        // A live-backed repo (mirrors the seasonal-curve "Pin interval" tests) so the chip's
        // disappearance after revert, and its reappearance after Undo, reflect real state changes
        // rather than a static flow that would never move.
        val viewModel = makeViewModelWithPlantRepo(plant, reactivePlantRepo(plant), dataStore = mockDataStore)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasContentDescription("Reschedule watering"))
        composeTestRule.onNodeWithText("Rescheduled +3 days").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(RESCHEDULE_DELTA_CHIP_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Reschedule reverted")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText("Reschedule reverted").assertIsDisplayed()
        composeTestRule.onNodeWithText("UNDO").performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Rescheduled +3 days")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText("Rescheduled +3 days").assertIsDisplayed()
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
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        // #603/#704: the watering StatChip is gone (StatsRow was deleted with the classic layout) —
        // the always-visible Water button in WateringDueActionsRow is the entry point now.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(WATERING_DUE_WATER_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithTag(WATERING_DUE_WATER_BUTTON_TEST_TAG).performClick()
        confirmLogWateringDatePickerWithToday()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Water Fern?")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText("Water Fern?").assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.water_reason_question_late)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.water_reason_soil_still_moist_late)).assertIsDisplayed()
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
            mockQuickLogUseCase.quickWaterWithReason(plant, null, any())
        } returns QuickLogUseCase.QuickLogOutcome(message = "", logged = true)
        val viewModel = makeViewModel(plant, listOf(onScheduleLog), dataStore = mockDataStoreAmplitudeOff)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        // #603/#704: the watering StatChip is gone (StatsRow was deleted with the classic layout) —
        // the always-visible Water button in WateringDueActionsRow is the entry point now.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(WATERING_DUE_WATER_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithTag(WATERING_DUE_WATER_BUTTON_TEST_TAG).performClick()
        confirmLogWateringDatePickerWithToday()
        composeTestRule.waitForIdle()

        assertTrue(
            composeTestRule.onAllNodesWithText("Why now?")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
        coVerify { mockQuickLogUseCase.quickWaterWithReason(plant, null, any()) }
    }

    /**
     * #654: a plain tap on the Water button always opens the "Log watering" date picker first —
     * there is no more instant "log now" fast path on tap — regardless of on/off-schedule status.
     * Dismissing the picker (Cancel) logs nothing at all.
     */
    @Test
    fun waterButton_tap_alwaysOpensLogWateringDatePickerBeforeLoggingAnything() {
        val plant = Plant(id = 26L, name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val onScheduleLog = CareLog(
            id = 100L,
            plantId = plant.id,
            careType = CareType.WATER,
            loggedAt = System.currentTimeMillis() - (7 * 24 * 60 * 60 * 1000L)
        )
        val viewModel = makeViewModel(plant, listOf(onScheduleLog))

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(WATERING_DUE_WATER_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithTag(WATERING_DUE_WATER_BUTTON_TEST_TAG).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(LOG_WATERING_DATE_PICKER_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithTag(LOG_WATERING_DATE_PICKER_TEST_TAG).assertIsDisplayed()
        coVerify(exactly = 0) { mockQuickLogUseCase.quickWaterWithReason(any(), any(), any()) }

        composeTestRule.onNodeWithText(str(R.string.cancel)).performClick()
        composeTestRule.waitForIdle()

        coVerify(exactly = 0) { mockQuickLogUseCase.quickWaterWithReason(any(), any(), any()) }
    }

    /**
     * #530 (product ADR-0060): the Water tab no longer carries a "Water + Fertilize" button — that
     * combined action lives on Home and the Fertilize tab ([FertilizeDueActionRow]). A liquid-fertilizer
     * plant's Water tab shows only the plain Water button, and it still logs a plain watering (never the
     * combined path).
     */
    @Test
    fun wateringDueRow_liquidPlant_showsOnlyThePlainWaterButtonAndLogsPlainWater() {
        val plant = Plant(
            id = 24L,
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
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        // The test opens on the Water tab (initialTab = WATER) — no tab switch needed to reach the button.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(WATERING_DUE_WATER_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithTag(WATERING_DUE_WATER_BUTTON_TEST_TAG).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(str(R.string.water_fertilize_combined_button)).assertCountEquals(0)

        composeTestRule.onNodeWithTag(WATERING_DUE_WATER_BUTTON_TEST_TAG).performClick()
        confirmLogWateringDatePickerWithToday()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Water Ivy?")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText("Water Ivy?").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText("Water & fertilize Ivy?")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    /**
     * #652 round 2: a non-liquid-fertilizer plant's Water tab shows only the plain "Water" button —
     * no combined button at all (since #530 that holds for liquid plants too).
     */
    @Test
    fun wateringDueRow_regularPlant_offSchedule_tapOpensPlainWaterReasonPrompt() {
        val plant = Plant(id = 25L, name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant, listOf(offScheduleWaterLog(plant.id)))

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(WATERING_DUE_WATER_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithTag(WATERING_DUE_WATER_BUTTON_TEST_TAG).performClick()
        confirmLogWateringDatePickerWithToday()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Water Fern?")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText("Water Fern?").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText("Water & fertilize Fern?")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
        assertTrue(
            composeTestRule.onAllNodesWithText(str(R.string.water_fertilize_combined_button))
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
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
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.FERTILIZE
            )
        }

        // #603/#704: the fertilizing StatChip is gone (StatsRow was deleted with the classic layout) —
        // the equivalent action now lives under the Fertilize tab.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG))
        // #652 round 2: the button is relabeled "Water + Fertilize" for a liquid-fertilizer plant.
        composeTestRule.onNode(hasTestTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG) and hasText("Water + Fertilize"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG).performClick()
        confirmLogWateringDatePickerWithToday()
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
            mockQuickLogUseCase.quickLog(plant, CareType.FERTILIZE, any())
        } returns QuickLogUseCase.QuickLogOutcome(message = "", logged = true)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.FERTILIZE
            )
        }

        // #603/#704: the fertilizing StatChip is gone (StatsRow was deleted with the classic layout) —
        // the equivalent action now lives under the Fertilize tab.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG))
        // #652 round 2: a non-liquid-fertilizer plant keeps the plain "Fertilize" label, unchanged.
        composeTestRule.onNode(hasTestTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG) and hasText("Fertilize"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG).performClick()
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

        // The hero/name-header sections push the tab strip below the fold on CI's 320x640
        // emulator; scroll to it first. Custom Reminders/Active Issues moved into their own hidden
        // tabs (#590, product ADR-0043), so they no longer push this any further.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(waterTabMatcher)
        composeTestRule.onNode(homeTabMatcher).assertIsDisplayed()
        composeTestRule.onNode(waterTabMatcher).assertIsDisplayed()
        composeTestRule.onNodeWithText("Fertilize").assertIsDisplayed()
        composeTestRule.onNode(photoTabMatcher).assertIsDisplayed()
        // Repot sits behind the chevron since #530 (product ADR-0060).
        composeTestRule.onAllNodesWithText("Repot").assertCountEquals(0)
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
            .performScrollToNode(homeTabMatcher)
        composeTestRule.onNode(homeTabMatcher).assertIsSelected()
        composeTestRule.onNodeWithText("Fertilize").assertIsNotSelected()

        composeTestRule.onNodeWithText("Fertilize").performClick()

        composeTestRule.onNodeWithText("Fertilize").assertIsSelected()
        composeTestRule.onNode(homeTabMatcher).assertIsNotSelected()
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
        // The hero/name-header sections push the tab strip below the fold on CI's 320x640
        // emulator; scroll to it first. Custom Reminders/Active Issues moved into their own hidden
        // tabs (#590, product ADR-0043), so they no longer push this any further.
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

        // The hero/name-header sections push the tab strip below the fold on CI's 320x640
        // emulator; scroll to it first. Custom Reminders/Active Issues moved into their own hidden
        // tabs (#590, product ADR-0043), so they no longer push this any further.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(photoTabMatcher)
        composeTestRule.onNode(photoTabMatcher).performClick()
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
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        // The inline watering-interval header sits on the Water tab (opened via initialTab), below the always-visible
        // Water/Reschedule actions row (#603 round-3: the actions row now renders first).
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Water every 7 days"))
        composeTestRule.onNodeWithText("Water every 7 days").assertIsDisplayed()
    }

    @Test
    fun waterTab_showsDormancyOffEvenWithoutWateringSchedule() {
        val plant = Plant(id = 133L, name = "Cactus", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasContentDescription("Dormancy window"))
        composeTestRule.onNodeWithContentDescription("Dormancy window").assertIsOff().assertIsDisplayed()
    }

    @Test
    fun fertilizeTab_showsInlineScheduleControl() {
        // No fertilizing interval → the inline control shows its disabled "Fertilizing reminder" header,
        // which is unique to this control. The action button no longer depends on the interval (#532).
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

        // The hero/name-header sections push the tab strip below the fold on CI's 320x640
        // emulator; scroll to it first. Custom Reminders/Active Issues moved into their own hidden
        // tabs (#590, product ADR-0043), so they no longer push this any further.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Fertilize"))
        composeTestRule.onNodeWithText("Fertilize").performClick()
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Fertilizing reminder"))
        composeTestRule.onNodeWithText("Fertilizing reminder").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG).assertCountEquals(1)
    }

    @Test
    fun fertilizeTab_regularPlantWithoutInterval_showsFertilizeAndTapLogsDirectly() {
        val plant = Plant(id = 35L, name = "Sage", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)
        coEvery {
            mockQuickLogUseCase.quickLog(plant, CareType.FERTILIZE, any())
        } returns QuickLogUseCase.QuickLogOutcome(message = "", logged = true)
        showDetail(viewModel, initialTab = PlantDetailTab.FERTILIZE)

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG))
        composeTestRule.onNode(hasTestTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG) and hasText("Fertilize"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Fertilized Sage")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    @Test
    fun fertilizeTab_liquidPlantWithoutInterval_showsWaterAndFertilizeAndOpensDatePicker() {
        val plant = Plant(
            id = 36L,
            name = "Ivy",
            useLiquidFertilizer = true,
            wateringIntervalDays = 7,
            createdAt = 0L,
            updatedAt = 0L
        )
        showDetail(makeViewModel(plant), initialTab = PlantDetailTab.FERTILIZE)

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG))
        composeTestRule.onNode(hasTestTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG) and hasText("Water + Fertilize"))
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(LOG_WATERING_DATE_PICKER_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
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
        coEvery { careLogRepo.getLastWateringBefore(any(), any()) } returns null
        every { plantPhotoRepo.getPhotosForPlant(plant.id) } returns flowOf(emptyList())
        val viewModel =
            PlantDetailViewModel(plantRepo, careLogRepo, plantPhotoRepo, plant.id, mockDataStore, mockQuickLogUseCase, mockCustomReminderRepo, mockPlantIssueRepo, database, wateringAdjustmentRepo)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.REPOT
            )
        }

        // Two repots → the Repot tab's insights card shows the count and an average interval.
        // The hero/name-header sections push the tab strip below the fold on CI's 320x640
        // emulator; scroll to it first. Custom Reminders/Active Issues moved into their own hidden
        // tabs (#590, product ADR-0043), so they no longer push this any further.
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Repottings"))
        composeTestRule.onNodeWithText("Repottings").assertIsDisplayed()
        composeTestRule.onNodeWithText("Avg. interval").assertIsDisplayed()
    }

    @Test
    fun repotTab_actionQuickLogsRepot() {
        val plant = Plant(id = 42L, name = "Yucca", createdAt = 0L, updatedAt = 0L)
        coEvery { mockQuickLogUseCase.quickLog(plant, CareType.REPOT, any()) } returns
            QuickLogUseCase.QuickLogOutcome(message = "Repotted Yucca", logged = true)
        coEvery { mockQuickLogUseCase.maybeBuildPhotoReminderRequest(plant.id) } returns null
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.REPOT
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(REPOT_TAB_ACTION_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithTag(REPOT_TAB_ACTION_BUTTON_TEST_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        // #694: the date picker opens first — nothing is logged yet.
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(REPOT_DATE_PICKER_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithTag(REPOT_DATE_PICKER_TEST_TAG).assertIsDisplayed()
        coVerify(exactly = 0) { mockQuickLogUseCase.quickLog(any(), CareType.REPOT, any()) }

        composeTestRule.onNodeWithText(str(R.string.ok)).performClick()

        coVerify(timeout = 5000) { mockQuickLogUseCase.quickLog(plant, CareType.REPOT, any()) }
    }

    @Test
    fun repotTab_dismissingDatePicker_doesNotLog() {
        val plant = Plant(id = 42L, name = "Yucca", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.REPOT
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(REPOT_TAB_ACTION_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithTag(REPOT_TAB_ACTION_BUTTON_TEST_TAG).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(REPOT_DATE_PICKER_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText(str(R.string.cancel)).performClick()
        composeTestRule.waitForIdle()

        coVerify(exactly = 0) { mockQuickLogUseCase.quickLog(any(), CareType.REPOT, any()) }
    }

    @Test
    fun photoTab_actionOpensAddPhotoSheet() {
        val plant = Plant(id = 43L, name = "Ivy", createdAt = 0L, updatedAt = 0L)
        val viewModel = makeViewModel(plant)
        var navigationRequested = false

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = { navigationRequested = true },
                onNavigateToEditLog = {}
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(photoTabMatcher)
        composeTestRule.onNode(photoTabMatcher).performClick()
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(PHOTO_TAB_ACTION_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithTag(PHOTO_TAB_ACTION_BUTTON_TEST_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
            .performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(ADD_PHOTO_SHEET_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithTag(ADD_PHOTO_SHEET_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(ADD_PHOTO_DATE_ROW_TEST_TAG).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.photo_source_take_photo)).assertIsDisplayed()
        composeTestRule.onNodeWithText(str(R.string.photo_source_choose_gallery)).assertIsDisplayed()

        assertFalse(navigationRequested)
    }

    // #694 acceptance criterion: "Cancelling the dialog or image selection creates no PHOTO log."
    // The Add-photo sheet's default state has no visible Cancel affordance (unlike the Repot date
    // picker) — the system back button is the sheet's only dismissal path here, same as every other
    // bare ModalBottomSheet on this screen (WateringReasonBottomSheet).
    @Test
    fun photoTab_dismissingSheet_doesNotLog() {
        val plant = Plant(id = 44L, name = "Basil", createdAt = 0L, updatedAt = 0L)
        val plantRepo = mockk<PlantRepository>()
        val careLogRepo = mockk<CareLogRepository>()
        val plantPhotoRepo = mockk<PlantPhotoRepository>()
        every { plantRepo.getPlantById(plant.id) } returns flowOf(plant)
        every { careLogRepo.getLogsForPlant(plant.id) } returns flowOf(emptyList())
        every { careLogRepo.getPhotoLogsForPlant(plant.id) } returns flowOf(emptyList())
        coEvery { careLogRepo.getLastWateringBefore(any(), any()) } returns null
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
            .performScrollToNode(photoTabMatcher)
        composeTestRule.onNode(photoTabMatcher).performClick()
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(PHOTO_TAB_ACTION_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithTag(PHOTO_TAB_ACTION_BUTTON_TEST_TAG).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(ADD_PHOTO_SHEET_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithTag(ADD_PHOTO_SHEET_TEST_TAG).assertIsDisplayed()

        Espresso.pressBack()
        composeTestRule.waitForIdle()

        coVerify(exactly = 0) { careLogRepo.addLog(any()) }
        coVerify(exactly = 0) { plantRepo.updatePlant(any()) }
    }

    // ---- Tab row collapse/expand + attention badge (#590, product ADR-0043) ----

    @Test
    fun tabRow_collapsedByDefault_hidesRepotRemindersAndIssuesTabs() {
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
            composeTestRule.onAllNodesWithText(repotTabLabel())
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
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
        composeTestRule.onNodeWithText(repotTabLabel()).assertIsDisplayed()
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
    fun tabRow_collapsingWhileOnHiddenTab_resetsSelectionToHome() {
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
        // Asserts selection via the tab's own semantics instead of scrolling down to its section
        // content: the tab strip (toggle included) is one LazyColumn item, CustomRemindersCard a
        // separate later one, so scrolling down there and back up to `plant_detail_tabs_toggle`
        // decomposes the toggle's item and forces performScrollToNode's expensive "reset to index 0,
        // rescan forward a viewport at a time" recovery path — the root cause of #592's CI timeout.
        // Don't reintroduce that round-trip; keep this check scroll-free.
        composeTestRule.onNodeWithText(customRemindersTabLabel()).assertIsSelected()

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("plant_detail_tabs_toggle"))
        composeTestRule.onNodeWithTag("plant_detail_tabs_toggle").performClick()

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(homeTabMatcher)
        composeTestRule.onNode(homeTabMatcher).assertIsSelected()
        assertTrue(
            composeTestRule.onAllNodesWithText(customRemindersSectionLabel())
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    // ---- Home tab (#530, product ADR-0060) ----

    private fun showDetail(viewModel: PlantDetailViewModel, initialTab: PlantDetailTab? = null) {
        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = initialTab
            )
        }
    }

    @Test
    fun homeTab_showsLastAndNextWateringAndFertilizingRows() {
        val day = 24 * 60 * 60 * 1000L
        val plant = Plant(
            id = 110L,
            name = "Fern",
            wateringIntervalDays = 7,
            fertilizingIntervalDays = 30,
            createdAt = 0L,
            updatedAt = 0L
        )
        val now = System.currentTimeMillis()
        val logs = listOf(
            CareLog(id = 1L, plantId = plant.id, careType = CareType.WATER, loggedAt = now - 2 * day),
            CareLog(id = 2L, plantId = plant.id, careType = CareType.FERTILIZE, loggedAt = now - 3 * day)
        )
        showDetail(makeViewModel(plant, logs))

        listOf(
            R.string.insight_last_watered,
            R.string.home_summary_next_watering,
            R.string.insight_last_fertilized,
            R.string.home_summary_next_fertilizing
        ).forEach { labelRes ->
            composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
                .performScrollToNode(hasText(str(labelRes)))
            composeTestRule.onNodeWithText(str(labelRes)).assertIsDisplayed()
        }
    }

    @Test
    fun homeTab_neverWatered_readsNeverWatered() {
        val plant = Plant(id = 111L, name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        showDetail(makeViewModel(plant))

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(str(R.string.water_label_never_watered)))
        composeTestRule.onNodeWithText(str(R.string.water_label_never_watered)).assertIsDisplayed()
    }

    @Test
    fun homeTab_withoutFertilizingInterval_hidesFertilizingRowsButKeepsTheButton() {
        val plant = Plant(id = 112L, name = "Cactus", wateringIntervalDays = 14, createdAt = 0L, updatedAt = 0L)
        showDetail(makeViewModel(plant))

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG))
        composeTestRule.onAllNodesWithTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG).assertCountEquals(1)
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(str(R.string.home_summary_next_watering)))
        composeTestRule.onAllNodesWithText(str(R.string.insight_last_fertilized)).assertCountEquals(0)
        composeTestRule.onAllNodesWithText(str(R.string.home_summary_next_fertilizing)).assertCountEquals(0)
    }

    @Test
    fun homeTab_liquidPlantWithoutFertilizingInterval_showsTheCombinedButton() {
        val plant = Plant(
            id = 119L,
            name = "Ivy",
            useLiquidFertilizer = true,
            wateringIntervalDays = 7,
            createdAt = 0L,
            updatedAt = 0L
        )
        showDetail(makeViewModel(plant))

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG))
        composeTestRule.onAllNodesWithText(str(R.string.water_fertilize_combined_button)).assertCountEquals(1)
        composeTestRule.onAllNodesWithTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG).assertCountEquals(1)
    }

    @Test
    fun homeTab_liquidFertilizerPlant_showsExactlyOneWaterAndFertilizeButton() {
        val plant = Plant(
            id = 113L,
            name = "Ivy",
            wateringIntervalDays = 7,
            fertilizingIntervalDays = 30,
            useLiquidFertilizer = true,
            createdAt = 0L,
            updatedAt = 0L
        )
        showDetail(makeViewModel(plant))

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG))
        composeTestRule.onAllNodesWithText(str(R.string.water_fertilize_combined_button)).assertCountEquals(1)
    }

    @Test
    fun homeTab_liquidPlant_offSchedule_tapOpensCombinedReasonPrompt() {
        val plant = Plant(
            id = 118L,
            name = "Ivy",
            useLiquidFertilizer = true,
            fertilizingIntervalDays = 30,
            wateringIntervalDays = 7,
            createdAt = 0L,
            updatedAt = 0L
        )
        showDetail(makeViewModel(plant, listOf(offScheduleWaterLog(plant.id))))

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithTag(FERTILIZE_DUE_ACTION_BUTTON_TEST_TAG).performClick()
        confirmLogWateringDatePickerWithToday()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText("Water & fertilize Ivy?")
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText("Water & fertilize Ivy?").assertIsDisplayed()
    }

    @Test
    fun homeTab_waterButton_opensTheLogWateringDatePicker() {
        val plant = Plant(id = 114L, name = "Fern", wateringIntervalDays = 7, createdAt = 0L, updatedAt = 0L)
        showDetail(makeViewModel(plant))

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(WATERING_DUE_WATER_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithTag(WATERING_DUE_WATER_BUTTON_TEST_TAG).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(LOG_WATERING_DATE_PICKER_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    @Test
    fun tabRow_attentionBadge_visibleWithOverdueRepot() {
        val day = 24 * 60 * 60 * 1000L
        val plant = Plant(id = 115L, name = "Yucca", repottingIntervalDays = 30, createdAt = 0L, updatedAt = 0L)
        val repottedAt = System.currentTimeMillis() - 100 * day
        val logs = listOf(CareLog(id = 1L, plantId = plant.id, careType = CareType.REPOT, loggedAt = repottedAt))
        showDetail(makeViewModel(plant, logs))

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("plant_detail_tabs_toggle"))
        composeTestRule.onNodeWithContentDescription(tabsExpandAttentionCd()).assertIsDisplayed()

        composeTestRule.onNodeWithTag("plant_detail_tabs_toggle").performClick()

        composeTestRule.onNodeWithContentDescription(tabsCollapseCd()).assertIsDisplayed()
    }

    @Test
    fun tabRow_noAttentionBadge_whenRepotIsNotOverdue() {
        val plant = Plant(id = 116L, name = "Yucca", repottingIntervalDays = 365, createdAt = 0L, updatedAt = 0L)
        val logs = listOf(
            CareLog(id = 1L, plantId = plant.id, careType = CareType.REPOT, loggedAt = System.currentTimeMillis())
        )
        showDetail(makeViewModel(plant, logs))

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("plant_detail_tabs_toggle"))
        composeTestRule.onNodeWithContentDescription(tabsExpandCd()).assertIsDisplayed()
        composeTestRule.onAllNodesWithContentDescription(tabsExpandAttentionCd()).assertCountEquals(0)
    }

    @Test
    fun initialTab_repot_expandsTabRowAndSelectsIt() {
        val plant = Plant(id = 117L, name = "Yucca", createdAt = 0L, updatedAt = 0L)
        showDetail(makeViewModel(plant), initialTab = PlantDetailTab.REPOT)

        composeTestRule.onNode(hasText(repotTabLabel()) and isSelectable()).assertIsSelected()
        composeTestRule.onNodeWithContentDescription(tabsCollapseCd()).assertExists()
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

    private fun repotTabLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.plant_detail_tab_repot)

    private fun issuesTabLabel(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.plant_detail_tab_issues)

    private fun tabsExpandCd(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.plant_detail_tabs_expand_cd)

    private fun tabsExpandAttentionCd(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.plant_detail_tabs_expand_attention_cd)

    /**
     * "Water" is ambiguous on Plant Detail as of #704: the Water tab
     * ([R.string.plant_detail_tab_water]) and `WateringDueActionsRow`'s Water button
     * ([R.string.watering_due_action_water]) render that same literal text, and since product
     * ADR-0040 the button renders for every plant, including one with no configured watering
     * interval — where previously the whole row was gated behind `wateringIntervalDays != null`
     * and the tab was the only "Water" on screen. Match the tab by the selected/not-selected
     * semantics these tests actually assert on rather than by text alone (#420: assert
     * user-visible semantics, never tree structure).
     */
    private val waterTabMatcher = hasText("Water") and isSelectable()

    private val homeTabMatcher = hasText("Home") and isSelectable()

    /**
     * "Photo" is potentially ambiguous on Plant Detail the same way "Water" is (see
     * [waterTabMatcher]'s KDoc): the Photo tab ([R.string.plant_detail_tab_photo]) and
     * [R.string.care_type_photo] (rendered as a standalone `Text` by `CareLogItem` for any
     * `CareType.PHOTO` entry in Home's combined care-history list, #530) are both the literal
     * text "Photo". Not a live bug in the fixtures these tests use today — none carries a PHOTO
     * log — but preemptive hardening against the same class of collision, matched by the
     * selected/not-selected semantics rather than by text alone (#420).
     */
    private val photoTabMatcher = hasText("Photo") and isSelectable()

    private fun tabsCollapseCd(): String = InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.plant_detail_tabs_collapse_cd)

    /**
     * Custom Reminders/Active Issues moved from always-visible cards into their own tabs (#590,
     * product ADR-0043) — hidden behind the collapsed tab row by default. Scrolls to and taps the
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
        // 10s, not the usual 5s: kept from an earlier attempt at #592, when
        // tabRow_collapsingWhileOnHiddenTab_resetsSelectionToWater timed out here twice in CI and the
        // "Failed to find ColorBuffer" warnings alongside it were read as emulator rendering slowness.
        // That diagnosis was wrong — #592's real cause was a redundant scroll round-trip in that test
        // (see its own comment), fixed there. The wider timeout is retained only as harmless headroom;
        // it is not load-bearing, and no test should be written to depend on it.
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
        // Column-specific writes (#808): the reschedule revert/undo and the cover-photo actions no longer
        // round-trip through a full-row updatePlant().
        coEvery { repo.updateWateringDueDateOverride(any(), any(), any()) } answers {
            state.value = state.value.copy(
                wateringDueDateOverride = it.invocation.args[1] as Long?,
                updatedAt = it.invocation.args[2] as Long
            )
        }
        coEvery { repo.updateCoverPhotoUri(any(), any(), any()) } answers {
            state.value = state.value.copy(
                coverPhotoUri = it.invocation.args[1] as String?,
                updatedAt = it.invocation.args[2] as Long
            )
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
            coEvery { it.getLastWateringBefore(any(), any()) } returns null
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
            coEvery { it.getLastWateringBefore(any(), any()) } returns null
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
        coEvery { careLogRepo.getLastWateringBefore(any(), any()) } returns null
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

    private fun seasonalCurveTodayDaysText(days: Int): String =
        InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.seasonal_curve_today_days, days)

    private fun seasonalCurvePinnedNoteText(): String =
        InstrumentationRegistry.getInstrumentation().targetContext
            .getString(R.string.seasonal_curve_pinned_note)

    /**
     * The seasonal-curve preview chart (#579) renders in the Water tab's inline settings card
     * alongside the "Pin interval" switch, unconditionally (seasonal watering graduated, #656).
     * On Plant Detail the caption is in whole days (#622), not the raw multiplier — asserts the
     * visible "Today" caption text, computed the same way the chart itself does — never chart
     * canvas/tree structure, per #420.
     */
    @Test
    fun seasonalCurveChart_todayCaption_isDisplayed() {
        val plant = Plant(id = 70L, name = "Aloe", createdAt = 0L, updatedAt = 0L, wateringIntervalDays = 7)
        val viewModel = makeViewModelWithPlantRepo(plant, reactivePlantRepo(plant))

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        val expectedMultiplier = SeasonalWatering.season(
            LocalDate.now(),
            SeasonalAmplitude.STANDARD.value,
            SeasonalWatering.currentHemisphere()
        )
        val baseIntervalDays = plant.wateringBaseIntervalDays ?: plant.wateringIntervalDays!!.toDouble()
        val expectedDays = (expectedMultiplier * baseIntervalDays).roundToInt()

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText(seasonalCurveTodayDaysText(expectedDays)))
        composeTestRule.onNodeWithText(seasonalCurveTodayDaysText(expectedDays)).assertIsDisplayed()
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
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
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
        val viewModel = makeViewModelWithPlantRepo(plant, reactivePlantRepo(plant), dataStore = mockDataStoreWithSeasonal)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
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

    /**
     * The "Why this date?" sheet's display-only mirror of the reschedule delta chip (#630) — same
     * wording, no tap action of its own (the chip outside the sheet is the only actionable UI).
     */
    @Test
    fun wateringExplanationSheet_showsRescheduleDeltaRow_whenOverrideExceedsSchedule() {
        val dayInMs = 24 * 60 * 60 * 1000L
        val plant = Plant(
            id = 82L,
            name = "Deferred Plant",
            createdAt = 0L,
            updatedAt = 0L,
            wateringIntervalDays = 7,
            wateringDueDateOverride = System.currentTimeMillis() + (3 * dayInMs)
        )
        val viewModel = makeViewModelWithPlantRepo(plant, reactivePlantRepo(plant), dataStore = mockDataStoreWithSeasonal)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("why_this_date_button"))
        composeTestRule.onNodeWithTag("why_this_date_button").performClick()

        composeTestRule.onNodeWithTag("watering_explanation_sheet").assertIsDisplayed()
        composeTestRule.onNode(
            hasText("Rescheduled +3 days").and(hasAnyAncestor(hasTestTag("watering_explanation_sheet")))
        ).assertIsDisplayed()
    }

    @Test
    fun wateringExplanationSheet_hidesRescheduleDeltaRow_whenNoOverride() {
        val plant = Plant(id = 83L, name = "Snake Plant", createdAt = 0L, updatedAt = 0L, wateringIntervalDays = 9)
        val viewModel = makeViewModelWithPlantRepo(plant, reactivePlantRepo(plant), dataStore = mockDataStoreWithSeasonal)

        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.WATER
            )
        }

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag("why_this_date_button"))
        composeTestRule.onNodeWithTag("why_this_date_button").performClick()

        composeTestRule.onNodeWithTag("watering_explanation_sheet").assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText("Rescheduled", substring = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }

    // ---- Repot tab: plan a repot (#809, product ADR-0057) ----

    private fun reactivePlantRepoWithRepotPlan(initial: Plant): PlantRepository {
        val state = MutableStateFlow(initial)
        val repo = mockk<PlantRepository>()
        every { repo.getPlantById(initial.id) } returns state
        coEvery { repo.updatePlant(any()) } answers { state.value = it.invocation.args[0] as Plant }
        coEvery { repo.updateWateringDueDateOverride(any(), any(), any()) } answers {
            state.value = state.value.copy(
                wateringDueDateOverride = it.invocation.args[1] as Long?,
                updatedAt = it.invocation.args[2] as Long
            )
        }
        coEvery { repo.updateCoverPhotoUri(any(), any(), any()) } answers {
            state.value = state.value.copy(
                coverPhotoUri = it.invocation.args[1] as String?,
                updatedAt = it.invocation.args[2] as Long
            )
        }
        coEvery { repo.setRepotPlan(any(), any(), any(), any()) } answers {
            state.value = state.value.copy(
                repotPlanSeasonStartAt = it.invocation.args[1] as Long,
                repotPlanMadeAt = it.invocation.args[2] as Long
            )
        }
        coEvery { repo.clearRepotPlan(any(), any()) } answers {
            state.value = state.value.copy(repotPlanSeasonStartAt = null, repotPlanMadeAt = null)
        }
        return repo
    }

    private fun openRepotTab(viewModel: PlantDetailViewModel) {
        composeTestRule.setContent {
            PlantDetailScreen(
                viewModel = viewModel,
                onNavigateBack = {},
                onNavigateToEdit = {},
                onNavigateToAddLog = {},
                onNavigateToEditLog = {},
                initialTab = PlantDetailTab.REPOT
            )
        }
    }

    @Test
    fun repotTab_planRepot_offersTheUpcomingSeasonsAndShowsThePlanWithEditAndClear() {
        val plant = Plant(id = 90L, name = "Yucca", createdAt = 0L, updatedAt = 0L)
        val plantRepo = reactivePlantRepoWithRepotPlan(plant)
        val upcoming = SeasonalRepotting.upcomingSeasons(LocalDate.now(), SeasonalWatering.currentHemisphere())
        val chosen = upcoming[1]
        openRepotTab(makeViewModelWithPlantRepo(plant, plantRepo))

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(REPOT_PLAN_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithText(str(R.string.repot_plan_action)).assertIsDisplayed().performClick()

        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(REPOT_PLAN_DIALOG_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        upcoming.forEach { option ->
            composeTestRule.onNodeWithText("${str(option.season.labelRes())} ${option.year}").assertIsDisplayed()
        }
        coVerify(exactly = 0) { plantRepo.setRepotPlan(any(), any(), any(), any()) }

        composeTestRule.onNodeWithText("${str(chosen.season.labelRes())} ${chosen.year}").performClick()

        coVerify(timeout = 5000) { plantRepo.setRepotPlan(90L, chosen.startAtMillis, any(), any()) }
        val plannedText = InstrumentationRegistry.getInstrumentation().targetContext
            .getString(chosen.season.repotPlanLabelRes(), chosen.year)
        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG).performScrollToNode(hasText(plannedText))
        composeTestRule.onNodeWithText(plannedText).assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription(str(R.string.repot_plan_edit_cd)).assertHasClickAction()

        composeTestRule.onNodeWithContentDescription(str(R.string.repot_plan_clear_cd)).performClick()

        coVerify(timeout = 5000) { plantRepo.clearRepotPlan(90L, any()) }
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithText(plannedText)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        }
    }

    @Test
    fun repotTab_planEdit_reopensThePickerAndReplacesThePlan() {
        val upcoming = SeasonalRepotting.upcomingSeasons(LocalDate.now(), SeasonalWatering.currentHemisphere())
        val plant = Plant(
            id = 91L,
            name = "Yucca",
            createdAt = 0L,
            updatedAt = 0L,
            repotPlanSeasonStartAt = upcoming[0].startAtMillis,
            repotPlanMadeAt = 1L
        )
        val plantRepo = reactivePlantRepoWithRepotPlan(plant)
        val replacement = upcoming[2]
        openRepotTab(makeViewModelWithPlantRepo(plant, plantRepo))

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasContentDescription(str(R.string.repot_plan_edit_cd)))
        composeTestRule.onNodeWithContentDescription(str(R.string.repot_plan_edit_cd)).performClick()
        composeTestRule.waitUntil(timeoutMillis = 5000) {
            composeTestRule.onAllNodesWithTag(REPOT_PLAN_DIALOG_TEST_TAG)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        composeTestRule.onNodeWithText("${str(replacement.season.labelRes())} ${replacement.year}").performClick()

        coVerify(timeout = 5000) { plantRepo.setRepotPlan(91L, replacement.startAtMillis, any(), any()) }
    }

    @Test
    fun repotTab_withoutPlan_showsNextIntervalDueDateAndPreferredSeasonsWhenNotAllFour() {
        val plant = Plant(
            id = 92L,
            name = "Yucca",
            createdAt = System.currentTimeMillis(),
            updatedAt = 0L,
            repottingIntervalDays = 360,
            repottingSeasons = setOf(FertilizingSeason.SPRING, FertilizingSeason.SUMMER)
        )
        openRepotTab(makeViewModelWithPlantRepo(plant, reactivePlantRepoWithRepotPlan(plant)))

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasText("Next repot due", substring = true))
        composeTestRule.onNodeWithText("Next repot due", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(
            InstrumentationRegistry.getInstrumentation().targetContext.getString(
                R.string.repot_plan_preferred_seasons,
                "${str(R.string.season_spring)}, ${str(R.string.season_summer)}"
            )
        ).assertIsDisplayed()
    }

    @Test
    fun repotTab_withNeitherPlanNorInterval_showsOnlyThePlanAction() {
        val plant = Plant(id = 93L, name = "Yucca", createdAt = 0L, updatedAt = 0L)
        openRepotTab(makeViewModelWithPlantRepo(plant, reactivePlantRepoWithRepotPlan(plant)))

        composeTestRule.onNodeWithTag(PLANT_DETAIL_CONTENT_TEST_TAG)
            .performScrollToNode(hasTestTag(REPOT_PLAN_BUTTON_TEST_TAG))
        composeTestRule.onNodeWithText(str(R.string.repot_plan_action)).assertIsDisplayed()
        assertTrue(
            composeTestRule.onAllNodesWithText("Next repot due", substring = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
        assertTrue(
            composeTestRule.onAllNodesWithText("Planned:", substring = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isEmpty()
        )
    }
}
