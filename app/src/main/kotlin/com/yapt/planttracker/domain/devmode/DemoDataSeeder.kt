package com.yapt.planttracker.domain.devmode

import androidx.room.withTransaction
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantRepository

/**
 * Impure orchestration around the pure [DemoData] generator: writes the demo dataset to the real
 * database and hard-deletes it again, both scoped strictly to the `[Demo] ` name prefix (#523).
 * Mirrors `domain/usecase/QuickLogUseCase`'s shape — a plain class with suspend functions, no
 * UI-facing state of its own, wrapping its multi-row writes in a single Room transaction so a
 * killed process can't leave a partial demo set behind.
 */
class DemoDataSeeder(
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val database: PlantDatabase
) {

    /**
     * Replaces the demo set with a freshly generated one anchored at [now] and returns the number
     * of plants inserted. Idempotent: any existing `[Demo] `-prefixed plants are removed first, so
     * repeated calls never stack duplicates (AC23). Never touches a plant without the prefix
     * (AC24) — the removal query is scoped by [DemoData.NAME_PREFIX].
     */
    suspend fun seed(now: Long = System.currentTimeMillis()): Int = database.withTransaction {
        removeExistingDemoPlants()
        val dataset = DemoData.generate(now)
        for (seed in dataset.plants) {
            val plantId = plantRepository.addPlant(seed.plant)
            for (log in seed.careLogs) {
                careLogRepository.addLog(log.copy(plantId = plantId))
            }
        }
        dataset.plants.size
    }

    /**
     * Hard-deletes every `[Demo] `-prefixed plant regardless of `archivedAt`, cascading its care
     * logs and photos, and returns the number removed. A no-op (returns 0) when none exist.
     */
    suspend fun remove(): Int = database.withTransaction { removeExistingDemoPlants() }

    private suspend fun removeExistingDemoPlants(): Int =
        plantRepository.deletePlantsWithNamePrefix(DemoData.NAME_PREFIX)
}
