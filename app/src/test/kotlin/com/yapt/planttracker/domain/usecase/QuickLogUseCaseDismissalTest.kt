package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.schedule.CareSchedule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * [QuickLogUseCase.recordWateringSuggestionDismissal] coverage (#674) — the single write path now
 * shared by the Plant Detail, Calendar, and Plant List dismiss-suggestion actions. Before this fix
 * only Plant Detail's own copy of this logic ever wrote the matching
 * [WateringAdjustmentTrigger.DIALOG_DISMISSAL] row; Calendar and Plant List raised confidence
 * silently.
 */
class QuickLogUseCaseDismissalTest {

    private val application: Application = mockk(relaxed = true)
    private val plantRepo: PlantRepository = mockk()
    private val careLogRepo: CareLogRepository = mockk()
    private val plantPhotoRepo: PlantPhotoRepository = mockk()
    private val database: PlantDatabase = mockk()
    private val wateringAdjustmentRepo: WateringAdjustmentRepository = mockk()

    @Before
    fun setUp() {
        coEvery { plantRepo.updatePlant(any()) } returns Unit
        coEvery { wateringAdjustmentRepo.addAdjustment(any()) } returns 1L
    }

    private fun plant(id: Long = 1L, name: String = "Monstera") = Plant(
        id = id,
        name = name,
        createdAt = 0L,
        updatedAt = 0L
    )

    private fun useCase(): QuickLogUseCase {
        val dataStore: DataStore<Preferences> = mockk {
            every { data } returns flowOf(emptyPreferences())
        }
        return QuickLogUseCase(
            application,
            plantRepo,
            careLogRepo,
            plantPhotoRepo,
            dataStore,
            database,
            wateringAdjustmentRepo
        )
    }

    @Test
    fun `recordWateringSuggestionDismissal raises confidence via confidenceAfterDismissal`() = runTest {
        val useCase = useCase()
        val monstera = plant().copy(wateringIntervalDays = 7, wateringConfidence = 1)
        val expectedConfidence = CareSchedule.confidenceAfterDismissal(1)

        useCase.recordWateringSuggestionDismissal(monstera)

        coVerify { plantRepo.updatePlant(match { it.wateringConfidence == expectedConfidence }) }
    }

    @Test
    fun `recordWateringSuggestionDismissal writes a DIALOG_DISMISSAL row when an interval is configured`() = runTest {
        val useCase = useCase()
        val monstera = plant().copy(wateringIntervalDays = 7, wateringConfidence = 1)

        useCase.recordWateringSuggestionDismissal(monstera)

        coVerify {
            wateringAdjustmentRepo.addAdjustment(
                match {
                    it.trigger == WateringAdjustmentTrigger.DIALOG_DISMISSAL &&
                        it.beforeIntervalDays == 7 &&
                        it.afterIntervalDays == 7
                }
            )
        }
    }

    @Test
    fun `recordWateringSuggestionDismissal logs the row's before-after in base-space, not the stale literal`() =
        runTest {
            // #584 review precedent (carried over from the pre-#674 PlantDetailViewModel-only copy of
            // this logic): the row must use the true base (6, from wateringBaseIntervalDays) rather than
            // the stale literal wateringIntervalDays (10).
            val useCase = useCase()
            val monstera = plant().copy(wateringIntervalDays = 10, wateringBaseIntervalDays = 6.0)

            useCase.recordWateringSuggestionDismissal(monstera)

            coVerify {
                wateringAdjustmentRepo.addAdjustment(
                    match { it.beforeIntervalDays == 6 && it.afterIntervalDays == 6 }
                )
            }
        }

    @Test
    fun `recordWateringSuggestionDismissal writes no adjustment row when wateringIntervalDays is null`() = runTest {
        val useCase = useCase()
        val monstera = plant().copy(wateringIntervalDays = null, wateringConfidence = 1)

        useCase.recordWateringSuggestionDismissal(monstera)

        coVerify(exactly = 0) { wateringAdjustmentRepo.addAdjustment(any()) }
    }

    @Test
    fun `recordWateringSuggestionDismissal returns the updated plant`() = runTest {
        val useCase = useCase()
        val monstera = plant().copy(wateringIntervalDays = 7, wateringConfidence = 1)
        val expectedConfidence = CareSchedule.confidenceAfterDismissal(1)

        val result = useCase.recordWateringSuggestionDismissal(monstera)

        assertEquals(expectedConfidence, result.wateringConfidence)
    }
}
