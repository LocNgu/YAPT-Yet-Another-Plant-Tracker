package com.yapt.planttracker.ui.screens.addplant

import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.DormancyWindow
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.LiveWateringInterval
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal const val DEFAULT_WATERING_INTERVAL_DAYS = 7
internal const val DEFAULT_FERTILIZING_INTERVAL_DAYS = 30
private val ALL_SEASONS = FertilizingSeason.entries.toSet()

enum class AddPlantStep {
    PHOTO,
    DETAILS,
    CARE,
    REVIEW;

    val number: Int get() = ordinal + 1

    companion object {
        val COUNT: Int = entries.size
    }
}

/**
 * Every non-text input of the Add/Edit Plant form plus the restore bookkeeping, as one immutable value
 * (technical ADR-0044). The ViewModel mirrors it into its `SavedStateHandle` as JSON on every change, so
 * the draft and the wizard [step] survive process death. Typed text lives outside it as synchronous
 * Compose state (name, species, room, notes).
 */
@Serializable
@Suppress("LongParameterList")
data class AddEditPlantFormState(
    val step: AddPlantStep = AddPlantStep.PHOTO,
    val coverPhotoUri: String? = null,
    val pendingPhotos: List<String> = emptyList(),
    val wateringIntervalDays: Int = DEFAULT_WATERING_INTERVAL_DAYS,
    val wateringIntervalEnabled: Boolean = false,
    val fertilizingIntervalDays: Int = DEFAULT_FERTILIZING_INTERVAL_DAYS,
    val fertilizingIntervalEnabled: Boolean = false,
    val fertilizingSeasons: Set<FertilizingSeason> = ALL_SEASONS,
    val useLiquidFertilizer: Boolean = false,
    /** Repotting is scheduled in months (product ADR-0022); persisted as `months * DAYS_PER_MONTH` days. */
    val repottingIntervalMonths: Int = AddEditPlantViewModel.DEFAULT_REPOTTING_MONTHS,
    val repottingIntervalEnabled: Boolean = false,
    /** Kept even while the reminder is off so toggling it off and on remembers the choice (#809). */
    val repottingSeasons: Set<FertilizingSeason> = ALL_SEASONS,
    val pinIntervalToBase: Boolean = false,
    val wateringAmplitudeOverride: SeasonalAmplitude? = null,
    val dormancyStartMonth: Int? = null,
    val dormancyEndMonth: Int? = null,
    val dormantWateringIntervalDays: Int? = null,
    /** The slider seed of edit mode (#928, product ADR-0070): set once at load; `null` for a new plant. */
    val seededWateringIntervalDays: Int? = null,
    val loadedAmplitudeOverride: SeasonalAmplitude? = null,
    val linkedParentId: Long? = null,
    val propagatedFromName: String? = null,
    /** Edit mode: the plant row was copied into the form, so a restored form must not be overwritten by it. */
    val editLoaded: Boolean = false,
    /** Propagate: the parent's care settings were applied, so a restored form must not get them again. */
    val prefillDone: Boolean = false
) {
    /** Whether the "adjusted for winter" hint still describes the strength the form has chosen. */
    val seasonalHintApplies: Boolean
        get() = !pinIntervalToBase && wateringAmplitudeOverride == loadedAmplitudeOverride

    /** Anything the user could have entered here, ignoring the wizard step and the bookkeeping fields. */
    val hasCareOrPhotoInput: Boolean
        get() = copy(
            step = AddPlantStep.PHOTO,
            seededWateringIntervalDays = null,
            loadedAmplitudeOverride = null,
            linkedParentId = null,
            propagatedFromName = null,
            editLoaded = false,
            prefillDone = false
        ) != AddEditPlantFormState()

    fun toJson(): String = JSON.encodeToString(serializer(), this)

    /**
     * A new plant from a propagation starts with the parent's care settings (#960, product ADR-0073), all
     * still editable. Each one is written only while it holds its untouched default, so a slow parent read
     * never overwrites what the user already changed; the caller applies this inside one atomic update.
     * Copied: the dormancy window and cadence, the fertilizing reminder (on/off, interval, seasons,
     * liquid), the repotting seasons, the pin switch and the watering reminder with the parent's live
     * effective interval for today ([live]), never the stored literal. Not copied: the repotting reminder
     * and plan, learned watering state, notes, photos, issues, hidden/archived.
     */
    fun withParentCarePrefill(parent: Plant, live: LiveWateringInterval?): AddEditPlantFormState {
        var s = copy(linkedParentId = parent.id, propagatedFromName = parent.name, prefillDone = true)
        if (!s.wateringIntervalEnabled && s.wateringIntervalDays == DEFAULT_WATERING_INTERVAL_DAYS && live != null) {
            s = s.copy(wateringIntervalDays = live.effectiveDays, wateringIntervalEnabled = true)
        }
        s = s.prefillFertilizing(parent)
        if (s.repottingSeasons == ALL_SEASONS) s = s.copy(repottingSeasons = parent.repottingSeasons)
        if (!s.pinIntervalToBase && parent.pinIntervalToBase) {
            s = s.copy(pinIntervalToBase = true, wateringAmplitudeOverride = null)
        }
        if (!s.pinIntervalToBase && s.wateringAmplitudeOverride == null) {
            s = s.copy(wateringAmplitudeOverride = parent.wateringAmplitudeOverride)
        }
        val dormancyUntouched = s.dormancyStartMonth == null && s.dormancyEndMonth == null &&
            s.dormantWateringIntervalDays == null
        val parentStart = parent.dormancyStartMonth
        val parentEnd = parent.dormancyEndMonth
        if (dormancyUntouched && parentStart != null && parentEnd != null) {
            s = s.copy(
                dormancyStartMonth = parentStart,
                dormancyEndMonth = parentEnd,
                dormantWateringIntervalDays = DormancyWindow.validWateringInterval(parent.dormantWateringIntervalDays)
            )
        }
        return s
    }

    /** Edit mode: copies the stored plant into the form once; [shownWatering] is today's live interval (#928). */
    fun withPlantLoaded(plant: Plant, shownWatering: Int?): AddEditPlantFormState {
        val override = plant.wateringAmplitudeOverride.takeUnless { plant.pinIntervalToBase }
        return copy(
            coverPhotoUri = plant.coverPhotoUri,
            wateringIntervalDays = shownWatering ?: wateringIntervalDays,
            wateringIntervalEnabled = shownWatering != null || wateringIntervalEnabled,
            seededWateringIntervalDays = shownWatering ?: seededWateringIntervalDays,
            fertilizingIntervalDays = plant.fertilizingIntervalDays ?: fertilizingIntervalDays,
            fertilizingIntervalEnabled = plant.fertilizingIntervalDays != null || fertilizingIntervalEnabled,
            fertilizingSeasons = plant.fertilizingSeasons,
            repottingIntervalMonths = plant.repottingIntervalDays?.let(AddEditPlantViewModel::daysToRepottingMonths)
                ?: repottingIntervalMonths,
            repottingIntervalEnabled = plant.repottingIntervalDays != null || repottingIntervalEnabled,
            repottingSeasons = plant.repottingSeasons,
            useLiquidFertilizer = plant.useLiquidFertilizer,
            pinIntervalToBase = plant.pinIntervalToBase,
            wateringAmplitudeOverride = override,
            loadedAmplitudeOverride = override,
            dormancyStartMonth = plant.dormancyStartMonth,
            dormancyEndMonth = plant.dormancyEndMonth,
            dormantWateringIntervalDays = DormancyWindow.validWateringInterval(plant.dormantWateringIntervalDays),
            editLoaded = true
        )
    }

    private fun prefillFertilizing(parent: Plant): AddEditPlantFormState {
        var s = this
        val parentFertilizing = parent.fertilizingIntervalDays
        if (!s.fertilizingIntervalEnabled && s.fertilizingIntervalDays == DEFAULT_FERTILIZING_INTERVAL_DAYS &&
            parentFertilizing != null
        ) {
            s = s.copy(fertilizingIntervalDays = parentFertilizing, fertilizingIntervalEnabled = true)
        }
        if (s.fertilizingSeasons == ALL_SEASONS) s = s.copy(fertilizingSeasons = parent.fertilizingSeasons)
        if (!s.useLiquidFertilizer) s = s.copy(useLiquidFertilizer = parent.useLiquidFertilizer)
        return s
    }

    companion object {
        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        fun fromJsonOrNull(json: String?): AddEditPlantFormState? =
            json?.let { runCatching { JSON.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

internal fun toggledSeasons(current: Set<FertilizingSeason>, season: FertilizingSeason): Set<FertilizingSeason> {
    val newSeasons = if (season in current) current - season else current + season
    return if (newSeasons.isEmpty()) current else newSeasons
}
