package com.yapt.planttracker.data.backup

import com.yapt.planttracker.data.entity.CareLogEntity
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.data.entity.WateringAdjustmentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests entity↔backup-model mapping. The mapping logic lives inline in BackupManager;
 * these helpers replicate it exactly so the field-level contract is verifiable without
 * an Android runtime.
 */
class BackupModelsTest {

    // --- helpers that mirror BackupManager mapping ---

    private fun PlantEntity.toBackupPlant() = BackupPlant(
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
        wateringConfidence = wateringConfidence,
        wateringBaseIntervalDays = wateringBaseIntervalDays,
        pinIntervalToBase = pinIntervalToBase
    )

    private fun BackupPlant.toPlantEntity() = PlantEntity(
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
        wateringConfidence = wateringConfidence,
        wateringBaseIntervalDays = wateringBaseIntervalDays,
        pinIntervalToBase = pinIntervalToBase
    )

    private fun CareLogEntity.toBackupCareLog() = BackupCareLog(
        id = id,
        plantId = plantId,
        careType = careType,
        loggedAt = loggedAt,
        notes = notes,
        photoUri = photoUri,
        amount = amount,
        wateringFeedback = wateringFeedback
    )

    private fun BackupCareLog.toCareLogEntity() = CareLogEntity(
        id = id,
        plantId = plantId,
        careType = careType,
        loggedAt = loggedAt,
        notes = notes,
        photoUri = photoUri,
        amount = amount,
        wateringFeedback = wateringFeedback
    )

    // --- fixtures ---

    private val fullPlant = PlantEntity(
        id = 1L,
        name = "Monstera",
        species = "Monstera deliciosa",
        room = "Living room",
        coverPhotoUri = "content://uri/photo.jpg",
        notes = "Loves humidity",
        wateringIntervalDays = 7,
        fertilizingIntervalDays = 14,
        repottingIntervalDays = 365,
        createdAt = 1_000_000_000_000L,
        updatedAt = 1_100_000_000_000L,
        wateringConfidence = 2,
        wateringBaseIntervalDays = 6.42,
        pinIntervalToBase = true
    )

    private val fullLog = CareLogEntity(
        id = 10L,
        plantId = 1L,
        careType = "WATER",
        loggedAt = 1_600_000_000_000L,
        notes = "Good watering",
        photoUri = "content://uri/log.jpg",
        amount = "300ml",
        wateringFeedback = "JUST_RIGHT"
    )

    // --- PlantEntity tests ---

    @Test
    fun `PlantEntity to BackupPlant preserves all fields`() {
        val bp = fullPlant.toBackupPlant()
        assertEquals(fullPlant.id, bp.id)
        assertEquals(fullPlant.name, bp.name)
        assertEquals(fullPlant.species, bp.species)
        assertEquals(fullPlant.room, bp.room)
        assertEquals(fullPlant.coverPhotoUri, bp.coverPhotoUri)
        assertEquals(fullPlant.notes, bp.notes)
        assertEquals(fullPlant.wateringIntervalDays, bp.wateringIntervalDays)
        assertEquals(fullPlant.fertilizingIntervalDays, bp.fertilizingIntervalDays)
        assertEquals(fullPlant.repottingIntervalDays, bp.repottingIntervalDays)
        assertEquals(fullPlant.createdAt, bp.createdAt)
        assertEquals(fullPlant.updatedAt, bp.updatedAt)
        assertEquals(fullPlant.wateringConfidence, bp.wateringConfidence)
        assertEquals(fullPlant.wateringBaseIntervalDays, bp.wateringBaseIntervalDays)
        assertEquals(fullPlant.pinIntervalToBase, bp.pinIntervalToBase)
    }

    @Test
    fun `PlantEntity round-trip preserves all fields`() {
        assertEquals(fullPlant, fullPlant.toBackupPlant().toPlantEntity())
    }

    @Test
    fun `PlantEntity round-trip with all-null optionals`() {
        val minimal = PlantEntity(
            id = 2L, name = "Cactus",
            species = null, room = null, coverPhotoUri = null, notes = null,
            wateringIntervalDays = null, fertilizingIntervalDays = null,
            createdAt = 1_000_000_000_000L, updatedAt = 1_000_000_000_000L
        )
        val roundTripped = minimal.toBackupPlant().toPlantEntity()
        assertEquals(minimal, roundTripped)
        assertNull(roundTripped.species)
        assertNull(roundTripped.wateringIntervalDays)
    }

    // --- CareLogEntity tests ---

    @Test
    fun `CareLogEntity to BackupCareLog preserves all fields`() {
        val bl = fullLog.toBackupCareLog()
        assertEquals(fullLog.id, bl.id)
        assertEquals(fullLog.plantId, bl.plantId)
        assertEquals(fullLog.careType, bl.careType)
        assertEquals(fullLog.loggedAt, bl.loggedAt)
        assertEquals(fullLog.notes, bl.notes)
        assertEquals(fullLog.photoUri, bl.photoUri)
        assertEquals(fullLog.amount, bl.amount)
        assertEquals(fullLog.wateringFeedback, bl.wateringFeedback)
    }

    @Test
    fun `CareLogEntity round-trip preserves all fields`() {
        assertEquals(fullLog, fullLog.toBackupCareLog().toCareLogEntity())
    }

    @Test
    fun `CareLogEntity round-trip with all-null optionals`() {
        val minimal = CareLogEntity(
            id = 5L,
            plantId = 1L,
            careType = "FERTILIZE",
            loggedAt = 1_600_000_000_000L,
            notes = null,
            photoUri = null,
            amount = null,
            wateringFeedback = null
        )
        assertEquals(minimal, minimal.toBackupCareLog().toCareLogEntity())
    }

    @Test
    fun `wateringFeedback round-trips as raw String for unknown enum values`() {
        val unknownFeedback = "FUTURE_FEEDBACK_VALUE"
        val log = fullLog.copy(wateringFeedback = unknownFeedback)
        val backup = log.toBackupCareLog()
        assertEquals(unknownFeedback, backup.wateringFeedback)
        assertEquals(unknownFeedback, backup.toCareLogEntity().wateringFeedback)
    }

    @Test
    fun `wateringFeedback null is preserved through round-trip`() {
        val log = fullLog.copy(wateringFeedback = null)
        assertNull(log.toBackupCareLog().toCareLogEntity().wateringFeedback)
    }

    @Test
    fun `BackupCareLog careType stored as raw String`() {
        val bl = fullLog.copy(careType = "PRUNE").toBackupCareLog()
        assertEquals("PRUNE", bl.careType)
    }

    // --- WateringAdjustmentEntity tests (#572) ---

    private fun WateringAdjustmentEntity.toBackupWateringAdjustment() = BackupWateringAdjustment(
        id = id,
        plantId = plantId,
        triggeredAt = triggeredAt,
        trigger = trigger,
        beforeIntervalDays = beforeIntervalDays,
        afterIntervalDays = afterIntervalDays
    )

    private fun BackupWateringAdjustment.toWateringAdjustmentEntity() = WateringAdjustmentEntity(
        id = id,
        plantId = plantId,
        triggeredAt = triggeredAt,
        trigger = trigger,
        beforeIntervalDays = beforeIntervalDays,
        afterIntervalDays = afterIntervalDays
    )

    private val fullWateringAdjustment = WateringAdjustmentEntity(
        id = 100L,
        plantId = 1L,
        triggeredAt = 1_600_000_000_000L,
        trigger = "WATER_TOO_SOON",
        beforeIntervalDays = 8,
        afterIntervalDays = 9
    )

    @Test
    fun `WateringAdjustmentEntity round-trip preserves all fields`() {
        assertEquals(
            fullWateringAdjustment,
            fullWateringAdjustment.toBackupWateringAdjustment().toWateringAdjustmentEntity()
        )
    }

    @Test
    fun `WateringAdjustmentEntity trigger stored as raw String for unknown enum values`() {
        val unknownTrigger = "FUTURE_TRIGGER_VALUE"
        val adjustment = fullWateringAdjustment.copy(trigger = unknownTrigger)
        assertEquals(unknownTrigger, adjustment.toBackupWateringAdjustment().trigger)
    }
}
