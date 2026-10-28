package com.yapt.planttracker.ui.screens.plantdetail

import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/** Which side of the family link the picker is choosing (#960, product ADR-0073). */
enum class FamilyPickerMode { PARENT, CHILD }

/** A plant the picker offers, with the parent it already has so the row can say it will be re-parented. */
data class FamilyCandidate(val plant: Plant, val currentParent: Plant?)

/**
 * Quick-logs a propagation from the Propagate tab's date picker (#960, product ADR-0073) through
 * [com.yapt.planttracker.domain.usecase.QuickLogUseCase.quickLog], which also applies the once-only
 * confidence drop. A same-day duplicate is rejected there; on success the screen is told to offer
 * adding or linking the new plant.
 */
fun PlantDetailViewModel.quickPropagate(loggedAt: Long = System.currentTimeMillis()) {
    viewModelScope.launch {
        val p = plant.value ?: return@launch
        val outcome = quickLogUseCase.quickLog(p, CareType.PROPAGATE, loggedAt)
        if (!outcome.logged) {
            emitQuickLogMessage(PlantDetailViewModel.QuickLogMessage.AlreadyPropagatedToday(p.name))
            return@launch
        }
        emitEvent(PlantDetailViewModel.Event.PropagateLogged)
        emitQuickLogMessage(PlantDetailViewModel.QuickLogMessage.Propagated(p.name))
    }
}

/**
 * The plants the picker may offer for [mode] (#960, product ADR-0073). Every plant counts, archived and
 * hidden ones included, but never this plant itself. [FamilyPickerMode.PARENT] also excludes this plant's
 * descendants; [FamilyPickerMode.CHILD] also excludes its ancestors and its existing children. A candidate
 * that already has a different parent stays selectable and is re-parented when picked. These exclusions
 * are a convenience: the repository re-checks for cycles when it writes.
 */
suspend fun PlantDetailViewModel.familyCandidates(mode: FamilyPickerMode): List<FamilyCandidate> {
    val everyone = plantRepository.getAllPlantsIncludingArchived().first()
    val excluded = when (mode) {
        FamilyPickerMode.PARENT -> plantRepository.getDescendantIds(plantId)
        FamilyPickerMode.CHILD -> plantRepository.getAncestorIds(plantId) +
            everyone.filter { it.propagatedFromId == plantId }.map { it.id }
    } + plantId
    val byId = everyone.associateBy { it.id }
    return everyone
        .filter { it.id !in excluded }
        .sortedBy { it.name.lowercase() }
        .map { FamilyCandidate(it, it.propagatedFromId?.let(byId::get)) }
}

/** Makes [parentId] this plant's parent (#960). Refusals (a cycle) surface as [PlantDetailViewModel.Event.FamilyLinkRefused]. */
fun PlantDetailViewModel.linkParent(parentId: Long) = link(childId = plantId, parentId = parentId)

/** Makes [childId] a child of this plant, re-parenting it if it had another parent (#960). */
fun PlantDetailViewModel.linkChild(childId: Long) = link(childId = childId, parentId = plantId)

private fun PlantDetailViewModel.link(childId: Long, parentId: Long) {
    viewModelScope.launch {
        val linked = plantEditMutex.withLock { plantRepository.linkParent(childId, parentId) }
        if (!linked) emitEvent(PlantDetailViewModel.Event.FamilyLinkRefused)
    }
}

/** Clears this plant's own parent link (#960); neither plant is touched otherwise. */
fun PlantDetailViewModel.unlinkParent() = unlink(plantId)

/** Clears [childId]'s parent link (#960). */
fun PlantDetailViewModel.unlinkChild(childId: Long) = unlink(childId)

private fun PlantDetailViewModel.unlink(childId: Long) {
    viewModelScope.launch {
        plantEditMutex.withLock { plantRepository.unlinkParent(childId) }
    }
}
