package com.yapt.planttracker.domain.usecase

import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.util.toLocalDate
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * REPOT/room-change confidence resets and the post-reset history bootstrap (#571) — a gardener who
 * repots a plant or moves it to a different room knowingly starts over rather than averaging the old
 * situation with the new. In the #568 model a "chapter" (#285's approach 4) reduces to
 * `confidence = 0`, no new subsystem.
 *
 * A dedicated object rather than folding this directly into [QuickLogUseCase]/`AddCareLogViewModel`/
 * `AddEditPlantViewModel`: a REPOT log is written from two call sites (the manual Add Care Log form and
 * [QuickLogUseCase]'s bulk-action REPOT path via `BulkActionBar`), and the reset side effect must be
 * byte-for-byte identical from both — mirrors `CareLogRepository.hasLogOfTypeOnDay`'s "one query, many
 * callers" precedent (#509).
 */
object WateringLifecycleReset {

    /** A REPOT-triggered reset also freezes live per-observation base-learning for 4 weeks (#571 spec). */
    const val REPOT_FREEZE_WINDOW_DAYS = 28L

    /**
     * Writes the REPOT-triggered lifecycle reset: `wateringConfidence` -> 0, a fresh
     * [Plant.wateringResetAt] anchor (at [resetAnchorMs], the REPOT log's own `loggedAt` — not
     * necessarily "now", since a REPOT log can be backdated), and [Plant.wateringFreezeUntil] 4 weeks
     * out from that anchor. Written once at log-creation time — never derived from querying REPOT log
     * history live — so editing/deleting a past REPOT log can never spuriously re-trigger this.
     */
    suspend fun applyRepotReset(
        plant: Plant,
        resetAnchorMs: Long,
        plantRepository: PlantRepository,
        wateringAdjustmentRepository: WateringAdjustmentRepository?,
        now: Long = System.currentTimeMillis()
    ) {
        val beforeAfter = currentIntervalOrZero(plant)
        plantRepository.updatePlant(
            plant.copy(
                wateringConfidence = 0,
                wateringResetAt = resetAnchorMs,
                wateringFreezeUntil = resetAnchorMs + TimeUnit.DAYS.toMillis(REPOT_FREEZE_WINDOW_DAYS),
                updatedAt = now
            )
        )
        wateringAdjustmentRepository?.addAdjustment(
            WateringAdjustment(
                plantId = plant.id,
                triggeredAt = now,
                trigger = WateringAdjustmentTrigger.REPOT_RESET,
                beforeIntervalDays = beforeAfter,
                afterIntervalDays = beforeAfter
            )
        )
    }

    /**
     * Whether a [Plant.room] change from [previousRoom] to [newRoom] should reset the adaptive model
     * (#571 spec clarification): any real change resets, **except** blank/empty -> filled for the
     * first time, which is data entry rather than a physical move.
     */
    fun roomChangeTriggersReset(previousRoom: String?, newRoom: String?): Boolean {
        if (previousRoom == newRoom) return false
        val isInitialEntry = previousRoom.isNullOrBlank() && !newRoom.isNullOrBlank()
        return !isInitialEntry
    }

    /** Whether [now] still falls inside a REPOT-triggered freeze window ([Plant.wateringFreezeUntil]). */
    fun isFrozen(freezeUntil: Long?, now: Long): Boolean = freezeUntil != null && now < freezeUntil

    /**
     * Bundles [maybeBootstrap]'s per-plant inputs (to stay under Detekt's `LongParameterList`
     * threshold): [waterLogTimestampsMs] is every WATER log timestamp for [plant] (any order);
     * [boundaryMs] bounds which of them are eligible (pass `Long.MIN_VALUE` for the "initial enable"
     * case, which has no boundary — the whole history is eligible); [seasonFn] is the same
     * de-seasonalization function [CareSchedule.bootstrapBaseInterval] uses. [feedback] is the
     * triggering observation's [WateringFeedback] (see [maybeBootstrap]'s doc for why it matters).
     */
    data class BootstrapRequest(
        val plant: Plant,
        val waterLogTimestampsMs: List<Long>,
        val boundaryMs: Long,
        val seasonFn: (LocalDate) -> Double,
        val feedback: WateringFeedback? = null
    )

    /**
     * Evaluates and (if eligible) applies the cold-start history bootstrap (#571 Part B) for
     * [request]: runs [CareSchedule.bootstrapBaseInterval] over every timestamp in
     * [BootstrapRequest.waterLogTimestampsMs] at or after [BootstrapRequest.boundaryMs], and — only
     * when the result clears [CareSchedule.MIN_BOOTSTRAP_GAPS] — writes
     * [Plant.wateringBaseIntervalDays] in base space and [Plant.wateringIntervalDays] in effective
     * display space (mirroring the unit-space rule `QuickLogUseCase.applyWateringIntervalSuggestion()`
     * already established), writes [Plant.wateringConfidence], clears the pending
     * [Plant.wateringResetAt] anchor so this fires exactly once, and records a
     * [WateringAdjustmentTrigger.HISTORY_BOOTSTRAP] row in base space.
     *
     * Returns `true` if it applied, `false` otherwise — not enough history yet is an accepted long-tail
     * outcome, not a bug (a plant may simply never accumulate [CareSchedule.MIN_BOOTSTRAP_GAPS] post-
     * boundary gaps).
     *
     * **The median-of-history estimate is otherwise blind to today's `WateringReason`** (#649, product
     * ADR-0033 follow-up, Codex review finding on #661) — [CareSchedule.bootstrapBaseInterval] only
     * ever sees raw timestamps, so a late "Soil was still moist" watering (`WateringFeedback.TOO_SOON`)
     * landing on a plant's very first adaptive observation (or its first post-reset one) could
     * otherwise still bootstrap to a *shorter* interval than the plant already had, silently breaking
     * ADR-0033's "a late watering can never shorten the interval" guarantee through this one cold-start
     * path — the normal per-observation [CareSchedule.computeAdaptiveInterval] call this bypasses
     * enforces it via the `TOO_SOON_TARGET_MULTIPLIER`, but `maybeBootstrap` never reaches that
     * function. When [BootstrapRequest.feedback] is [WateringFeedback.TOO_SOON], the bootstrapped base
     * is floored at the plant's pre-bootstrap interval so this path can't undercut it either — the
     * *confidence* the bootstrap computes is still applied as-is (it reflects how much history exists,
     * not which direction it should have moved the interval).
     */
    suspend fun maybeBootstrap(
        request: BootstrapRequest,
        plantRepository: PlantRepository,
        wateringAdjustmentRepository: WateringAdjustmentRepository?,
        now: Long = System.currentTimeMillis()
    ): Boolean {
        val result = eligibleBootstrapResult(request) ?: return false

        val plant = request.plant
        val before = currentIntervalOrZero(plant)
        val baseIntervalDays = if (request.feedback == WateringFeedback.TOO_SOON) {
            maxOf(result.baseIntervalDays, before.toDouble())
        } else {
            result.baseIntervalDays
        }
        val afterBase = baseIntervalDays.roundToInt()
        val afterEffective = SeasonalWatering.effectiveInterval(
            baseIntervalDays,
            request.seasonFn(now.toLocalDate())
        )
        // Intentionally overwrites any incremental confidence/base learned per-observation between
        // the freeze ending and this bootstrap firing — the cold-start estimate wins, not a bug.
        plantRepository.updatePlant(
            plant.copy(
                wateringBaseIntervalDays = baseIntervalDays,
                wateringIntervalDays = afterEffective,
                wateringConfidence = result.confidence,
                wateringResetAt = null,
                updatedAt = now
            )
        )
        wateringAdjustmentRepository?.addAdjustment(
            WateringAdjustment(
                plantId = plant.id,
                triggeredAt = now,
                trigger = WateringAdjustmentTrigger.HISTORY_BOOTSTRAP,
                beforeIntervalDays = before,
                afterIntervalDays = afterBase
            )
        )
        return true
    }

    /** `null` when there's no [CareSchedule.bootstrapBaseInterval] result, or it's below the application threshold. */
    private fun eligibleBootstrapResult(request: BootstrapRequest): CareSchedule.BootstrapResult? {
        val eligibleTimestamps = request.waterLogTimestampsMs.filter { it >= request.boundaryMs }
        val result = CareSchedule.bootstrapBaseInterval(eligibleTimestamps, request.seasonFn)
        return result?.takeIf { it.gapCount >= CareSchedule.MIN_BOOTSTRAP_GAPS }
    }

    private fun currentIntervalOrZero(plant: Plant): Int =
        plant.wateringBaseIntervalDays?.roundToInt() ?: plant.wateringIntervalDays ?: 0
}
