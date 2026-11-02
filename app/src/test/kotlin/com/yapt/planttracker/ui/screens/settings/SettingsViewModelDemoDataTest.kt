package com.yapt.planttracker.ui.screens.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.yapt.planttracker.data.backup.BackupManagerInterface
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.devmode.DemoData
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.util.MainDispatcherRule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * VM-level coverage for the Developer section's "Seed demo plants" / "Remove demo plants" actions
 * (#533, follow-up to #523): `seedDemoPlants()` / `removeDemoPlants()` write through to the database
 * and emit the affected-count message on [SettingsViewModel.debugActionEvent].
 *
 * **Real database, no DI seam (Option A).** `SettingsViewModel` builds its `DemoDataSeeder` internally
 * (`by lazy`) to stay under Detekt's `LongParameterList` constructor threshold, and the seeder wraps
 * its writes in `database.withTransaction`, which needs a real `RoomDatabase`. So instead of adding a
 * constructor parameter purely for mocking, this test hands the VM a real in-memory [PlantDatabase] —
 * the same approach as `DemoDataSeederTest` and `PlantDetailViewModelPlantIssueTest`. Synchronous
 * query/transaction executors keep Room's `withTransaction` hop on the calling thread so the
 * fire-and-forget `viewModelScope.launch` completes inside the test (see that test's KDoc). The
 * generator and seeder semantics themselves are covered by `DemoDataTest` / `DemoDataSeederTest`;
 * this file only pins the VM → seeder → message plumbing.
 *
 * The context is the real (Robolectric) application so the plural resources resolve to their
 * English text, which is what the Settings snackbar shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SettingsViewModelDemoDataTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val dataStore: DataStore<Preferences> = mockk {
        every { data } returns flowOf(emptyPreferences())
    }
    private val backupManager: BackupManagerInterface = mockk()

    private lateinit var context: Context
    private lateinit var database: PlantDatabase
    private lateinit var plantRepository: PlantRepository
    private lateinit var vm: SettingsViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val synchronousExecutor = Executor { it.run() }
        database = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(synchronousExecutor)
            .setTransactionExecutor(synchronousExecutor)
            .build()
        plantRepository = PlantRepository(database.plantDao())
        vm = SettingsViewModel(
            dataStore = dataStore,
            context = context,
            database = database,
            plantRepository = plantRepository,
            featureFlags = FeatureFlags(dataStore, flags = emptyList()),
            backupManager = backupManager
        )
    }

    @After
    fun tearDown() = database.close()

    private suspend fun allPlantNames(): List<String> =
        database.plantDao().getAllPlantsIncludingArchived().first().map { it.name }

    @Test
    fun `seedDemoPlants writes the demo set and emits the seeded count`() = runTest {
        vm.debugActionEvent.test {
            vm.seedDemoPlants()
            assertEquals("10 demo plants seeded", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        val names = allPlantNames()
        assertEquals(10, names.size)
        assertTrue(names.all { it.startsWith(DemoData.NAME_PREFIX) })
    }

    @Test
    fun `removeDemoPlants deletes only demo plants and emits the removed count`() = runTest {
        plantRepository.addPlant(Plant(name = "My Real Fern", createdAt = 0L, updatedAt = 0L))
        vm.debugActionEvent.test {
            vm.seedDemoPlants()
            awaitItem()

            vm.removeDemoPlants()
            assertEquals("10 demo plants removed", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf("My Real Fern"), allPlantNames())
    }

    @Test
    fun `removeDemoPlants emits the nothing-to-remove message when no demo plants exist`() = runTest {
        plantRepository.addPlant(Plant(name = "My Real Fern", createdAt = 0L, updatedAt = 0L))

        vm.debugActionEvent.test {
            vm.removeDemoPlants()
            assertEquals("No demo plants to remove", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(listOf("My Real Fern"), allPlantNames())
    }

    @Test
    fun `removeDemoPlants uses the singular message for a single demo plant`() = runTest {
        plantRepository.addPlant(Plant(name = "${DemoData.NAME_PREFIX}Lone Pothos", createdAt = 0L, updatedAt = 0L))

        vm.debugActionEvent.test {
            vm.removeDemoPlants()
            assertEquals("1 demo plant removed", awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        assertEquals(emptyList<String>(), allPlantNames())
    }
}
