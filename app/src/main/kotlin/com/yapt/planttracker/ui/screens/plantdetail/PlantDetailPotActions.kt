package com.yapt.planttracker.ui.screens.plantdetail

import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.domain.model.PotDiameter
import com.yapt.planttracker.domain.model.PotMaterial
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * Opens the optional pot prompt after a Repot logged from Plant Detail (#1003). Quick-log surfaces
 * elsewhere (Care, notification, widget) never call this. The prompt is in-memory state only, so it
 * does not come back after the app is killed or the prompt is dismissed.
 */
internal suspend fun PlantDetailViewModel.offerPotUpdate() {
    val current = plantRepository.getPlantById(plantId).first() ?: return
    setPotPrompt(PotPrompt(current.potMaterial, current.potDiameterCm))
}

/** Skipping changes nothing. */
fun PlantDetailViewModel.dismissPotPrompt() {
    setPotPrompt(null)
}

/**
 * Saves the prompt's answer. [diameterText] blank = unset; anything outside [PotDiameter] is rejected
 * (returns `false`, prompt stays open). Writes only the pot columns, inside
 * [PlantDetailViewModel.plantEditMutex] with the plant re-read fresh, so a concurrent full-row edit can't
 * restore the old pot and a deleted plant is never written.
 */
fun PlantDetailViewModel.savePotPrompt(
    material: PotMaterial?,
    diameterText: String,
    now: Long = System.currentTimeMillis()
): Boolean {
    if (!PotDiameter.isValidInput(diameterText)) return false
    val diameter = PotDiameter.parseOrNull(diameterText)
    setPotPrompt(null)
    viewModelScope.launch {
        plantEditMutex.withLock {
            plantRepository.getPlantById(plantId).first() ?: return@withLock
            plantRepository.updatePot(plantId, material, diameter, now)
        }
    }
    return true
}
