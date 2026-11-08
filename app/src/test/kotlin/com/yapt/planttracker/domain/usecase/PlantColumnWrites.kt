package com.yapt.planttracker.domain.usecase

import com.yapt.planttracker.data.repository.PlantRepository
import io.mockk.coEvery
import io.mockk.coVerify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.IdentityHashMap

/**
 * Test helpers for the column-specific watering writes (#872). The quick-log and adaptive paths no longer
 * write a whole plant row, so a strict `mockk<PlantRepository>()` needs each statement stubbed, and a
 * "nothing was written" assertion has to cover all of them, not just `updatePlant`.
 *
 * Every stubbed column write is recorded as a map from the `Plant` property name to the value written,
 * holding only the columns that statement names — so an assertion on a column the write doesn't touch
 * fails, which is the point of #872.
 */
private val recordedWrites = IdentityHashMap<PlantRepository, MutableList<Map<String, Any?>>>()

internal fun plantColumnWrites(repo: PlantRepository): List<Map<String, Any?>> =
    recordedWrites[repo].orEmpty()

internal fun stubPlantColumnWrites(repo: PlantRepository) {
    val writes = mutableListOf<Map<String, Any?>>()
    recordedWrites[repo] = writes
    coEvery { repo.updateWateringConfidence(any(), any(), any()) } answers {
        writes += mapOf("id" to arg<Long>(0), "wateringConfidence" to arg<Int?>(1), "updatedAt" to arg<Long>(2))
    }
    coEvery { repo.updateWateringConfidenceAndBase(any(), any(), any(), any()) } answers {
        writes += mapOf(
            "id" to arg<Long>(0),
            "wateringConfidence" to arg<Int?>(1),
            "wateringBaseIntervalDays" to arg<Double?>(2),
            "updatedAt" to arg<Long>(3)
        )
    }
    coEvery { repo.updateWateringModel(any(), any(), any(), any(), any()) } answers {
        writes += mapOf(
            "id" to arg<Long>(0),
            "wateringIntervalDays" to arg<Int?>(1),
            "wateringBaseIntervalDays" to arg<Double?>(2),
            "wateringConfidence" to arg<Int?>(3),
            "updatedAt" to arg<Long>(4)
        )
    }
    coEvery { repo.applyWateringBootstrap(any(), any(), any(), any(), any()) } answers {
        writes += mapOf(
            "id" to arg<Long>(0),
            "wateringIntervalDays" to arg<Int>(1),
            "wateringBaseIntervalDays" to arg<Double>(2),
            "wateringConfidence" to arg<Int?>(3),
            "wateringResetAt" to null,
            "updatedAt" to arg<Long>(4)
        )
    }
    coEvery { repo.updateWateringDueDateOverride(any(), any(), any(), any()) } answers {
        writes += mapOf(
            "id" to arg<Long>(0),
            "wateringDueDateOverride" to arg<Long?>(1),
            "wateringDueDateOverrideMadeAt" to arg<Long?>(2),
            "updatedAt" to arg<Long>(3)
        )
    }
}

/** Asserts some recorded column write (exactly [exactly] of them, when given) satisfies [predicate]. */
internal fun assertPlantWrite(
    repo: PlantRepository,
    exactly: Int? = null,
    predicate: (Map<String, Any?>) -> Boolean
) {
    val matching = plantColumnWrites(repo).count(predicate)
    if (exactly != null) {
        assertEquals("matching plant column writes in ${plantColumnWrites(repo)}", exactly, matching)
    } else {
        assertTrue("no matching plant column write in ${plantColumnWrites(repo)}", matching > 0)
    }
}

internal fun verifyNoPlantRowWrite(repo: PlantRepository) {
    coVerify(exactly = 0) { repo.updatePlant(any()) }
    coVerify(exactly = 0) { repo.updateWateringConfidence(any(), any(), any()) }
    coVerify(exactly = 0) { repo.updateWateringConfidenceAndBase(any(), any(), any(), any()) }
    coVerify(exactly = 0) { repo.updateWateringModel(any(), any(), any(), any(), any()) }
    coVerify(exactly = 0) { repo.applyWateringBootstrap(any(), any(), any(), any(), any()) }
    coVerify(exactly = 0) { repo.updateWateringDueDateOverride(any(), any(), any(), any()) }
}
