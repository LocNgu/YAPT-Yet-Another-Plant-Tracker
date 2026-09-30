package com.yapt.planttracker.ui.screens.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.yapt.planttracker.data.backup.BackupManagerInterface
import com.yapt.planttracker.data.db.CareLogDao
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.db.PlantLastCare
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.repotting.RepottingOverviewBuilder
import com.yapt.planttracker.domain.repotting.RepottingOverviewThreshold
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.util.MainDispatcherRule
import com.yapt.planttracker.util.toStartOfDayMillis
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/**
 * Settings' Repotting overview subtitle (#525, product ADR-0059): its count comes from the same
 * `observeRepottingOverview()` pipeline as the page, so it must match `RepottingOverviewBuilder` for the
 * saved threshold.
 */
class SettingsViewModelRepottingOverviewTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val mockPrefs: Preferences = mockk()
    private val mockDataStore: DataStore<Preferences> = mockk()
    private val mockContext: Context = mockk(relaxed = true)
    private val mockDatabase: PlantDatabase = mockk(relaxed = true)
    private val mockPlantRepository: PlantRepository = mockk(relaxed = true)
    private val mockBackupManager: BackupManagerInterface = mockk()
    private val today = LocalDate.now()

    private fun stubRepottingOverview(
        plants: List<Plant>,
        lastRepotAtByPlantId: Map<Long, Long> = emptyMap(),
        storedThreshold: RepottingOverviewThreshold? = null
    ) {
        every { mockDataStore.data } returns flowOf(mockPrefs)
        every { mockPrefs[SettingsKeys.REPOTTING_OVERVIEW_THRESHOLD] } returns storedThreshold?.name
        every { mockPlantRepository.getAllPlants() } returns flowOf(plants)
        val careLogDao = mockk<CareLogDao>(relaxed = true)
        every { careLogDao.observeLastCareOfType(CareType.REPOT.name) } returns
            flowOf(lastRepotAtByPlantId.map { PlantLastCare(it.key, it.value) })
        every { mockDatabase.careLogDao() } returns careLogDao
    }

    private fun buildVm() = SettingsViewModel(
        dataStore = mockDataStore,
        context = mockContext,
        database = mockDatabase,
        plantRepository = mockPlantRepository,
        featureFlags = FeatureFlags(mockDataStore, flags = emptyList()),
        backupManager = mockBackupManager
    )

    private fun plant(id: Long, createdAt: LocalDate, planStart: LocalDate? = null) = Plant(
        id = id,
        name = "Plant $id",
        createdAt = createdAt.toStartOfDayMillis(),
        repotPlanSeasonStartAt = planStart?.toStartOfDayMillis(),
        repotPlanMadeAt = planStart?.let { LocalDate.of(2026, 1, 1).toStartOfDayMillis() }
    )

    // The StateFlow starts at (default chip, 0); waiting for an exact (chip, count) pair skips that seed value.
    private suspend fun SettingsViewModel.awaitSummary(threshold: RepottingOverviewThreshold, count: Int) =
        repottingOverviewSummary.first { it.threshold == threshold && it.count == count }

    @Test
    fun `defaults to two years and counts only the plants that match`() = runTest {
        stubRepottingOverview(
            plants = listOf(
                plant(1, LocalDate.of(2020, 1, 1)),
                plant(2, LocalDate.of(2020, 1, 1)),
                plant(3, today.minusMonths(1)),
                plant(4, LocalDate.of(2020, 1, 1), planStart = today.plusMonths(3))
            ),
            lastRepotAtByPlantId = mapOf(2L to today.minusMonths(2).toStartOfDayMillis())
        )

        // Plant 1 only: 2 was repotted recently, 3 was added last month, 4 is planned.
        assertEquals(1, buildVm().awaitSummary(RepottingOverviewThreshold.TWO_YEARS, count = 1).count)
    }

    @Test
    fun `follows the saved threshold and lists never repotted plants`() = runTest {
        stubRepottingOverview(
            plants = listOf(
                plant(1, today.minusDays(3)),
                plant(2, today.minusDays(9)),
                plant(3, LocalDate.of(2020, 1, 1))
            ),
            lastRepotAtByPlantId = mapOf(3L to today.minusDays(20).toStartOfDayMillis()),
            storedThreshold = RepottingOverviewThreshold.NEVER
        )

        assertEquals(2, buildVm().awaitSummary(RepottingOverviewThreshold.NEVER, count = 2).count)
    }

    @Test
    fun `count equals the page's list size for every threshold`() = runTest {
        val plants = listOf(
            plant(1, today.minusYears(5)),
            plant(2, today.minusYears(5)),
            plant(3, today.minusYears(5)),
            plant(4, today.minusMonths(6)),
            plant(5, today.minusYears(5), planStart = today.plusMonths(2))
        )
        val lastRepot = mapOf(
            2L to today.minusYears(2).minusMonths(6).toStartOfDayMillis(),
            3L to today.minusYears(1).minusMonths(6).toStartOfDayMillis()
        )
        val expected = RepottingOverviewThreshold.entries.associateWith {
            RepottingOverviewBuilder.build(plants, lastRepot, it, today, SeasonalWatering.currentHemisphere()).count
        }
        // The fixture really separates the chips, so the comparison below can't pass by accident.
        assertEquals(
            mapOf(
                RepottingOverviewThreshold.NEVER to 2,
                RepottingOverviewThreshold.ONE_YEAR to 3,
                RepottingOverviewThreshold.TWO_YEARS to 2,
                RepottingOverviewThreshold.THREE_YEARS to 1
            ),
            expected
        )

        for ((threshold, count) in expected) {
            stubRepottingOverview(plants, lastRepot, storedThreshold = threshold)

            assertEquals("threshold $threshold", count, buildVm().awaitSummary(threshold, count).count)
        }
    }
}
