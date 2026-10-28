package com.yapt.planttracker.data.repository

import com.yapt.planttracker.data.db.PlantDao
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.SeasonalFertilizing
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * [onPhotoReferencesRemoved] fires from [deletePlant] and [deleteAllArchived] (both cascade to care
 * logs/photos) and [deletePlantsWithNamePrefix] (demo-data cleanup, when it removes anything) — see
 * [com.yapt.planttracker.YaptApplication.scheduleOrphanPhotoCleanup] (#736/#559). Deliberately **not**
 * hooked on [updatePlant] — it's a hot path (every field edit, every interval tap), and cover-photo
 * replacement there is already covered by the periodic sweep. Defaulted to a no-op so every
 * pre-existing direct construction (tests, `SettingsViewModel`) still compiles unchanged.
 */
class PlantRepository(
    private val plantDao: PlantDao,
    private val onPhotoReferencesRemoved: () -> Unit = {}
) {

    fun getAllPlants(): Flow<List<Plant>> =
        plantDao.getAllPlants().map { list -> list.map { it.toDomain() } }

    fun getPlantById(id: Long): Flow<Plant?> =
        plantDao.getPlantById(id).map { it?.toDomain() }

    fun getAllRooms(): Flow<List<String>> = plantDao.getAllRooms()

    suspend fun addPlant(plant: Plant): Long =
        plantDao.insertPlant(plant.toEntity())

    suspend fun updatePlant(plant: Plant) =
        plantDao.updatePlant(plant.toEntity())

    suspend fun deletePlant(plant: Plant) {
        plantDao.deletePlant(plant.toEntity())
        onPhotoReferencesRemoved()
    }

    fun getArchivedPlants(): Flow<List<Plant>> =
        plantDao.getArchivedPlants().map { list -> list.map { it.toDomain() } }

    fun getArchivedCount(): Flow<Int> = plantDao.getArchivedCount()

    fun getHiddenPlants(): Flow<List<Plant>> =
        plantDao.getHiddenPlants().map { list -> list.map { it.toDomain() } }

    fun getHiddenCount(): Flow<Int> = plantDao.getHiddenCount()

    fun getAllNonArchivedPlants(): Flow<List<Plant>> =
        plantDao.getAllNonArchivedPlants().map { list -> list.map { it.toDomain() } }

    /** Column-specific hide (#915) — see [PlantDao.updateHiddenAt]'s doc for why this exists. */
    suspend fun hidePlant(id: Long, timestamp: Long = System.currentTimeMillis()) =
        plantDao.updateHiddenAt(id, timestamp)

    suspend fun unhidePlant(id: Long) = plantDao.updateHiddenAt(id, null)

    suspend fun archivePlant(id: Long, timestamp: Long = System.currentTimeMillis()) =
        plantDao.archivePlant(id, timestamp)

    suspend fun restorePlant(id: Long) = plantDao.restorePlant(id)

    /** Column-specific update — see [PlantDao.updateWateringBaseInterval]'s doc for why this exists. */
    suspend fun updateWateringBaseInterval(id: Long, wateringBaseIntervalDays: Double, updatedAt: Long) =
        plantDao.updateWateringBaseInterval(id, wateringBaseIntervalDays, updatedAt)

    /** Column-specific update — see [PlantDao.updateWateringDueDateOverride]'s doc for why this exists. */
    suspend fun updateWateringDueDateOverride(id: Long, wateringDueDateOverride: Long?, updatedAt: Long) =
        plantDao.updateWateringDueDateOverride(id, wateringDueDateOverride, updatedAt)

    /** Column-specific update — see [PlantDao.updateCoverPhotoUri]'s doc for why this exists. */
    suspend fun updateCoverPhotoUri(id: Long, coverPhotoUri: String?, updatedAt: Long) =
        plantDao.updateCoverPhotoUri(id, coverPhotoUri, updatedAt)

    /**
     * Sets the one-off planned repot (#809, product ADR-0057): [seasonStartAt] is the target season's
     * first day at start of day, [madeAt] is when the plan was made. Column-specific — see
     * [PlantDao.updateRepotPlan]'s doc for why this never round-trips through a full-row `updatePlant()`.
     */
    suspend fun setRepotPlan(id: Long, seasonStartAt: Long, madeAt: Long, updatedAt: Long) =
        plantDao.updateRepotPlan(id, seasonStartAt, madeAt, updatedAt)

    /** Clears the one-off planned repot (#809); the recurring interval date, if any, takes over again. */
    suspend fun clearRepotPlan(id: Long, updatedAt: Long) =
        plantDao.updateRepotPlan(id, null, null, updatedAt)

    /** Column-specific update — see [PlantDao.updateWateringConfidence]'s doc for why this exists. */
    suspend fun updateWateringConfidence(id: Long, confidence: Int?, updatedAt: Long) =
        plantDao.updateWateringConfidence(id, confidence, updatedAt)

    /** Direct children of [parentId] — archived and hidden ones included — by name (#960). */
    fun getChildren(parentId: Long): Flow<List<Plant>> =
        plantDao.getChildren(parentId).map { list -> list.map { it.toDomain() } }

    /** Every plant, archived and hidden included — the family picker's candidate source (#960). */
    fun getAllPlantsIncludingArchived(): Flow<List<Plant>> =
        plantDao.getAllPlantsIncludingArchived().map { list -> list.map { it.toDomain() } }

    suspend fun getDescendantIds(id: Long): Set<Long> = plantDao.getDescendantIds(id).toSet()

    suspend fun getAncestorIds(id: Long): Set<Long> = plantDao.getAncestorIds(id).toSet()

    /**
     * Links [childId] under [parentId] (#960, product ADR-0072). Returns `false` — and changes nothing —
     * when that would make a plant its own parent, point at a missing plant, or create a cycle; the check
     * runs inside the same statement as the write ([PlantDao.linkToParent]), so the picker's exclusions
     * are a convenience, not the guarantee.
     */
    suspend fun linkParent(childId: Long, parentId: Long): Boolean =
        plantDao.linkToParent(childId, parentId) > 0

    /** Clears [childId]'s parent link (#960); the former parent and the child both stay. */
    suspend fun unlinkParent(childId: Long) = plantDao.setPropagatedFrom(childId, null)

    /** Archives every id in a single atomic statement (bulk graveyard action, #448). */
    suspend fun archivePlants(ids: List<Long>, timestamp: Long = System.currentTimeMillis()) =
        plantDao.archivePlants(ids, timestamp)

    /** Restores every id in a single atomic statement (bulk-archive undo, #448). */
    suspend fun restorePlants(ids: List<Long>) = plantDao.restorePlants(ids)

    suspend fun deleteAllArchived() {
        plantDao.deleteAllArchived()
        onPhotoReferencesRemoved()
    }

    /** Hard-deletes every plant whose name starts with [prefix]; returns the count removed (#523). */
    suspend fun deletePlantsWithNamePrefix(prefix: String): Int =
        plantDao.deletePlantsByNamePrefix(prefix).also { if (it > 0) onPhotoReferencesRemoved() }
}

private fun PlantEntity.toDomain() = Plant(
    id = id,
    name = name,
    species = species,
    room = room,
    coverPhotoUri = coverPhotoUri,
    notes = notes,
    wateringIntervalDays = wateringIntervalDays,
    fertilizingIntervalDays = fertilizingIntervalDays,
    repottingIntervalDays = repottingIntervalDays,
    createdAt = createdAt,
    updatedAt = updatedAt,
    wateringDueDateOverride = wateringDueDateOverride,
    useLiquidFertilizer = useLiquidFertilizer,
    archivedAt = archivedAt,
    wateringConfidence = wateringConfidence,
    wateringBaseIntervalDays = wateringBaseIntervalDays,
    pinIntervalToBase = pinIntervalToBase,
    wateringResetAt = wateringResetAt,
    wateringFreezeUntil = wateringFreezeUntil,
    dormancyStartMonth = dormancyStartMonth,
    dormancyEndMonth = dormancyEndMonth,
    fertilizingSeasons = SeasonalFertilizing.decode(fertilizingSeasons),
    dormantWateringIntervalDays = dormantWateringIntervalDays,
    repotPlanSeasonStartAt = repotPlanSeasonStartAt,
    repotPlanMadeAt = repotPlanMadeAt,
    repottingSeasons = SeasonalFertilizing.decode(repottingSeasons),
    hiddenAt = hiddenAt,
    propagatedFromId = propagatedFromId
)

private fun Plant.toEntity() = PlantEntity(
    id = id,
    name = name,
    species = species,
    room = room,
    coverPhotoUri = coverPhotoUri,
    notes = notes,
    wateringIntervalDays = wateringIntervalDays,
    fertilizingIntervalDays = fertilizingIntervalDays,
    repottingIntervalDays = repottingIntervalDays,
    createdAt = createdAt,
    updatedAt = updatedAt,
    wateringDueDateOverride = wateringDueDateOverride,
    useLiquidFertilizer = useLiquidFertilizer,
    archivedAt = archivedAt,
    wateringConfidence = wateringConfidence,
    wateringBaseIntervalDays = wateringBaseIntervalDays,
    pinIntervalToBase = pinIntervalToBase,
    wateringResetAt = wateringResetAt,
    wateringFreezeUntil = wateringFreezeUntil,
    dormancyStartMonth = dormancyStartMonth,
    dormancyEndMonth = dormancyEndMonth,
    fertilizingSeasons = SeasonalFertilizing.encode(fertilizingSeasons),
    dormantWateringIntervalDays = dormantWateringIntervalDays,
    repotPlanSeasonStartAt = repotPlanSeasonStartAt,
    repotPlanMadeAt = repotPlanMadeAt,
    repottingSeasons = SeasonalFertilizing.encode(repottingSeasons),
    hiddenAt = hiddenAt,
    propagatedFromId = propagatedFromId
)
