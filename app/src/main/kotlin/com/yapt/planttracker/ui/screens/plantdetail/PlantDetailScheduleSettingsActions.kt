package com.yapt.planttracker.ui.screens.plantdetail

import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.schedule.DormancyWindow
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeOnce
import com.yapt.planttracker.util.toLocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/**
 * Quiet window after the last −/+ stepper tap before a coalesced watering/fertilizing interval edit
 * commits (#531 review round 1, product ADR-0048) — a burst of taps produces one write and one
 * `MANUAL_EDIT` row instead of one per tap. A slider release or the on/off switch bypasses this
 * window entirely. Issue #799 tracks generalizing same-day-edit coalescing beyond stepper taps.
 */
private const val INTERVAL_TAP_COALESCE_WINDOW_MS = 1000L

/**
 * Inline scheduling edits from the Plant Detail tabs (#436, product ADR-0023). Each persists a
 * single field straight through `PlantRepository.updatePlant`; the change flows back via the
 * `plant` StateFlow so the tab insights update immediately. A `null` interval clears the schedule
 * (the "Not scheduled" state), matching the reminder toggle on Add/Edit Plant.
 *
 * [viaButtonTap] distinguishes a `SteppedSlider` stepper-button tap from a slider release or the
 * enable switch (#531 review round 1, product ADR-0048). A tap schedules (or reschedules) a debounced
 * write [INTERVAL_TAP_COALESCE_WINDOW_MS] after the *last* tap — see [PlantDetailViewModel
 * .pendingWateringTapDays]/[PlantDetailViewModel.pendingWateringTapJob]. A release/switch commits
 * immediately and cancels any pending tap first, so a stale queued value can never overwrite a newer
 * immediate commit. Either path writes through [writeWateringIntervalLocked], which reads the plant
 * fresh inside [PlantDetailViewModel.intervalEditMutex] rather than the cached `plant` StateFlow —
 * that snapshot can lag a write still in flight from the same or another rapid edit, which used to
 * make a burst's `MANUAL_EDIT` row log the wrong `beforeIntervalDays`. A still-pending tap value
 * survives leaving the screen mid-window via `PlantDetailViewModel.onCleared()`, which flushes it
 * through `applicationScope` rather than the (by-then-cancelled) `viewModelScope`.
 */
fun PlantDetailViewModel.setWateringInterval(days: Int?, viaButtonTap: Boolean = false) {
    pendingWateringTapJob?.cancel()
    pendingWateringTapJob = null
    if (viaButtonTap) {
        pendingWateringTapDays = days
        pendingWateringTapJob = viewModelScope.launch {
            delay(INTERVAL_TAP_COALESCE_WINDOW_MS)
            pendingWateringTapDays = null
            // applicationScope, not viewModelScope: this write must survive the screen having been
            // left during the delay above, which cancels viewModelScope before this point is reached.
            applicationScope.launch { writeWateringIntervalLocked(days) }
        }
    } else {
        pendingWateringTapDays = null
        // No delay precedes this write, so viewModelScope (unlike the debounced branch above) is
        // safe — a same-instant clear racing this exact launch call is not a scenario this fix covers.
        viewModelScope.launch { writeWateringIntervalLocked(days) }
    }
}

/**
 * The actual watering-interval write + `MANUAL_EDIT` audit row, shared by [setWateringInterval]'s
 * immediate and debounced-tap paths and by `flushPendingIntervalEditsOnClear()`. Reads the plant
 * fresh inside [PlantDetailViewModel.intervalEditMutex] — never the cached `plant` StateFlow, which
 * can still hold an older snapshot while a previous edit's write is in flight (#531 review round 1).
 */
private suspend fun PlantDetailViewModel.writeWateringIntervalLocked(days: Int?) {
    intervalEditMutex.withLock {
        val p = plantRepository.getPlantById(plantId).first() ?: return@withLock
        // De-seasonalize the newly set value to today (#569), mirroring AddEditPlant's
        // manual-edit handling — unchanged when amplitude is Off, the plant is
        // pinned, or the schedule was just switched off (`days == null`); the prior base
        // (if any) is preserved rather than cleared.
        val deseasonalizedDays = if (days != null && !p.pinIntervalToBase) {
            deseasonalizedBaseOrNull(days)
        } else {
            null
        }
        val wateringBaseIntervalDays = if (days != null && !p.pinIntervalToBase) {
            deseasonalizedDays ?: p.wateringBaseIntervalDays
        } else {
            p.wateringBaseIntervalDays
        }
        val now = System.currentTimeMillis()
        plantRepository.updatePlant(
            p.copy(
                wateringIntervalDays = days,
                wateringBaseIntervalDays = wateringBaseIntervalDays,
                updatedAt = now
            )
        )
        if (days != null && days != p.wateringIntervalDays) {
            // #584 review: log the base-space before/after, not the literal typed value. This
            // deliberately does *not* reuse `wateringBaseIntervalDays` above for "after" — that
            // preserves a stale prior base when season is off, whereas the log's "after" must
            // collapse to the literal `days` in that case (mirrors the "before" side's collapse).
            val loggedAfter = if (!p.pinIntervalToBase) {
                (deseasonalizedDays ?: days.toDouble()).roundToInt()
            } else {
                days
            }
            wateringAdjustmentRepository.addAdjustment(
                WateringAdjustment(
                    plantId = p.id,
                    triggeredAt = now,
                    trigger = WateringAdjustmentTrigger.MANUAL_EDIT,
                    beforeIntervalDays = currentBaseIntervalDaysOrLiteral(p, p.wateringIntervalDays ?: days),
                    afterIntervalDays = loggedAfter
                )
            )
        }
    }
}

/** "Pin interval" switch on the inline Water tab settings card (#569), always visible (#656). */
fun PlantDetailViewModel.setPinIntervalToBase(pinned: Boolean) {
    viewModelScope.launch {
        plant.value?.let {
            plantRepository.updatePlant(it.copy(pinIntervalToBase = pinned, updatedAt = System.currentTimeMillis()))
        }
    }
}

/** Persist a complete window, or clear both columns together, from the Water tab (#762). */
fun PlantDetailViewModel.setDormancyWindow(
    startMonth: Int?,
    endMonth: Int?,
    dormantWateringIntervalDays: Int? = null
) {
    require(
        (startMonth == null && endMonth == null) ||
            (startMonth != null && endMonth != null && startMonth in 1..12 && endMonth in 1..12)
    )
    require(
        dormantWateringIntervalDays == null ||
            DormancyWindow.validWateringInterval(dormantWateringIntervalDays) != null
    )
    viewModelScope.launch {
        dormancyEditMutex.withLock {
            // Read inside the lock: a previous selection may have committed while the screen's
            // StateFlow still holds its older Plant snapshot.
            plantRepository.getPlantById(plantId).first()?.let { current ->
                plantRepository.updatePlant(
                    current.copy(
                        dormancyStartMonth = startMonth,
                        dormancyEndMonth = endMonth,
                        dormantWateringIntervalDays = if (startMonth == null || endMonth == null) {
                            null
                        } else {
                            dormantWateringIntervalDays
                        },
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }
}

private suspend fun PlantDetailViewModel.deseasonalizedBaseOrNull(intervalDays: Int): Double? {
    val amplitude = dataStore.seasonalAmplitudeOnce()
    if (amplitude == 0.0) return null
    return SeasonalWatering.deseasonalize(
        intervalDays.toDouble(),
        System.currentTimeMillis().toLocalDate(),
        amplitude,
        SeasonalWatering.currentHemisphere()
    )
}

/**
 * [plant]'s current base-space reference for [WateringAdjustment] row units (#584 review) —
 * mirrors `QuickLogUseCase`'s `currentAdaptiveBaseIntervalDays()` fallback. Collapses to [literal]
 * itself when the plant is pinned or amplitude is Off, matching every other read of
 * `Plant.wateringBaseIntervalDays`.
 */
@Suppress("ReturnCount")
internal suspend fun PlantDetailViewModel.currentBaseIntervalDaysOrLiteral(plant: Plant, literal: Int): Int {
    if (plant.pinIntervalToBase) return literal
    val amplitude = dataStore.seasonalAmplitudeOnce()
    if (amplitude == 0.0) return literal
    return (plant.wateringBaseIntervalDays ?: literal.toDouble()).roundToInt()
}

/**
 * [viaButtonTap] mirrors [setWateringInterval]'s coalescing (#531 review round 1, product ADR-0048) —
 * fertilizing has no adjustment-log/base-interval concept, so [writeFertilizingIntervalLocked] is a
 * plain field write, but it still shares [PlantDetailViewModel.intervalEditMutex] and the same
 * fresh-read-inside-the-lock fix.
 */
fun PlantDetailViewModel.setFertilizingInterval(days: Int?, viaButtonTap: Boolean = false) {
    pendingFertilizingTapJob?.cancel()
    pendingFertilizingTapJob = null
    if (viaButtonTap) {
        pendingFertilizingTapDays = days
        pendingFertilizingTapJob = viewModelScope.launch {
            delay(INTERVAL_TAP_COALESCE_WINDOW_MS)
            pendingFertilizingTapDays = null
            applicationScope.launch { writeFertilizingIntervalLocked(days) }
        }
    } else {
        pendingFertilizingTapDays = null
        viewModelScope.launch { writeFertilizingIntervalLocked(days) }
    }
}

private suspend fun PlantDetailViewModel.writeFertilizingIntervalLocked(days: Int?) {
    intervalEditMutex.withLock {
        val p = plantRepository.getPlantById(plantId).first() ?: return@withLock
        plantRepository.updatePlant(p.copy(fertilizingIntervalDays = days, updatedAt = System.currentTimeMillis()))
    }
}

/** Inline auto-save for the Fertilize tab's season selector (#795); rejects an empty set, same as Add/Edit Plant. */
fun PlantDetailViewModel.setFertilizingSeasons(seasons: Set<FertilizingSeason>) {
    if (seasons.isEmpty()) return
    viewModelScope.launch {
        plant.value?.let { p ->
            plantRepository.updatePlant(
                p.copy(fertilizingSeasons = seasons, updatedAt = System.currentTimeMillis())
            )
        }
    }
}

fun PlantDetailViewModel.setLiquidFertilizer(enabled: Boolean) {
    viewModelScope.launch {
        plant.value?.let { p ->
            plantRepository.updatePlant(p.copy(useLiquidFertilizer = enabled, updatedAt = System.currentTimeMillis()))
        }
    }
}

/**
 * Flushes a still-pending debounced tap edit (#531 review round 1, product ADR-0048) — called from
 * [PlantDetailViewModel.onCleared]. The screen can be left mid-window (back navigation clears this
 * ViewModel before [INTERVAL_TAP_COALESCE_WINDOW_MS] elapses); without this the edit would simply be
 * lost, since AndroidX cancels `viewModelScope`'s Job — which is what the pending debounce coroutine
 * itself was waiting on — before ever calling `onCleared()`. Runs on `applicationScope`, which that
 * cancellation never touches, so the write still lands.
 */
internal fun PlantDetailViewModel.flushPendingIntervalEditsOnClear() {
    pendingWateringTapDays?.let { days -> applicationScope.launch { writeWateringIntervalLocked(days) } }
    pendingFertilizingTapDays?.let { days -> applicationScope.launch { writeFertilizingIntervalLocked(days) } }
}
