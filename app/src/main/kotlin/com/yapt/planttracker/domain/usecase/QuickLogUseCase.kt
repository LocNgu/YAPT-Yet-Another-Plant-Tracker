package com.yapt.planttracker.domain.usecase

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.room.withTransaction
import com.yapt.planttracker.R
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.FertilizerType
import com.yapt.planttracker.domain.model.PhotoReminderRequest
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.QuickWaterSuggestion
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.model.WateringFeedback
import com.yapt.planttracker.domain.model.WateringReason
import com.yapt.planttracker.domain.reminder.PhotoReminderPolicy
import com.yapt.planttracker.domain.schedule.CareSchedule
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeOnce
import com.yapt.planttracker.ui.util.labelRes
import com.yapt.planttracker.util.toLocalDate
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * Shared quick-log business logic used by both `PlantListViewModel` (PlantCard quick-log buttons)
 * and `CalendarViewModel` (day-sheet quick-log buttons), extracted to avoid duplicating the same
 * ~150 lines of care-log + adaptive-interval + photo-reminder logic across both ViewModels.
 *
 * Each ViewModel resolves its own [Plant] from its own `plantsWithStatus` state, calls the
 * relevant method here, and maps the returned domain object (message string, [QuickWaterSuggestion],
 * or [PhotoReminderRequest]) onto its own StateFlow/SharedFlow. This use case owns no UI-facing
 * state itself — every method is a plain suspend function.
 */
// #568 added two small adaptive-watering helpers to this already-cohesive choke point; splitting
// them out would scatter closely related logic across files for no readability gain.
@Suppress("TooManyFunctions", "LongParameterList")
class QuickLogUseCase(
    private val application: Application,
    private val plantRepository: PlantRepository,
    private val careLogRepository: CareLogRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    private val dataStore: DataStore<Preferences>,
    private val database: PlantDatabase,
    private val wateringAdjustmentRepository: WateringAdjustmentRepository,
    private val nowProvider: () -> Long = System::currentTimeMillis
) {

    /**
     * Result of a quick-log attempt. [logged] is false when the log was skipped because [plant]
     * already has a WATER/FERTILIZE log for the current calendar day (#509) — callers should skip
     * side effects (adaptive-interval suggestion, photo-reminder check) in that case. [waterPaired]
     * is true only when a liquid-fertilizer quick action actually inserted the paired WATER log
     * (it's suppressed, but FERTILIZE still proceeds, when the plant was already watered today).
     */
    data class QuickLogOutcome(
        val message: String,
        val logged: Boolean,
        val waterPaired: Boolean = false,
        val suggestion: QuickWaterSuggestion? = null
    )

    /** Summary of a [bulkLog] run: how many of [totalCount] plants were actually logged vs. skipped. */
    data class BulkLogResult(val loggedCount: Int, val skippedCount: Int, val totalCount: Int)

    /**
     * Result of [applyWateringIntervalSuggestion] — the plant's actual prior
     * [Plant.wateringIntervalDays]/[Plant.wateringBaseIntervalDays], alongside the effective value that
     * was actually written. Pre-#644 this differed from the raw base-space `newInterval` handed in
     * (#626); since #644, `newInterval` is itself already effective-space, so [newEffectiveIntervalDays]
     * is just that same value echoed back — kept as its own field for callers ([Event.SilentIntervalApplied]
     * etc.) that read it without needing to know the input parameter's semantics changed.
     */
    data class IntervalApplyResult(
        val previousEffectiveIntervalDays: Int,
        val previousBaseIntervalDays: Double?,
        val newEffectiveIntervalDays: Int
    )

    /**
     * Logs [careType] for every plant in [plants] inside a single Room transaction, so a bulk
     * care action is applied atomically — a killed process can't leave some of the selected
     * plants logged and others not (#448). Watering carries no reason (see below); other
     * care types route through [quickLog] so liquid-fertilizer plants still get a paired watering.
     * Plants that already have today's log for [careType] are skipped (#509) rather than aborting
     * the whole batch. Per-plant interval-suggestion and photo-reminder side effects are
     * intentionally not surfaced here — bulk callers skip those dialogs.
     *
     * A bulk watering carries no reason: the user never saw a per-plant prompt, so it passes `null`
     * (#586, product ADR-0030 — replacing the defaulted [WateringFeedback.JUST_RIGHT] this used to
     * write). For an on-schedule plant that is the same quiet gap observation as before; for an
     * off-schedule one the adaptive model excludes it from base learning, which is the safe direction
     * for an action the user took without attributing anything to any particular plant.
     */
    suspend fun bulkLog(plants: List<Plant>, careType: CareType): BulkLogResult {
        var loggedCount = 0
        database.withTransaction {
            for (plant in plants) {
                val outcome = if (careType == CareType.WATER) {
                    quickWaterWithReason(plant, reason = null)
                } else {
                    quickLog(plant, careType)
                }
                if (outcome.logged) loggedCount++
            }
        }
        return BulkLogResult(
            loggedCount = loggedCount,
            skippedCount = plants.size - loggedCount,
            totalCount = plants.size
        )
    }

    /**
     * Logs [careType] for [plant]. Returns [QuickLogOutcome.logged] = false without inserting
     * anything if [plant] already has a [careType] log today (WATER/FERTILIZE only, #509). For a
     * liquid-fertilizer plant, the "already watered today" check runs before the FERTILIZE insert
     * so the paired WATER insert can be suppressed without racing against itself.
     */
    suspend fun quickLog(plant: Plant, careType: CareType): QuickLogOutcome {
        if (isDuplicateGuarded(careType) && hasLoggedToday(plant.id, careType)) {
            return QuickLogOutcome(message = alreadyLoggedMessage(plant, careType), logged = false)
        }
        val alreadyWateredToday = careType == CareType.FERTILIZE &&
            plant.useLiquidFertilizer &&
            hasLoggedToday(plant.id, CareType.WATER)

        val now = System.currentTimeMillis()
        val log = CareLog(
            plantId = plant.id,
            careType = careType,
            loggedAt = now,
            wateringFeedback = null,
            fertilizerType = if (careType == CareType.FERTILIZE && plant.useLiquidFertilizer) FertilizerType.LIQUID else FertilizerType.UNSPECIFIED
        )
        careLogRepository.addLog(log)
        maybeApplyRepotReset(plant, careType, now)
        val waterPaired = careType == CareType.FERTILIZE && plant.useLiquidFertilizer && !alreadyWateredToday
        if (waterPaired) {
            // No reason: the user fertilized, and the watering came along with it (ADR-0008) — they
            // were never asked why they watered, so nothing is attributed (#586).
            careLogRepository.addLog(
                CareLog(
                    plantId = plant.id,
                    careType = CareType.WATER,
                    loggedAt = now,
                    wateringFeedback = null
                )
            )
            clearWateringOverrideIfActive(plant.id)
        }
        val message = when (careType) {
            CareType.FERTILIZE -> if (waterPaired) {
                application.getString(R.string.quick_log_watered_and_fertilized, plant.name)
            } else {
                application.getString(R.string.quick_log_fertilized, plant.name)
            }
            else -> application.getString(
                R.string.quick_log_other,
                application.getString(careType.labelRes()),
                plant.name
            )
        }
        return QuickLogOutcome(message = message, logged = true, waterPaired = waterPaired)
    }

    /**
     * Logs a watering with the given [reason] (#586, product ADR-0030), clears any active skip
     * override, and returns a [QuickLogOutcome] with a [QuickWaterSuggestion] if the adaptive
     * interval system produces one. Returns [QuickLogOutcome.logged] = false without inserting
     * anything if [plant] already has a WATER log on the calendar day containing [loggedAt] (#509).
     *
     * [reason] is `null` for an on-schedule watering (no prompt appears at all — the fast path), for
     * a watering the user logged without choosing a reason, and for surfaces that never ask (bulk
     * log, the notification's "Watered" action). Which of those it was is never stored: the model
     * separates them from timing alone (see [CareSchedule.computeAdaptiveInterval]).
     *
     * [loggedAt] defaults to "now" but Plant Detail's "Log watering" date picker (#654) can pass a
     * backdated timestamp instead — the same value drives the duplicate-day check, the [CareLog] write,
     * and the adaptive-gap math ([computeSuggestion]/[adaptWateringInterval]'s `now`), so none of the
     * three can drift from each other or silently fall back to the real wall-clock time.
     */
    suspend fun quickWaterWithReason(
        plant: Plant,
        reason: WateringReason?,
        loggedAt: Long = System.currentTimeMillis()
    ): QuickLogOutcome {
        if (hasLoggedToday(plant.id, CareType.WATER, loggedAt)) {
            return QuickLogOutcome(message = alreadyLoggedMessage(plant, CareType.WATER), logged = false)
        }
        val feedback = reason?.toWateringFeedback()
        careLogRepository.addLog(
            CareLog(
                plantId = plant.id,
                careType = CareType.WATER,
                loggedAt = loggedAt,
                wateringFeedback = feedback
            )
        )
        val freshPlant = clearWateringOverrideIfActive(plant.id) ?: plant
        val suggestion = computeSuggestion(freshPlant, feedback, loggedAt)
        return QuickLogOutcome(
            message = application.getString(R.string.quick_log_watered, plant.name),
            logged = true,
            suggestion = suggestion
        )
    }

    /**
     * Logs a paired FERTILIZE + WATER entry for liquid-fertilizer plants, mirroring
     * [quickWaterWithReason] — the paired watering is a watering like any other, so the same #586
     * reason prompt governs it. Returns [QuickLogOutcome.logged] = false without inserting
     * anything if [plant] already has a FERTILIZE log on the calendar day containing [loggedAt]. If
     * [plant] was already watered that same day, the paired WATER insert is suppressed (checked before
     * the FERTILIZE insert so it can't race against a WATER row inserted earlier in this same call)
     * but the FERTILIZE log still proceeds (#509).
     *
     * [loggedAt] mirrors [quickWaterWithReason]'s parameter of the same name (#654) — the same value
     * drives both duplicate-day checks, both [CareLog] writes, and the paired watering's adaptive-gap
     * math.
     */
    suspend fun quickLiquidFertilizeWithReason(
        plant: Plant,
        reason: WateringReason?,
        loggedAt: Long = System.currentTimeMillis()
    ): QuickLogOutcome {
        if (hasLoggedToday(plant.id, CareType.FERTILIZE, loggedAt)) {
            return QuickLogOutcome(message = alreadyLoggedMessage(plant, CareType.FERTILIZE), logged = false)
        }
        val feedback = reason?.toWateringFeedback()
        val alreadyWateredToday = hasLoggedToday(plant.id, CareType.WATER, loggedAt)

        careLogRepository.addLog(
            CareLog(
                plantId = plant.id,
                careType = CareType.FERTILIZE,
                loggedAt = loggedAt,
                wateringFeedback = null,
                fertilizerType = FertilizerType.LIQUID
            )
        )

        return if (alreadyWateredToday) {
            QuickLogOutcome(
                message = application.getString(R.string.quick_log_fertilized, plant.name),
                logged = true,
                waterPaired = false
            )
        } else {
            careLogRepository.addLog(
                CareLog(
                    plantId = plant.id,
                    careType = CareType.WATER,
                    loggedAt = loggedAt,
                    wateringFeedback = feedback
                )
            )
            val freshPlant = clearWateringOverrideIfActive(plant.id) ?: plant
            QuickLogOutcome(
                message = application.getString(R.string.quick_log_watered_and_fertilized, plant.name),
                logged = true,
                waterPaired = true,
                suggestion = computeSuggestion(freshPlant, feedback, loggedAt)
            )
        }
    }

    /**
     * The single write path for committing a new [Plant.wateringIntervalDays] from an ADR-0006
     * adaptive suggestion (#572) — shared by the Plant Detail dialog's Apply button/silent-apply path,
     * the Calendar suggestion dialog, and the Plant List suggestion dialog (#631). Before this fix each
     * of the three screens carried its own independent copy of this exact math; only Plant Detail's had
     * been fixed for #572 (base dual-write) and #626 (effective-space conversion), and Calendar/Plant
     * List silently reproduced both bugs. Now there is exactly one implementation.
     *
     * [originalSuggestion] is the interval that was originally suggested before any retyping — still
     * **base-space** (this class's own adaptive suggestion, e.g. [QuickWaterSuggestion.suggestedInterval]
     * / `PendingWateringSuggestion.rawIntervalDays`) — `null` disables
     * [CareSchedule.confidenceAfterDialogEdit] (no caller passes `null` today; kept for parity with call
     * sites that may not always have one).
     *
     * **[newInterval] is *effective*-space (#644)**, not base-space as it was before this fix — every
     * one of the three dialogs' editable text fields now pre-fills from (and, on Apply, submits) the
     * same effective, seasonally-converted number the dialog's "Suggested: N days" sentence already
     * shows, so the field and the sentence can no longer disagree (#644's bug). [Plant.wateringIntervalDays]
     * is read everywhere else as that same effective value (the "currently" figure, the Water tab
     * slider, [com.yapt.planttracker.domain.schedule.WateringExplanationBuilder]), so [newInterval] is
     * now written to it **directly** — no re-conversion, unlike pre-#644 where a base-space input was
     * run through [CareSchedule.effectiveWateringIntervalDaysForDisplay] before the write. What used to
     * be that conversion is now inverted: [SeasonalWatering.deseasonalize] derives the base-space
     * equivalent of [newInterval] once (`newIntervalBaseSpace`, mirroring
     * [com.yapt.planttracker.ui.screens.plantdetail.PlantDetailViewModel]'s `deseasonalizedBaseOrNull`/
     * `setWateringInterval` manual-edit precedent), reused both for the [Plant.wateringBaseIntervalDays]
     * dual-write (#572) below and for the confidence-tolerance comparison against [originalSuggestion]
     * (see the comment there for why that comparison needs it). Gated on the same
     * `!plant.pinIntervalToBase && amplitude != 0.0` condition as before (#584 review round 2) — pinned
     * or zero-amplitude plants keep [newInterval] as a literal value and leave the stored base untouched
     * rather than clobbering a real prior base with one that was never seasonally converted.
     *
     * Also, when `ADAPTIVE_WATERING` is on, writes a [WateringAdjustmentTrigger.DIALOG_EDIT] row whose
     * `afterIntervalDays` deliberately stays base-space (`newIntervalBaseSpace`, rounded) — the model's
     * base-space accounting, not the user-facing effective value now written into
     * [Plant.wateringIntervalDays] — this divergence from the literal value is intentional and unchanged
     * from before #644, only the source value used to derive it has changed.
     */
    suspend fun applyWateringIntervalSuggestion(
        plant: Plant,
        originalSuggestion: Int?,
        newInterval: Int
    ): IntervalApplyResult {
        val now = System.currentTimeMillis()
        val adaptiveOn = isAdaptiveWateringEnabled()
        // ADAPTIVE_WATERING/SEASONAL_WATERING are independent flags — when amplitude is 0 or the plant
        // is pinned, newInterval is a *literal* value, not base-space-convertible, so it's used as-is
        // for the confidence check and the base is left untouched below rather than clobbered with a
        // never-seasonally-converted value (#584 review round 2, still applies post-#644).
        val amplitude = dataStore.seasonalAmplitudeOnce()
        val seasonAdjustable = !plant.pinIntervalToBase && amplitude != 0.0
        val newIntervalBaseSpace = if (seasonAdjustable) {
            SeasonalWatering.deseasonalize(
                newInterval.toDouble(),
                nowProvider().toLocalDate(),
                amplitude,
                SeasonalWatering.currentHemisphere()
            )
        } else {
            newInterval.toDouble()
        }
        // Retyping the suggested number before tapping Apply is fine-tuning within the model, not a
        // rejection of it — never a full reset like an AddEditPlant edit (#568). Outside
        // GAP_AGREEMENT_TOLERANCE of the original suggestion, the suggestion was materially wrong and
        // confidence falls, but the model still stands. A silent apply always passes an unedited
        // newInterval, so confidence never falls from an apply the user never edited.
        // #644: originalSuggestion is base-space, newInterval is now effective-space — compare like for
        // like by converting newInterval back down to newIntervalBaseSpace (computed above and reused
        // for the wateringBaseIntervalDays write below) rather than converting originalSuggestion up,
        // since the base-space conversion is already needed regardless of this check.
        val wateringConfidence = if (adaptiveOn && originalSuggestion != null) {
            CareSchedule.confidenceAfterDialogEdit(
                plant.wateringConfidence,
                originalSuggestion,
                newIntervalBaseSpace.roundToInt()
            )
        } else {
            plant.wateringConfidence
        }
        val newBaseIntervalDays = if (seasonAdjustable) newIntervalBaseSpace else plant.wateringBaseIntervalDays
        val previousEffectiveIntervalDays = plant.wateringIntervalDays ?: newInterval
        val previousBaseIntervalDays = plant.wateringBaseIntervalDays
        // #644: newInterval is effective-space now — write it to wateringIntervalDays directly, no
        // re-conversion (that used to be #626's fix; it's now folded into the deseasonalize step above).
        plantRepository.updatePlant(
            plant.copy(
                wateringIntervalDays = newInterval,
                wateringBaseIntervalDays = newBaseIntervalDays,
                wateringConfidence = wateringConfidence,
                updatedAt = now
            )
        )
        if (adaptiveOn) {
            // #584 review: `previousEffectiveIntervalDays` may still be a literal effective value
            // rather than base-space — read the plant's actual current base for the row instead. #626:
            // this row stays base-space and deliberately keeps diverging from the literal
            // wateringIntervalDays now written above — it represents the model's base-space
            // accounting, not the user-facing effective value.
            wateringAdjustmentRepository.addAdjustment(
                WateringAdjustment(
                    plantId = plant.id,
                    triggeredAt = now,
                    trigger = WateringAdjustmentTrigger.DIALOG_EDIT,
                    beforeIntervalDays = currentAdaptiveBaseIntervalDays(plant, previousEffectiveIntervalDays),
                    afterIntervalDays = newIntervalBaseSpace.roundToInt()
                )
            )
        }
        return IntervalApplyResult(previousEffectiveIntervalDays, previousBaseIntervalDays, newInterval)
    }

    /**
     * Records a "Soil still moist" observation: a [CareType.CHECK] log (`wateringFeedback = TOO_SOON`
     * — the plant was checked and not watered) and a [Plant.wateringDueDateOverride] set to
     * [newDueAtMillis]. Reached from the Reschedule reason prompt in the app (#586, product ADR-0030)
     * and from the check-reminders notification's Still-moist action (#570, `check_reminders` feature
     * flag, `StillMoistReceiver`) — one call site, so the two paths cannot drift.
     *
     * [newDueAtMillis] replaces #570's flat `+1 day` constant, which could not clear "due" for a plant
     * overdue by two or more days while the same-day guard blocked a second tap. In the app the user
     * picks the date; the notification, which has no picker, passes
     * [suggestedStillMoistDeferralDays] applied to now. Returns `false` without inserting anything if
     * [plant] already has a CHECK log today — a notification firing the action twice in one day (e.g.
     * the "Run reminder check now" debug action) shouldn't double-log (#509-style guard via
     * [isDuplicateGuarded]).
     *
     * Feeds the observation into [CareSchedule.computeAdaptiveInterval] only when `adaptive_watering`
     * is on, and only updates [Plant.wateringConfidence] — it never silently rewrites the stored
     * interval itself, mirroring every other quick-log surface's "confidence updates regardless of
     * whether a suggestion is ever shown/applied" rule (no dialog is ever shown here, so there is no
     * "apply" step to silently substitute for). The *length* of the deferral is never an input to the
     * model — only the reason is (#586): a "soil still moist" reschedule of +1 day and one of +5 teach
     * exactly the same thing.
     *
     * The override and (when adaptive watering is on) confidence are written in a single
     * [PlantRepository.updatePlant] call built off this same [plant] snapshot (#612) — two sequential
     * `.copy()`/`updatePlant` calls off the same stale snapshot let the second silently revert the
     * first's [Plant.wateringDueDateOverride] write.
     */
    suspend fun recordStillMoistCheck(plant: Plant, newDueAtMillis: Long): Boolean {
        if (isDuplicateGuarded(CareType.CHECK) && hasLoggedToday(plant.id, CareType.CHECK)) {
            return false
        }
        val now = System.currentTimeMillis()
        careLogRepository.addLog(
            CareLog(
                plantId = plant.id,
                careType = CareType.CHECK,
                loggedAt = now,
                wateringFeedback = WateringFeedback.TOO_SOON
            )
        )

        val updatedConfidence = if (isAdaptiveWateringEnabled()) {
            recordStillMoistAdaptiveObservation(plant, now)
        } else {
            null
        }

        plantRepository.updatePlant(
            plant.copy(
                wateringDueDateOverride = newDueAtMillis,
                wateringConfidence = updatedConfidence ?: plant.wateringConfidence,
                updatedAt = now
            )
        )
        return true
    }

    /**
     * How many days a "Soil still moist" observation suggests deferring by (#586, product ADR-0030),
     * derived from the interval the adaptive model would land on *after* this observation rather than
     * from a constant: "come back when the freshly-lengthened interval says it is due", i.e.
     * `newBase - observedGap`, floored at one day so it always moves the date forward.
     *
     * Falls back to [DEFAULT_STILL_MOIST_DEFERRAL_DAYS] (#570's flat +1 day) whenever there is nothing
     * to derive from: adaptive watering off, no interval configured, or no previous watering. This is
     * a preview — it writes nothing — so the in-app picker can open on it and the notification action,
     * which has no picker, can apply it directly.
     */
    @Suppress("ReturnCount")
    suspend fun suggestedStillMoistDeferralDays(plant: Plant): Int {
        if (!isAdaptiveWateringEnabled()) return DEFAULT_STILL_MOIST_DEFERRAL_DAYS
        val currentInterval = plant.wateringIntervalDays ?: return DEFAULT_STILL_MOIST_DEFERRAL_DAYS
        val lastWatering = careLogRepository.getLastLogOfType(plant.id, CareType.WATER)
            ?: return DEFAULT_STILL_MOIST_DEFERRAL_DAYS
        val observedIntervalDays = CareSchedule.daysBetween(lastWatering.loggedAt, nowProvider())
        if (observedIntervalDays <= 0) return DEFAULT_STILL_MOIST_DEFERRAL_DAYS
        val result = computeStillMoistAdaptiveInterval(plant, observedIntervalDays)
        return (result.intervalDays - observedIntervalDays).coerceAtLeast(DEFAULT_STILL_MOIST_DEFERRAL_DAYS)
    }

    /**
     * Returns the new [Plant.wateringConfidence] if this observation changes it, or `null` when
     * there's nothing to derive from or confidence is unchanged — [recordStillMoistCheck] folds the
     * result into its single combined `updatePlant` call rather than writing here (#612).
     */
    @Suppress("ReturnCount")
    private suspend fun recordStillMoistAdaptiveObservation(plant: Plant, now: Long): Int? {
        val currentInterval = plant.wateringIntervalDays ?: return null
        val lastWatering = careLogRepository.getLastLogOfType(plant.id, CareType.WATER) ?: return null
        val actualIntervalDays = CareSchedule.daysBetween(lastWatering.loggedAt, now)
        if (actualIntervalDays <= 0) return null

        val currentBase = currentAdaptiveBaseIntervalDays(plant, currentInterval)
        val frozen = WateringLifecycleReset.isFrozen(plant.wateringFreezeUntil, now)
        val result = computeStillMoistAdaptiveInterval(plant, actualIntervalDays, frozen)
        wateringAdjustmentRepository.addAdjustment(
            WateringAdjustment(
                plantId = plant.id,
                triggeredAt = now,
                trigger = if (frozen) WateringAdjustmentTrigger.FROZEN_POST_REPOT else WateringAdjustmentTrigger.CHECK_STILL_MOIST,
                beforeIntervalDays = currentBase,
                afterIntervalDays = result.intervalDays
            )
        )
        return result.confidence.takeIf { it != plant.wateringConfidence }
    }

    /**
     * The one place a "soil still moist" observation is turned into an [CareSchedule.AdaptiveInterval]
     * — shared by [suggestedStillMoistDeferralDays] (preview, writes nothing) and
     * [recordStillMoistAdaptiveObservation] (the real write), so the deferral the picker suggests and
     * the interval the model actually learns are computed by the same code (#586). [frozen] (#571) is
     * always `false` for the preview, since a real freeze can only be known at the moment of the write.
     */
    private suspend fun computeStillMoistAdaptiveInterval(
        plant: Plant,
        observedIntervalDays: Int,
        frozen: Boolean = false
    ): CareSchedule.AdaptiveInterval {
        val recentFeedback = careLogRepository.getRecentWaterings(plant.id, limit = RECENT_WATERINGS_WINDOW)
            .map { it.wateringFeedback }
        return CareSchedule.computeAdaptiveInterval(
            feedback = WateringFeedback.TOO_SOON,
            observedIntervalDays = deseasonalizedObservedIntervalDays(observedIntervalDays, plant.pinIntervalToBase),
            currentBaseIntervalDays = currentAdaptiveBaseIntervalDays(
                plant,
                plant.wateringIntervalDays ?: observedIntervalDays
            ),
            currentConfidence = plant.wateringConfidence,
            recentFeedback = recentFeedback,
            frozen = frozen
        )
    }

    /**
     * Builds a [PhotoReminderRequest] for [plantId] if the feature is enabled, the plant hasn't
     * already been reminded this session (shared across surfaces via
     * [PhotoReminderPolicy.shownThisSession]), and the newest photo across plant photos and
     * care-log photos is at least [PhotoReminderPolicy.PHOTO_REMINDER_INTERVAL_DAYS] days old.
     * Returns null when no reminder should be shown. Callers decide what to do with the result
     * (e.g. suppress it while another dialog is showing) — that UI-level rule stays in the screen.
     */
    suspend fun maybeBuildPhotoReminderRequest(plantId: Long): PhotoReminderRequest? {
        val enabled = dataStore.data.first()[SettingsKeys.PHOTO_REMINDER_ENABLED] ?: false
        if (!enabled) return null
        if (plantId in PhotoReminderPolicy.shownThisSession) return null
        val plant = plantRepository.getPlantById(plantId).first() ?: return null
        val lastPlantPhotoTs = plantPhotoRepository.getPhotosForPlantOnce(plantId)
            .maxOfOrNull { it.capturedAt }
        val lastCareLogPhotoTs = careLogRepository.getPhotoLogsForPlant(plantId).first()
            .mapNotNull { log -> log.photoUri?.let { log.loggedAt } }
            .maxOrNull()
        val lastPhotoTs = listOfNotNull(lastPlantPhotoTs, lastCareLogPhotoTs).maxOrNull()
        val daysSince = PhotoReminderPolicy.lastPhotoDaysSince(lastPhotoTs, plant.createdAt)
        if (daysSince >= PhotoReminderPolicy.PHOTO_REMINDER_INTERVAL_DAYS) {
            PhotoReminderPolicy.shownThisSession.add(plantId)
            return PhotoReminderRequest(plantId, plant.name, daysSince)
        }
        return null
    }

    private fun isDuplicateGuarded(careType: CareType) =
        careType == CareType.WATER || careType == CareType.FERTILIZE || careType == CareType.CHECK

    /**
     * [dayTimestampMs] defaults to "now" but a caller backdating a log (#654) passes the chosen date
     * instead, so the duplicate-day guard keys off the *logged* day, not the wall-clock day the check
     * happens to run on.
     */
    private suspend fun hasLoggedToday(
        plantId: Long,
        careType: CareType,
        dayTimestampMs: Long = System.currentTimeMillis()
    ): Boolean = careLogRepository.hasLogOfTypeOnDay(plantId, careType, dayTimestampMs)

    private fun alreadyLoggedMessage(plant: Plant, careType: CareType): String = when (careType) {
        CareType.WATER -> application.getString(R.string.quick_log_already_watered, plant.name)
        CareType.FERTILIZE -> application.getString(R.string.quick_log_already_fertilized, plant.name)
        else -> error("No duplicate guard defined for $careType")
    }

    /**
     * [feedback] may be `null` (#570, product ADR-0027) — the quick-water sheet's chip collapsed to
     * one optional flag, so `null` is now the dominant case. The legacy (flag-off) branch still
     * needs an explicit value to produce a suggestion; the adaptive branch accepts `null` directly.
     *
     * Gates on the **effective**-space comparison (#620), not the raw base-space [suggestion] vs
     * [current] — [suggestion] is season-neutral base space while `plant.wateringIntervalDays`
     * ("current") is what the Calendar/Plant List/Plant Detail dialogs display as today's cadence, so
     * comparing them directly could flag a pure unit-mismatch artifact as a real change (the same bug
     * #620 fixed for the Plant Detail dialog specifically). This is the single choke point all three
     * quick-log surfaces share, so none of them can independently regress this comparison again.
     *
     * The observed gap is computed against [now]'s own chronological predecessor
     * ([CareLogRepository.getLastWateringBefore], strictly earlier `loggedAt`), not "the two globally
     * newest waterings" (#654 round-2 review fix) — [now] is the just-inserted log's own `loggedAt`
     * (real "now", or Plant Detail's backdated pick), and the two can disagree once a caller backdates
     * a log to a date *before* an already-existing WATER log: the old `getLastTwoWaterings()`-based
     * query would pair the new log with that later, already-existing one (or skip the new log's real
     * neighbor entirely) instead of the log the new one actually follows.
     */
    private suspend fun computeSuggestion(
        plant: Plant,
        feedback: WateringFeedback?,
        now: Long = System.currentTimeMillis()
    ): QuickWaterSuggestion? {
        val current = plant.wateringIntervalDays ?: return null
        val previousWatering = careLogRepository.getLastWateringBefore(plant.id, now) ?: return null
        val actual = CareSchedule.daysBetween(previousWatering.loggedAt, now)
        if (actual <= 0) return null
        val suggestion = if (isAdaptiveWateringEnabled()) {
            adaptWateringInterval(plant, feedback, actual, current, now)
        } else {
            feedback?.let { CareSchedule.computeSuggestedInterval(it, actual, current) } ?: return null
        }
        val effectiveSuggestion = effectiveIntervalForDisplay(plant, suggestion, now)
        return if (effectiveSuggestion != current) {
            QuickWaterSuggestion(plant.id, plant.name, suggestion, effectiveSuggestion)
        } else {
            null
        }
    }

    /**
     * Converts a base-space [suggestion] to effective (display) space via the same wrapper
     * [CareSchedule.effectiveWateringIntervalDaysForDisplay] the "Why this date?" sheet and
     * `PlantDetailViewModel.pendingWateringSuggestion` use (#620), so no call site can drift from
     * another. Treats [suggestion] as if it were the plant's new base — mirroring
     * `PlantDetailViewModel`'s identical `plant.copy(...)` pattern — so this is a display-only, one-way
     * conversion; the returned [QuickWaterSuggestion.suggestedInterval] (write path) is untouched.
     *
     * [now] mirrors [computeSuggestion]'s own parameter of the same name (#654 review) — the season used
     * to convert [suggestion] must be the day the watering was actually logged (possibly backdated), not
     * [nowProvider]'s real wall-clock time, or the "different from current" comparison the ADR-0006
     * dialog relies on could be judged against the wrong season.
     */
    private suspend fun effectiveIntervalForDisplay(
        plant: Plant,
        suggestion: Int,
        now: Long = System.currentTimeMillis()
    ): Int =
        CareSchedule.effectiveWateringIntervalDaysForDisplay(
            plant = plant.copy(wateringBaseIntervalDays = suggestion.toDouble(), wateringIntervalDays = suggestion),
            nowDate = now.toLocalDate(),
            seasonalAmplitude = dataStore.seasonalAmplitudeOnce()
        ) ?: suggestion

    /**
     * Applies the multiplicative + confidence-weighted model (#568, technical ADR-0021) and
     * persists the resulting [Plant.wateringConfidence] immediately, independent of whether the
     * caller ends up surfacing/applying the returned suggestion. [feedback] may be `null` (#570) — a
     * silent gap-only observation, capped at [CareSchedule.NEUTRAL_OBSERVATION_GAIN].
     *
     * Before evaluating the per-observation update, checks whether this WATER log is the one that
     * unlocks the #571 history bootstrap (either the plant's first-ever adaptive observation, or a
     * pending post-reset opportunity) — see [maybeApplyHistoryBootstrap]. When it fires, the bootstrap
     * already silently committed the new interval, so this returns [currentInterval] unchanged
     * (suppressing the ADR-0006 suggestion dialog for this observation) rather than also running the
     * incremental per-step correction on top of a value the model just cold-started.
     *
     * [now] defaults to the real wall-clock time but [computeSuggestion] threads through the caller's
     * chosen [loggedAt][quickWaterWithReason] instead when backdating (#654) — the same value that
     * decided the duplicate-day check and the [CareLog] write also decides the freeze-window check and
     * the [WateringAdjustment.triggeredAt] this records, so a backdated observation can't be evaluated
     * against "today" while claiming to have happened on an earlier day.
     */
    private suspend fun adaptWateringInterval(
        plant: Plant,
        feedback: WateringFeedback?,
        actualIntervalDays: Int,
        currentInterval: Int,
        now: Long = System.currentTimeMillis()
    ): Int {
        if (maybeApplyHistoryBootstrap(plant, feedback, now)) return currentInterval

        val recentFeedback = careLogRepository.getRecentWaterings(plant.id, limit = RECENT_WATERINGS_WINDOW)
            .map { it.wateringFeedback }
        val currentBase = currentAdaptiveBaseIntervalDays(plant, currentInterval)
        val frozen = WateringLifecycleReset.isFrozen(plant.wateringFreezeUntil, now)
        val result = CareSchedule.computeAdaptiveInterval(
            feedback = feedback,
            observedIntervalDays = deseasonalizedObservedIntervalDays(
                actualIntervalDays,
                plant.pinIntervalToBase,
                atDate = now.toLocalDate()
            ),
            currentBaseIntervalDays = currentBase,
            currentConfidence = plant.wateringConfidence,
            recentFeedback = recentFeedback,
            frozen = frozen
        )
        if (result.confidence != plant.wateringConfidence) {
            plantRepository.updatePlant(plant.copy(wateringConfidence = result.confidence, updatedAt = now))
        }
        wateringAdjustmentRepository.addAdjustment(
            WateringAdjustment(
                plantId = plant.id,
                triggeredAt = now,
                trigger = adjustmentTriggerFor(feedback, result.excludedFromBaseLearning, frozen),
                beforeIntervalDays = currentBase,
                afterIntervalDays = result.intervalDays
            )
        )
        return result.intervalDays
    }

    /**
     * The #571 REPOT-triggered lifecycle reset, reached from [quickLog]'s bulk-action REPOT path
     * (`BulkActionBar`) — extracted out of [quickLog] to stay under Detekt's
     * `CyclomaticComplexMethod` threshold. Gated on `adaptive_watering` (AC: neither lifecycle trigger
     * fires when the flag is off).
     */
    private suspend fun maybeApplyRepotReset(plant: Plant, careType: CareType, now: Long) {
        if (careType == CareType.REPOT && isAdaptiveWateringEnabled()) {
            WateringLifecycleReset.applyRepotReset(plant, now, plantRepository, wateringAdjustmentRepository)
        }
    }

    /**
     * The #571 cold-start bootstrap opportunity, evaluated on every WATER-log adaptive observation:
     * the plant's first-ever adaptive observation ([Plant.wateringConfidence] == `null`, using its
     * whole history), or a pending post-reset opportunity ([Plant.wateringResetAt] != `null`, using
     * only history at/after the freeze boundary). Returns `false` (no-op) when neither applies, or
     * when [WateringLifecycleReset.maybeBootstrap] doesn't find enough gaps yet.
     *
     * [feedback] is threaded through to [WateringLifecycleReset.BootstrapRequest] so a bootstrap
     * triggered by a late "Soil was still moist" observation ([WateringFeedback.TOO_SOON]) can't
     * undercut ADR-0033's "a late watering never shortens the interval" guarantee — see that
     * function's doc for why the median-of-history estimate needs this floor (#649 follow-up).
     */
    private suspend fun maybeApplyHistoryBootstrap(plant: Plant, feedback: WateringFeedback?, now: Long): Boolean {
        val boundaryMs = when {
            plant.wateringConfidence == null -> Long.MIN_VALUE
            plant.wateringResetAt != null -> plant.wateringFreezeUntil ?: plant.wateringResetAt
            else -> return false
        }
        val request = WateringLifecycleReset.BootstrapRequest(
            plant = plant,
            waterLogTimestampsMs = careLogRepository.getWaterLogTimestampsAscending(plant.id),
            boundaryMs = boundaryMs,
            seasonFn = seasonFnFor(plant),
            feedback = feedback
        )
        return WateringLifecycleReset.maybeBootstrap(request, plantRepository, wateringAdjustmentRepository, now)
    }

    /**
     * The season function [WateringLifecycleReset.maybeBootstrap]/[CareSchedule.bootstrapBaseInterval]
     * de-seasonalize each historical gap with — `{ 1.0 }` (a no-op) when [Plant.pinIntervalToBase] is
     * set or `SEASONAL_WATERING` is off, mirroring every other de-seasonalization call site in this
     * file ([deseasonalizedObservedIntervalDays]/[currentAdaptiveBaseIntervalDays]).
     */
    @Suppress("ReturnCount")
    private suspend fun seasonFnFor(plant: Plant): (LocalDate) -> Double {
        if (plant.pinIntervalToBase) return { 1.0 }
        val amplitude = dataStore.seasonalAmplitudeOnce()
        if (amplitude == 0.0) return { 1.0 }
        val hemisphere = SeasonalWatering.currentHemisphere()
        return { date -> SeasonalWatering.season(date, amplitude, hemisphere) }
    }

    private fun adjustmentTriggerFor(
        feedback: WateringFeedback?,
        excludedFromBaseLearning: Boolean,
        frozen: Boolean = false
    ): WateringAdjustmentTrigger = when {
        frozen -> WateringAdjustmentTrigger.FROZEN_POST_REPOT
        excludedFromBaseLearning -> WateringAdjustmentTrigger.WATER_NOT_ATTRIBUTED
        feedback == WateringFeedback.TOO_SOON -> WateringAdjustmentTrigger.WATER_TOO_SOON
        feedback == WateringFeedback.TOO_LATE -> WateringAdjustmentTrigger.WATER_TOO_LATE
        feedback == WateringFeedback.JUST_RIGHT -> WateringAdjustmentTrigger.WATER_JUST_RIGHT
        else -> WateringAdjustmentTrigger.WATER_NEUTRAL
    }

    private suspend fun isAdaptiveWateringEnabled(): Boolean =
        dataStore.data.first()[FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.ADAPTIVE_WATERING)]
            ?: FeatureFlagRegistry.ADAPTIVE_WATERING.default

    /**
     * "Interaction with Part 1" (#569): `observedBase = observedGap / season(dateOfGap)`, so a
     * seasonal correction isn't baked into [Plant.wateringConfidence] as a permanent thirst change.
     * A no-op when SEASONAL_WATERING is off or [pinIntervalToBase] is set — [CareSchedule]'s due-date
     * math never applies the seasonal curve for a pinned plant, so its observed gaps are already
     * flat and must not be seasonally corrected.
     *
     * [atDate] defaults to [nowProvider]'s real wall-clock date — the right choice for
     * [computeStillMoistAdaptiveInterval]'s two callers, neither of which can be backdated today — but
     * [adaptWateringInterval] passes its own `now` (possibly a backdated `loggedAt`, #654) explicitly, so
     * the observed gap is de-seasonalized using the day the watering actually happened, not the day the
     * app happens to be evaluating it.
     */
    @Suppress("ReturnCount")
    private suspend fun deseasonalizedObservedIntervalDays(
        actualIntervalDays: Int,
        pinIntervalToBase: Boolean,
        atDate: LocalDate = nowProvider().toLocalDate()
    ): Int {
        if (pinIntervalToBase) return actualIntervalDays
        val amplitude = dataStore.seasonalAmplitudeOnce()
        if (amplitude == 0.0) return actualIntervalDays
        return SeasonalWatering.deseasonalizeToDays(
            actualIntervalDays,
            atDate,
            amplitude,
            SeasonalWatering.currentHemisphere()
        )
    }

    /**
     * The watering-model input for `currentBaseIntervalDays` (#572, amending technical ADR-0021):
     * season-neutral, reading [Plant.wateringBaseIntervalDays] instead of the raw (possibly seasonally
     * stale) [configuredIntervalDays] whenever `SEASONAL_WATERING` is on and the plant isn't pinned.
     * Prior to this fix every call site fed the model a value that only ever changed on a manual
     * edit, silently diverging from what [CareSchedule.computeStatus] actually used for the due date.
     */
    @Suppress("ReturnCount")
    private suspend fun currentAdaptiveBaseIntervalDays(plant: Plant, configuredIntervalDays: Int): Int {
        if (plant.pinIntervalToBase) return configuredIntervalDays
        val amplitude = dataStore.seasonalAmplitudeOnce()
        if (amplitude == 0.0) return configuredIntervalDays
        return (plant.wateringBaseIntervalDays ?: configuredIntervalDays.toDouble()).roundToInt()
    }

    /**
     * Clears [Plant.wateringDueDateOverride] if [plantId] currently has one active, and returns the
     * freshly-fetched plant (cleared or not) so callers that go on to call [computeSuggestion] can
     * build any follow-up [PlantRepository.updatePlant] call off consistent, post-clear state rather
     * than the stale [Plant] snapshot they were originally passed (#614, same bug class as #612) —
     * without this, [adaptWateringInterval]'s own `.copy()` would silently resurrect the override it
     * just cleared. Returns `null` only if [plantId] no longer exists (a pre-existing race, not
     * introduced here); callers fall back to their own stale snapshot in that case.
     */
    @Suppress("ReturnCount")
    private suspend fun clearWateringOverrideIfActive(plantId: Long): Plant? {
        val p = plantRepository.getPlantById(plantId).first() ?: return null
        if (p.wateringDueDateOverride == null) return p
        val cleared = p.copy(wateringDueDateOverride = null, updatedAt = System.currentTimeMillis())
        plantRepository.updatePlant(cleared)
        return cleared
    }

    companion object {
        private const val RECENT_WATERINGS_WINDOW = 3

        /**
         * Floor and fallback for [suggestedStillMoistDeferralDays] (#586) — also the value #570's
         * `STILL_MOIST_DEFERRAL_DAYS` applied unconditionally, kept only as the "nothing to derive
         * from" case (adaptive watering off, no interval, no previous watering).
         */
        const val DEFAULT_STILL_MOIST_DEFERRAL_DAYS = 1
    }
}
