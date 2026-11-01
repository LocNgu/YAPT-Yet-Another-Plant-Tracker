package com.yapt.planttracker.ui.screens.away

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.AwayPeriodRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.away.AwayPeriodValidation
import com.yapt.planttracker.domain.away.AwayTripStatus
import com.yapt.planttracker.domain.away.VISIT_CONFIRM_WINDOW_DAYS
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.SitterVisit
import com.yapt.planttracker.domain.model.SitterVisitPlant
import com.yapt.planttracker.domain.model.SitterVisitState
import com.yapt.planttracker.util.MainDispatcherRule
import com.yapt.planttracker.worker.ReminderScheduler
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.Executor

/** Away mode (#898, product ADR-0065): planning, editing, cancelling and ending a trip; nothing here writes a plant. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AwayModeViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: PlantDatabase
    private lateinit var repository: AwayPeriodRepository
    private var reminderRuns = 0

    private val today = LocalDate.of(2026, 10, 14)

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Room on the test thread (#912): on its own threads, a mutation resumes there and its event reaches the
        // unconfined collector after join() has already returned to the test body.
        val synchronousExecutor = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(synchronousExecutor)
            .setTransactionExecutor(synchronousExecutor)
            .build()
        repository = AwayPeriodRepository(db.awayPeriodDao())
        reminderRuns = 0
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun viewModel(dayChange: Flow<LocalDate> = flowOf(today)) = AwayModeViewModel(
        repository,
        onTodaysAwayStateChanged = { reminderRuns++ },
        dayChange = dayChange,
        todayProvider = { today }
    )

    private fun TestScope.collectEvents(vm: AwayModeViewModel, into: MutableList<AwayModeEvent>) {
        backgroundScope.launch(mainDispatcherRule.testDispatcher) { vm.events.collect { into += it } }
    }

    private suspend fun addTrip(first: LocalDate, last: LocalDate): AwayPeriod {
        val id = repository.addPeriod(AwayPeriod(firstDay = first, lastDay = last, createdAt = 1L))
        return AwayPeriod(id, first, last, createdAt = 1L)
    }

    private suspend fun addVisit(tripId: Long, day: LocalDate) =
        repository.addVisit(SitterVisit(awayPeriodId = tripId, day = day, createdAt = 1L))

    private suspend fun addVisitWithPlant(tripId: Long, day: LocalDate): Long {
        val plantId = PlantRepository(db.plantDao())
            .addPlant(Plant(name = "Aloe", wateringIntervalDays = 7, createdAt = 1L, updatedAt = 1L))
        return repository.addVisit(
            SitterVisit(
                awayPeriodId = tripId,
                day = day,
                plants = listOf(SitterVisitPlant(plantId)),
                createdAt = 1L
            )
        )
    }

    @Test
    fun `planning a trip stores it and does not touch reminders when it starts later`() = runTest {
        val vm = viewModel()

        vm.saveTrip(today.plusDays(10), today.plusDays(20), null).join()

        val stored = repository.getAll().single()
        assertEquals(today.plusDays(10), stored.firstDay)
        assertEquals(today.plusDays(20), stored.lastDay)
        assertEquals(0, reminderRuns)
    }

    @Test
    fun `planning a trip that includes today refreshes the reminders once`() = runTest {
        val vm = viewModel()

        vm.saveTrip(today, today.plusDays(3), null).join()

        assertEquals(1, reminderRuns)
    }

    @Test
    fun `a trip that already started is accepted while it is ongoing`() = runTest {
        val vm = viewModel()

        vm.saveTrip(today.minusDays(3), today.plusDays(3), null).join()

        assertEquals(today.minusDays(3), repository.getAll().single().firstDay)
        assertEquals(1, reminderRuns)
    }

    private suspend fun TestScope.assertRejected(
        vm: AwayModeViewModel,
        first: LocalDate,
        last: LocalDate,
        editingId: Long? = null,
        expected: (AwayPeriodValidation) -> Boolean
    ) {
        val events = mutableListOf<AwayModeEvent>()
        collectEvents(vm, events)
        val before = repository.getAll()

        vm.saveTrip(first, last, editingId).join()

        val rejected = events.single() as AwayModeEvent.Rejected
        assertTrue("unexpected validation ${rejected.validation}", expected(rejected.validation))
        assertEquals(before, repository.getAll())
        assertEquals(0, reminderRuns)
    }

    @Test
    fun `a last day in the past is rejected`() = runTest {
        assertRejected(viewModel(), today.minusDays(5), today.minusDays(1)) {
            it == AwayPeriodValidation.EndsInThePast
        }
    }

    @Test
    fun `a trip over ninety days is rejected`() = runTest {
        assertRejected(viewModel(), today.plusDays(1), today.plusDays(91)) { it == AwayPeriodValidation.TooLong }
    }

    @Test
    fun `an overlap with another trip is rejected`() = runTest {
        addTrip(today.plusDays(5), today.plusDays(10))

        assertRejected(viewModel(), today.plusDays(8), today.plusDays(15)) {
            it is AwayPeriodValidation.OverlapsTrip
        }
    }

    @Test
    fun `planning while another trip is already planned is rejected`() = runTest {
        addTrip(today.plusDays(5), today.plusDays(10))

        assertRejected(viewModel(), today.plusDays(30), today.plusDays(35)) {
            it is AwayPeriodValidation.AnotherCurrentOrUpcomingTrip
        }
    }

    @Test
    fun `editing a trip never conflicts with itself and keeps its identity`() = runTest {
        val trip = addTrip(today.plusDays(5), today.plusDays(10))
        val vm = viewModel()

        vm.saveTrip(today.plusDays(6), today.plusDays(12), trip.id).join()

        val stored = repository.getAll().single()
        assertEquals(trip.id, stored.id)
        assertEquals(today.plusDays(6), stored.firstDay)
        assertEquals(today.plusDays(12), stored.lastDay)
        assertEquals(1L, stored.createdAt)
    }

    @Test
    fun `editing the dates so today is no longer away refreshes the reminders`() = runTest {
        val trip = addTrip(today.minusDays(1), today.plusDays(2))
        val vm = viewModel()

        vm.saveTrip(today.plusDays(3), today.plusDays(6), trip.id).join()

        assertEquals(1, reminderRuns)
    }

    @Test
    fun `an edit deletes the sitter visits it strands and reports how many`() = runTest {
        val trip = addTrip(today.minusDays(2), today.plusDays(8))
        addVisit(trip.id, today.minusDays(1))
        addVisit(trip.id, today.plusDays(2))
        addVisit(trip.id, today.plusDays(7))
        val vm = viewModel()
        val events = mutableListOf<AwayModeEvent>()
        collectEvents(vm, events)

        vm.saveTrip(today.minusDays(2), today.plusDays(3), trip.id).join()

        val visits = repository.getAllWithVisits().single().visits
        assertEquals(listOf(today.minusDays(1), today.plusDays(2)), visits.map { it.day })
        assertEquals(listOf<AwayModeEvent>(AwayModeEvent.VisitsRemoved(1)), events)
    }

    @Test
    fun `cancelling an upcoming trip removes it without refreshing reminders`() = runTest {
        addTrip(today.plusDays(5), today.plusDays(10))
        val vm = viewModel()

        vm.cancelTrip().join()

        assertTrue(repository.getAll().isEmpty())
        assertEquals(0, reminderRuns)
    }

    @Test
    fun `cancel trip leaves an ongoing trip alone`() = runTest {
        addTrip(today.minusDays(1), today.plusDays(5))
        val vm = viewModel()

        vm.cancelTrip().join()

        assertEquals(1, repository.getAll().size)
        assertEquals(0, reminderRuns)
    }

    @Test
    fun `I'm back on the first day removes the trip and refreshes the reminders`() = runTest {
        val trip = addTrip(today, today.plusDays(6))
        addVisit(trip.id, today.plusDays(3))
        val vm = viewModel()
        val events = mutableListOf<AwayModeEvent>()
        collectEvents(vm, events)

        vm.imBack().join()

        assertTrue(repository.getAll().isEmpty())
        assertEquals(listOf<AwayModeEvent>(AwayModeEvent.VisitsRemoved(1)), events)
        assertEquals(1, reminderRuns)
    }

    @Test
    fun `I'm back mid-trip makes yesterday the last day and deletes the visits after it`() = runTest {
        val trip = addTrip(today.minusDays(4), today.plusDays(6))
        addVisit(trip.id, today.minusDays(2))
        addVisit(trip.id, today)
        addVisit(trip.id, today.plusDays(4))
        val vm = viewModel()
        val events = mutableListOf<AwayModeEvent>()
        collectEvents(vm, events)

        vm.imBack().join()

        val stored = repository.getAllWithVisits().single()
        assertEquals(today.minusDays(1), stored.period.lastDay)
        assertEquals(listOf(today.minusDays(2)), stored.visits.map { it.day })
        assertEquals(listOf<AwayModeEvent>(AwayModeEvent.VisitsRemoved(2)), events)
        assertEquals(1, reminderRuns)
    }

    @Test
    fun `I'm back reports nothing when no visit is stranded`() = runTest {
        addTrip(today.minusDays(4), today.plusDays(6))
        val vm = viewModel()
        val events = mutableListOf<AwayModeEvent>()
        collectEvents(vm, events)

        vm.imBack().join()

        assertTrue(events.isEmpty())
    }

    @Test
    fun `I'm back does nothing for a trip that has not started`() = runTest {
        addTrip(today.plusDays(2), today.plusDays(6))
        val vm = viewModel()

        vm.imBack().join()

        assertEquals(today.plusDays(6), repository.getAll().single().lastDay)
        assertEquals(0, reminderRuns)
    }

    @Test
    fun `planning editing cancelling and ending a trip never change a plant row`() = runTest {
        val plantRepository = PlantRepository(db.plantDao())
        plantRepository.addPlant(
            Plant(
                name = "Fern",
                wateringIntervalDays = 7,
                wateringBaseIntervalDays = 7.0,
                wateringDueDateOverride = 12_345L,
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        plantRepository.addPlant(Plant(name = "Cactus", wateringIntervalDays = 21, createdAt = 2L, updatedAt = 2L))
        val before = plantRepository.getAllPlants().first()
        val vm = viewModel()

        vm.saveTrip(today.plusDays(2), today.plusDays(9), null).join()
        val id = repository.getAll().single().id
        vm.saveTrip(today.minusDays(1), today.plusDays(9), id).join()
        vm.imBack().join()
        vm.saveTrip(today.plusDays(20), today.plusDays(25), null).join()
        vm.cancelTrip().join()

        assertEquals(before, plantRepository.getAllPlants().first())
    }

    @Test
    fun `the state and banner follow the day change without a restart`() = runTest {
        addTrip(today.plusDays(10), today.plusDays(12))
        val dayChange = MutableSharedFlow<LocalDate>(replay = 1)
        val vm = viewModel(dayChange)

        vm.uiState.test {
            assertNull(awaitItem())
            dayChange.emit(today)
            val farAway = awaitItem()!!
            assertTrue(farAway.status is AwayTripStatus.Upcoming)
            assertFalse(farAway.showsBanner)

            dayChange.emit(today.plusDays(3))
            assertTrue(awaitItem()!!.showsBanner)

            dayChange.emit(today.plusDays(10))
            assertTrue(awaitItem()!!.status is AwayTripStatus.Away)

            dayChange.emit(today.plusDays(13))
            val after = awaitItem()!!
            assertEquals(AwayTripStatus.None, after.status)
            assertFalse(after.showsBanner)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `before you leave opens a week out and closes on the first day, following the day change`() = runTest {
        addTrip(today.plusDays(10), today.plusDays(12))
        val dayChange = MutableSharedFlow<LocalDate>(replay = 1)
        val vm = viewModel(dayChange)

        vm.uiState.test {
            assertNull(awaitItem())
            dayChange.emit(today.plusDays(2))
            assertFalse(awaitItem()!!.canOpenBeforeYouLeave)

            dayChange.emit(today.plusDays(3))
            assertTrue(awaitItem()!!.canOpenBeforeYouLeave)

            dayChange.emit(today.plusDays(8))
            assertTrue(awaitItem()!!.canOpenBeforeYouLeave)

            dayChange.emit(today.plusDays(9))
            assertTrue(awaitItem()!!.canOpenBeforeYouLeave)

            dayChange.emit(today.plusDays(10))
            val away = awaitItem()!!
            assertTrue(away.status is AwayTripStatus.Away)
            assertFalse(away.canOpenBeforeYouLeave)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `before you leave follows a trip edit and a cancel with no restart`() = runTest {
        val trip = addTrip(today.plusDays(3), today.plusDays(8))
        val vm = viewModel()

        vm.uiState.test {
            val planned = awaitItem() ?: awaitItem()!!
            assertTrue(planned.canOpenBeforeYouLeave)

            vm.saveTrip(today.plusDays(12), today.plusDays(15), trip.id).join()
            assertFalse(awaitItem()!!.canOpenBeforeYouLeave)

            vm.saveTrip(today.plusDays(2), today.plusDays(15), trip.id).join()
            assertTrue(awaitItem()!!.canOpenBeforeYouLeave)

            vm.cancelTrip().join()
            assertFalse(awaitItem()!!.canOpenBeforeYouLeave)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the visits to confirm appear on the first day back and lapse after the window at midnight`() = runTest {
        val trip = addTrip(today.minusDays(10), today.plusDays(1))
        addVisitWithPlant(trip.id, today.minusDays(4))
        addVisitWithPlant(trip.id, today.minusDays(1))
        val dayChange = MutableSharedFlow<LocalDate>(replay = 1)
        val vm = viewModel(dayChange)

        vm.uiState.test {
            assertNull(awaitItem())
            dayChange.emit(today)
            assertTrue(awaitItem()!!.visitsToConfirm.isEmpty())

            dayChange.emit(today.plusDays(2))
            val back = awaitItem()!!
            assertEquals(2, back.visitsToConfirm.size)
            assertTrue(back.visitsToConfirm[0].day < back.visitsToConfirm[1].day)

            dayChange.emit(today.plusDays(1 + VISIT_CONFIRM_WINDOW_DAYS))
            assertEquals(2, awaitItem()!!.visitsToConfirm.size)

            dayChange.emit(today.plusDays(2 + VISIT_CONFIRM_WINDOW_DAYS))
            assertTrue(awaitItem()!!.visitsToConfirm.isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `an answered visit leaves the offer and Settings keeps listing it`() = runTest {
        val trip = addTrip(today.minusDays(10), today.minusDays(1))
        val answered = addVisitWithPlant(trip.id, today.minusDays(4))
        addVisitWithPlant(trip.id, today.minusDays(2))
        val stored = repository.getAllWithVisits().single().visits.first { it.id == answered }
        repository.updateVisit(stored.copy(state = SitterVisitState.DID_NOT_HAPPEN))
        val vm = viewModel()

        vm.uiState.test {
            val state = awaitItem() ?: awaitItem()!!
            assertEquals(1, state.visitsToConfirm.size)
            assertEquals(2, state.settingsVisits.size)
            assertEquals(
                listOf(SitterVisitState.DID_NOT_HAPPEN, SitterVisitState.PLANNED),
                state.settingsVisits.map { it.state }
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `the factory skips the immediate run while reminders are switched off`() = runTest {
        mockkObject(ReminderScheduler)
        every { ReminderScheduler.runNow(any()) } just Runs
        try {
            val factory = factoryWith(notificationsEnabled = false)
            val vm = factory.create(AwayModeViewModel::class.java)

            vm.saveTrip(LocalDate.now(), LocalDate.now().plusDays(2), null).join()

            verify(exactly = 0) { ReminderScheduler.runNow(any()) }
        } finally {
            unmockkObject(ReminderScheduler)
        }
    }

    @Test
    fun `the factory runs the reminder check at once while reminders are on`() = runTest {
        mockkObject(ReminderScheduler)
        every { ReminderScheduler.runNow(any()) } just Runs
        try {
            val factory = factoryWith(notificationsEnabled = true)
            val vm = factory.create(AwayModeViewModel::class.java)

            vm.saveTrip(LocalDate.now(), LocalDate.now().plusDays(2), null).join()

            verify(exactly = 1) { ReminderScheduler.runNow(any()) }
        } finally {
            unmockkObject(ReminderScheduler)
        }
    }

    private fun factoryWith(notificationsEnabled: Boolean): AwayModeViewModel.Factory {
        val dataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(preferencesOf(SettingsKeys.NOTIFICATIONS_ENABLED to notificationsEnabled))
        }
        return AwayModeViewModel.Factory(mockk<Application>(relaxed = true), repository, dataStore)
    }
}
