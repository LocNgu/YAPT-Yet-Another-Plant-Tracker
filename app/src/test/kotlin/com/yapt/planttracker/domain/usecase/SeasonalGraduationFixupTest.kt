package com.yapt.planttracker.domain.usecase

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Pure-JVM tests for [SeasonalGraduationFixup] (#702, hardened #703) — the one-time backfill
 * correcting [Plant.wateringBaseIntervalDays] left stale by `SEASONAL_WATERING` graduating (#656) out
 * of developer mode. See technical/product ADR references in the class KDoc.
 */
class SeasonalGraduationFixupTest {

    // Mid-year: with amplitude 0.5 (northern), season() = 1 - 0.5 = 0.5 (peak day is Jan 5).
    private val midYear = LocalDate.of(2024, 7, 5)
    private val amplitude = 0.5
    private val hemisphere = Hemisphere.NORTHERN
    private val legacyFlagKey = booleanPreferencesKey("feature_flag_seasonal_watering")

    private fun plant(
        id: Long = 1L,
        wateringIntervalDays: Int?,
        wateringBaseIntervalDays: Double? = null,
        pinIntervalToBase: Boolean = false
    ) = Plant(
        id = id,
        name = "Monstera",
        wateringIntervalDays = wateringIntervalDays,
        wateringBaseIntervalDays = wateringBaseIntervalDays,
        pinIntervalToBase = pinIntervalToBase,
        createdAt = 0L,
        updatedAt = 0L
    )

    /** [run]/[computeUpdate] re-fetch every plant by id before acting on it (#703) - every test stubs this. */
    private fun PlantRepository.stubFreshFetch(vararg plants: Plant) {
        for (plant in plants) {
            every { getPlantById(plant.id) } returns flowOf(plant)
        }
    }

    private fun tempDataStore() = run {
        val dataStoreFile = File.createTempFile("seasonal_graduation_fixup_test_", ".preferences_pb")
        dataStoreFile.deleteOnExit()
        PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.Unconfined),
            produceFile = { dataStoreFile }
        )
    }

    @Test
    fun `a plant with a stale base gets recomputed and logs an adjustment`() = runTest {
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)
        coEvery { plantRepository.updateWateringBaseInterval(any(), any(), any()) } returns Unit

        // Literal interval moved to 14 while the base stayed frozen at 7 (the #702 bug).
        val stalePlant = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0)
        plantRepository.stubFreshFetch(stalePlant)
        val request = SeasonalGraduationFixup.FixupRequest(
            plants = listOf(stalePlant),
            amplitude = amplitude,
            hemisphere = hemisphere,
            today = midYear
        )

        val changedCount = SeasonalGraduationFixup.run(
            request,
            plantRepository,
            wateringAdjustmentRepository,
            now = 5_000L
        )

        assertEquals(1, changedCount)
        val expectedBase = SeasonalWatering.deseasonalize(14.0, midYear, amplitude, hemisphere)

        val idSlot = slot<Long>()
        val baseSlot = slot<Double>()
        val updatedAtSlot = slot<Long>()
        coVerify(exactly = 1) {
            plantRepository.updateWateringBaseInterval(capture(idSlot), capture(baseSlot), capture(updatedAtSlot))
        }
        assertEquals(stalePlant.id, idSlot.captured)
        assertEquals(expectedBase, baseSlot.captured, 0.0001)
        assertEquals(5_000L, updatedAtSlot.captured)
        // The column-specific update is used instead of a full-row write (#703 review round 3).
        coVerify(exactly = 0) { plantRepository.updatePlant(any()) }

        coVerify(exactly = 1) {
            wateringAdjustmentRepository.addAdjustment(
                match {
                    it.trigger == WateringAdjustmentTrigger.SEASONAL_GRADUATION_FIXUP &&
                        it.beforeIntervalDays == 7 &&
                        it.afterIntervalDays == expectedBase.roundToInt()
                }
            )
        }
    }

    @Test
    fun `a pinned plant is untouched`() = runTest {
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)

        val pinnedPlant = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0, pinIntervalToBase = true)
        plantRepository.stubFreshFetch(pinnedPlant)
        val request = SeasonalGraduationFixup.FixupRequest(
            plants = listOf(pinnedPlant),
            amplitude = amplitude,
            hemisphere = hemisphere,
            today = midYear
        )

        val changedCount = SeasonalGraduationFixup.run(request, plantRepository, wateringAdjustmentRepository)

        assertEquals(0, changedCount)
        coVerify(exactly = 0) { plantRepository.updateWateringBaseInterval(any(), any(), any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepository.addAdjustment(any()) }
    }

    @Test
    fun `an amplitude-Off plant is untouched with no adjustment row`() = runTest {
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)

        val stalePlant = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0)
        val request = SeasonalGraduationFixup.FixupRequest(
            plants = listOf(stalePlant),
            amplitude = 0.0,
            hemisphere = hemisphere,
            today = midYear
        )

        val changedCount = SeasonalGraduationFixup.run(request, plantRepository, wateringAdjustmentRepository)

        assertEquals(0, changedCount)
        // amplitude == 0.0 short-circuits before ever touching the repository, so no fresh-fetch stub needed.
        coVerify(exactly = 0) { plantRepository.getPlantById(any()) }
        coVerify(exactly = 0) { plantRepository.updateWateringBaseInterval(any(), any(), any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepository.addAdjustment(any()) }
    }

    @Test
    fun `a plant with wateringIntervalDays null is untouched`() = runTest {
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)

        val neverConfiguredPlant = plant(wateringIntervalDays = null, wateringBaseIntervalDays = null)
        plantRepository.stubFreshFetch(neverConfiguredPlant)
        val request = SeasonalGraduationFixup.FixupRequest(
            plants = listOf(neverConfiguredPlant),
            amplitude = amplitude,
            hemisphere = hemisphere,
            today = midYear
        )

        val changedCount = SeasonalGraduationFixup.run(request, plantRepository, wateringAdjustmentRepository)

        assertEquals(0, changedCount)
        coVerify(exactly = 0) { plantRepository.updateWateringBaseInterval(any(), any(), any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepository.addAdjustment(any()) }
    }

    @Test
    fun `an already-correct base is a no-op`() = runTest {
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)

        // The base already matches what re-anchoring to today would produce - nothing to fix.
        val expectedBase = SeasonalWatering.deseasonalize(14.0, midYear, amplitude, hemisphere)
        val alreadyCorrectPlant = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = expectedBase)
        plantRepository.stubFreshFetch(alreadyCorrectPlant)
        val request = SeasonalGraduationFixup.FixupRequest(
            plants = listOf(alreadyCorrectPlant),
            amplitude = amplitude,
            hemisphere = hemisphere,
            today = midYear
        )

        val changedCount = SeasonalGraduationFixup.run(request, plantRepository, wateringAdjustmentRepository)

        assertEquals(0, changedCount)
        coVerify(exactly = 0) { plantRepository.updateWateringBaseInterval(any(), any(), any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepository.addAdjustment(any()) }
    }

    // #703 review: comparing rounded ints could mask a base that rounds the same as the recomputed
    // value but is a genuinely different raw Double - which can diverge meaningfully once multiplied
    // by the seasonal curve. This plant's stored base and the freshly-recomputed base round to the
    // same day count but are not equal, so the fixup must still correct it.
    @Test
    fun `a base that rounds the same as the recompute but differs in raw value is still corrected`() = runTest {
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)
        coEvery { plantRepository.updateWateringBaseInterval(any(), any(), any()) } returns Unit

        val literalInterval = 14
        val expectedNewBase = SeasonalWatering.deseasonalize(
            literalInterval.toDouble(),
            midYear,
            amplitude,
            hemisphere
        )
        val nudgedBeforeBase = expectedNewBase + 0.3
        // Sanity-check the fixture: same rounded day count, genuinely different raw values.
        assertEquals(expectedNewBase.roundToInt(), nudgedBeforeBase.roundToInt())

        val plantWithNudgedBase = plant(
            wateringIntervalDays = literalInterval,
            wateringBaseIntervalDays = nudgedBeforeBase
        )
        plantRepository.stubFreshFetch(plantWithNudgedBase)
        val request = SeasonalGraduationFixup.FixupRequest(
            plants = listOf(plantWithNudgedBase),
            amplitude = amplitude,
            hemisphere = hemisphere,
            today = midYear
        )

        val changedCount = SeasonalGraduationFixup.run(request, plantRepository, wateringAdjustmentRepository)

        assertEquals(1, changedCount)
        val baseSlot = slot<Double>()
        coVerify(exactly = 1) { plantRepository.updateWateringBaseInterval(any(), capture(baseSlot), any()) }
        assertEquals(expectedNewBase, baseSlot.captured, 0.0001)
    }

    @Test
    fun `a plant deleted since the snapshot was taken is skipped`() = runTest {
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)

        val deletedPlant = plant(id = 42L, wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0)
        every { plantRepository.getPlantById(42L) } returns flowOf(null)
        val request = SeasonalGraduationFixup.FixupRequest(
            plants = listOf(deletedPlant),
            amplitude = amplitude,
            hemisphere = hemisphere,
            today = midYear
        )

        val changedCount = SeasonalGraduationFixup.run(request, plantRepository, wateringAdjustmentRepository)

        assertEquals(0, changedCount)
        coVerify(exactly = 0) { plantRepository.updateWateringBaseInterval(any(), any(), any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepository.addAdjustment(any()) }
    }

    // #703 review round 3: eligibility must reflect a plant's *current* state, not the possibly-stale
    // snapshot the caller passed in - this plant looks eligible in the snapshot but a concurrent edit
    // (visible only via the fresh fetch) has since pinned it.
    @Test
    fun `eligibility is evaluated against the freshly-fetched plant, not the stale snapshot`() = runTest {
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)

        val staleSnapshot = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0)
        val nowPinnedPlant = staleSnapshot.copy(pinIntervalToBase = true)
        every { plantRepository.getPlantById(staleSnapshot.id) } returns flowOf(nowPinnedPlant)
        val request = SeasonalGraduationFixup.FixupRequest(
            plants = listOf(staleSnapshot),
            amplitude = amplitude,
            hemisphere = hemisphere,
            today = midYear
        )

        val changedCount = SeasonalGraduationFixup.run(request, plantRepository, wateringAdjustmentRepository)

        assertEquals(0, changedCount)
        coVerify(exactly = 0) { plantRepository.updateWateringBaseInterval(any(), any(), any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepository.addAdjustment(any()) }
    }

    // #703 review round 3: the write must be the column-specific update, never a full-row write that
    // could race and revert a concurrent edit to some other column - this asserts updatePlant() is
    // never called by this code path at all, regardless of what other tests already show indirectly.
    @Test
    fun `run never issues a full-row updatePlant write`() = runTest {
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)
        coEvery { plantRepository.updateWateringBaseInterval(any(), any(), any()) } returns Unit

        val stalePlant = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0)
        plantRepository.stubFreshFetch(stalePlant)
        val request = SeasonalGraduationFixup.FixupRequest(
            plants = listOf(stalePlant),
            amplitude = amplitude,
            hemisphere = hemisphere,
            today = midYear
        )

        SeasonalGraduationFixup.run(request, plantRepository, wateringAdjustmentRepository)

        coVerify(exactly = 0) { plantRepository.updatePlant(any()) }
        coVerify(exactly = 1) { plantRepository.updateWateringBaseInterval(any(), any(), any()) }
    }

    @Test
    fun `maybeRun's flag prevents a second run from re-touching an already-fixed-up plant`() = runTest {
        val dataStore = tempDataStore()
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)
        coEvery { plantRepository.updateWateringBaseInterval(any(), any(), any()) } returns Unit

        // The same stale plant instance is (deliberately) reused across both calls: if the flag didn't
        // gate the second call, run() would recompute against a different "today" and register another
        // change, since the object passed in never reflects the first call's write.
        val stalePlant = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0)
        plantRepository.stubFreshFetch(stalePlant)

        val firstRunChanged = SeasonalGraduationFixup.maybeRun(
            SeasonalGraduationFixup.FixupRequest(
                plants = listOf(stalePlant),
                amplitude = amplitude,
                hemisphere = hemisphere,
                today = midYear
            ),
            plantRepository,
            wateringAdjustmentRepository,
            dataStore
        )
        assertEquals(1, firstRunChanged)
        coVerify(exactly = 1) { plantRepository.updateWateringBaseInterval(any(), any(), any()) }

        val secondRunChanged = SeasonalGraduationFixup.maybeRun(
            SeasonalGraduationFixup.FixupRequest(
                plants = listOf(stalePlant),
                amplitude = amplitude,
                hemisphere = hemisphere,
                today = midYear.plusDays(1)
            ),
            plantRepository,
            wateringAdjustmentRepository,
            dataStore
        )
        assertEquals(0, secondRunChanged)
        // Still exactly once, total - the second call never re-touched the plant.
        coVerify(exactly = 1) { plantRepository.updateWateringBaseInterval(any(), any(), any()) }
        coVerify(exactly = 1) { wateringAdjustmentRepository.addAdjustment(any()) }
    }

    // --- #703 review: done-flag gating on empty plants / amplitude Off ---

    @Test
    fun `maybeRun does not mark done on a fresh install with an empty plant list`() = runTest {
        val dataStore = tempDataStore()
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)

        val changedCount = SeasonalGraduationFixup.maybeRun(
            SeasonalGraduationFixup.FixupRequest(
                plants = emptyList(),
                amplitude = amplitude,
                hemisphere = hemisphere,
                today = midYear
            ),
            plantRepository,
            wateringAdjustmentRepository,
            dataStore
        )

        assertEquals(0, changedCount)
        val doneFlag = dataStore.data.first()[SettingsKeys.SEASONAL_BASE_GRADUATION_FIXUP_DONE]
        assertEquals(null, doneFlag)
    }

    @Test
    fun `maybeRun does not mark done while amplitude is Off`() = runTest {
        val dataStore = tempDataStore()
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)

        val stalePlant = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0)

        val changedCount = SeasonalGraduationFixup.maybeRun(
            SeasonalGraduationFixup.FixupRequest(
                plants = listOf(stalePlant),
                amplitude = 0.0,
                hemisphere = hemisphere,
                today = midYear
            ),
            plantRepository,
            wateringAdjustmentRepository,
            dataStore
        )

        assertEquals(0, changedCount)
        val doneFlag = dataStore.data.first()[SettingsKeys.SEASONAL_BASE_GRADUATION_FIXUP_DONE]
        assertEquals(null, doneFlag)
    }

    @Test
    fun `maybeRun does not mark done when both plants are empty and amplitude is Off`() = runTest {
        val dataStore = tempDataStore()
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)

        val changedCount = SeasonalGraduationFixup.maybeRun(
            SeasonalGraduationFixup.FixupRequest(
                plants = emptyList(),
                amplitude = 0.0,
                hemisphere = hemisphere,
                today = midYear
            ),
            plantRepository,
            wateringAdjustmentRepository,
            dataStore
        )

        assertEquals(0, changedCount)
        val doneFlag = dataStore.data.first()[SettingsKeys.SEASONAL_BASE_GRADUATION_FIXUP_DONE]
        assertEquals(null, doneFlag)
    }

    @Test
    fun `maybeRun marks done on a normal non-empty, amplitude-on run`() = runTest {
        val dataStore = tempDataStore()
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)
        coEvery { plantRepository.updateWateringBaseInterval(any(), any(), any()) } returns Unit

        val stalePlant = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0)
        plantRepository.stubFreshFetch(stalePlant)

        SeasonalGraduationFixup.maybeRun(
            SeasonalGraduationFixup.FixupRequest(
                plants = listOf(stalePlant),
                amplitude = amplitude,
                hemisphere = hemisphere,
                today = midYear
            ),
            plantRepository,
            wateringAdjustmentRepository,
            dataStore
        )

        val doneFlag = dataStore.data.first()[SettingsKeys.SEASONAL_BASE_GRADUATION_FIXUP_DONE]
        assertEquals(true, doneFlag)
    }

    // --- #703 review: legacy dev-mode flag skip ---

    @Test
    fun `maybeRun skips every plant but still marks done when the legacy flag was ever on`() = runTest {
        val dataStore = tempDataStore()
        dataStore.edit { it[legacyFlagKey] = true }
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)

        val stalePlant = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0)

        val changedCount = SeasonalGraduationFixup.maybeRun(
            SeasonalGraduationFixup.FixupRequest(
                plants = listOf(stalePlant),
                amplitude = amplitude,
                hemisphere = hemisphere,
                today = midYear
            ),
            plantRepository,
            wateringAdjustmentRepository,
            dataStore
        )

        assertEquals(0, changedCount)
        coVerify(exactly = 0) { plantRepository.getPlantById(any()) }
        coVerify(exactly = 0) { plantRepository.updateWateringBaseInterval(any(), any(), any()) }
        coVerify(exactly = 0) { wateringAdjustmentRepository.addAdjustment(any()) }
        val doneFlag = dataStore.data.first()[SettingsKeys.SEASONAL_BASE_GRADUATION_FIXUP_DONE]
        assertEquals(true, doneFlag)
    }

    @Test
    fun `maybeRun proceeds normally when the legacy flag was never set`() = runTest {
        val dataStore = tempDataStore()
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)
        coEvery { plantRepository.updateWateringBaseInterval(any(), any(), any()) } returns Unit

        val stalePlant = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0)
        plantRepository.stubFreshFetch(stalePlant)

        val changedCount = SeasonalGraduationFixup.maybeRun(
            SeasonalGraduationFixup.FixupRequest(
                plants = listOf(stalePlant),
                amplitude = amplitude,
                hemisphere = hemisphere,
                today = midYear
            ),
            plantRepository,
            wateringAdjustmentRepository,
            dataStore
        )

        assertEquals(1, changedCount)
        coVerify(exactly = 1) { plantRepository.updateWateringBaseInterval(any(), any(), any()) }
    }

    @Test
    fun `maybeRun proceeds normally when the legacy flag was explicitly set to false`() = runTest {
        val dataStore = tempDataStore()
        dataStore.edit { it[legacyFlagKey] = false }
        val plantRepository: PlantRepository = mockk(relaxed = true)
        val wateringAdjustmentRepository: WateringAdjustmentRepository = mockk(relaxed = true)
        coEvery { plantRepository.updateWateringBaseInterval(any(), any(), any()) } returns Unit

        val stalePlant = plant(wateringIntervalDays = 14, wateringBaseIntervalDays = 7.0)
        plantRepository.stubFreshFetch(stalePlant)

        val changedCount = SeasonalGraduationFixup.maybeRun(
            SeasonalGraduationFixup.FixupRequest(
                plants = listOf(stalePlant),
                amplitude = amplitude,
                hemisphere = hemisphere,
                today = midYear
            ),
            plantRepository,
            wateringAdjustmentRepository,
            dataStore
        )

        assertEquals(1, changedCount)
        coVerify(exactly = 1) { plantRepository.updateWateringBaseInterval(any(), any(), any()) }
    }
}
