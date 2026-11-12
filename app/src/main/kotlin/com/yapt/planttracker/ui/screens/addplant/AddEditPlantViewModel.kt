package com.yapt.planttracker.ui.screens.addplant

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import com.yapt.planttracker.R
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.model.WateringAdjustment
import com.yapt.planttracker.domain.model.WateringAdjustmentTrigger
import com.yapt.planttracker.domain.schedule.DormancyWindow
import com.yapt.planttracker.domain.schedule.FertilizingSeason
import com.yapt.planttracker.domain.schedule.Hemisphere
import com.yapt.planttracker.domain.schedule.LiveWateringInterval
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.effectiveWateringAmplitude
import com.yapt.planttracker.domain.schedule.resolveWateringAmplitude
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeOnce
import com.yapt.planttracker.domain.usecase.WateringLifecycleReset
import com.yapt.planttracker.util.toLocalDate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// #571 added the room-change lifecycle-reset check alongside the existing manual-edit reset; splitting
// it out into its own class would scatter one cohesive save flow across files for no readability gain
// (cf. QuickLogUseCase/CareLogRepository's identical justification).
@Suppress("TooManyFunctions", "LongParameterList", "LargeClass")
class AddEditPlantViewModel(
    private val plantRepository: PlantRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    private val plantId: Long?,
    // Nullable + defaulted so the many existing tests constructing this VM directly don't all need
    // updating; null is treated the same as amplitude being Off (#569).
    private val dataStore: DataStore<Preferences>? = null,
    // Nullable + defaulted for the same reason as [dataStore] — `?.addAdjustment` calls below are
    // safe no-ops for tests that don't pass one (#572).
    private val wateringAdjustmentRepository: WateringAdjustmentRepository? = null,
    /** Drives the live seed; a test injecting this must inject a [nowProvider] on the same date. */
    private val hemisphere: () -> Hemisphere = SeasonalWatering::currentHemisphere,
    private val nowProvider: () -> Long = System::currentTimeMillis,
    /** Set when Add Plant was opened from Propagate's "New plant" offer (#960, product ADR-0073); add mode only. */
    private val propagatedFromId: Long? = null,
    /** Builds the prefilled "<Parent> (cutting)" name from the parent's name; the string lives in resources. */
    private val childNameFor: (parentName: String) -> String = { it },
    /** Mirror of the whole draft (technical ADR-0044); a fresh handle in tests that don't exercise restore. */
    private val savedStateHandle: SavedStateHandle = SavedStateHandle()
) : ViewModel() {

    val isEditMode: Boolean = plantId != null

    private val _form = MutableStateFlow(
        AddEditPlantFormState.fromJsonOrNull(savedStateHandle.get<String>(KEY_FORM)) ?: AddEditPlantFormState()
    )

    /** Every non-text input plus the wizard step; changed only through the setters below. */
    val uiState: StateFlow<AddEditPlantFormState> = _form.asStateFlow()

    // Typed text stays synchronous Compose state, not part of [uiState]: feeding a TextField from an
    // asynchronously collected StateFlow can drop keystrokes or jump the cursor (same exception as
    // PlantListViewModel.searchQueryText, technical ADR-0044). Each setter also mirrors into the handle.
    private var nameState by mutableStateOf(savedStateHandle.get<String>(KEY_NAME).orEmpty())
    val name: String get() = nameState
    private var speciesState by mutableStateOf(savedStateHandle.get<String>(KEY_SPECIES).orEmpty())
    val species: String get() = speciesState
    private var roomState by mutableStateOf(savedStateHandle.get<String>(KEY_ROOM).orEmpty())
    val room: String get() = roomState
    private var notesState by mutableStateOf(savedStateHandle.get<String>(KEY_NOTES).orEmpty())
    val notes: String get() = notesState

    /** The "adjusted for winter" hint describes the stored strength, so it hides once the form picks another one. */
    val showSeasonalAdjustment: Boolean
        get() = seasonalAdjustment != null && _form.value.seasonalHintApplies

    /** Edit mode only: non-null when today's seasonal value differs from the rounded base and the plant isn't dormant today. */
    var seasonalAdjustment by mutableStateOf<LiveWateringInterval.SeasonalAdjustment?>(null)
        private set

    /** Whether the plant is currently hidden (#915); only meaningful in edit mode. Hide/Unhide write at once, not on Save. */
    var isHidden by mutableStateOf(false)
        private set

    /** Add mode only: the parent's name for the read-only "Propagated from" line (#960); see [uiState]. */
    val propagatedFromName: String? get() = _form.value.propagatedFromName

    /** True when there is anything to lose by leaving the wizard (drives the discard dialog). */
    val hasUnsavedInput: Boolean
        get() = name.isNotBlank() || species.isNotBlank() || room.isNotBlank() || notes.isNotBlank() ||
            _form.value.hasCareOrPhotoInput

    val rooms: StateFlow<List<String>> = plantRepository.getAllRooms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events

    init {
        if (plantId == null && propagatedFromId != null && !_form.value.prefillDone) {
            viewModelScope.launch {
                plantRepository.getPlantById(propagatedFromId).first()?.let { parent ->
                    // The only suspending step, so every guarded write below sees the user's edits so far.
                    val live = LiveWateringInterval.compute(
                        parent,
                        nowProvider().toLocalDate(),
                        dataStore?.seasonalAmplitudeOnce() ?: 0.0,
                        hemisphere()
                    )
                    if (name.isEmpty()) setName(childNameFor(parent.name))
                    if (species.isEmpty()) setSpecies(parent.species.orEmpty())
                    if (room.isEmpty()) setRoom(parent.room.orEmpty())
                    mutate { it.withParentCarePrefill(parent, live) }
                }
            }
        }
        plantId?.let { id ->
            viewModelScope.launch {
                plantRepository.getPlantById(id).first()?.let { plant -> loadForEdit(plant) }
            }
        }
    }

    private suspend fun loadForEdit(plant: Plant) {
        val today = nowProvider().toLocalDate()
        val live = LiveWateringInterval.compute(
            plant,
            today,
            dataStore?.seasonalAmplitudeOnce() ?: 0.0,
            hemisphere()
        )
        isHidden = plant.hiddenAt != null
        val dormantToday = DormancyWindow.isDormant(
            today.monthValue,
            plant.dormancyStartMonth,
            plant.dormancyEndMonth
        )
        seasonalAdjustment = live?.adjustment.takeUnless { dormantToday }
        // A restored draft holds the user's edits: the plant row only refreshes the display-only fields above.
        if (_form.value.editLoaded) return
        setName(plant.name)
        setSpecies(plant.species ?: "")
        setRoom(plant.room ?: "")
        setNotes(plant.notes ?: "")
        val shownWatering = plant.wateringIntervalDays?.let { live?.effectiveDays ?: it }
        mutate { it.withPlantLoaded(plant, shownWatering) }
    }

    private fun mutate(transform: (AddEditPlantFormState) -> AddEditPlantFormState) {
        val updated = _form.updateAndGet(transform)
        savedStateHandle[KEY_FORM] = updated.toJson()
    }

    fun setName(value: String) {
        nameState = value
        savedStateHandle[KEY_NAME] = value
    }

    fun setSpecies(value: String) {
        speciesState = value
        savedStateHandle[KEY_SPECIES] = value
    }

    fun setRoom(value: String) {
        roomState = value
        savedStateHandle[KEY_ROOM] = value
    }

    fun setNotes(value: String) {
        notesState = value
        savedStateHandle[KEY_NOTES] = value
    }

    fun addPhoto(uri: String) {
        mutate { form ->
            form.copy(
                pendingPhotos = if (uri in form.pendingPhotos) form.pendingPhotos else form.pendingPhotos + uri,
                coverPhotoUri = uri
            )
        }
    }

    fun setWateringIntervalEnabled(enabled: Boolean) = mutate { it.copy(wateringIntervalEnabled = enabled) }

    fun setWateringIntervalDays(days: Int) = mutate { it.copy(wateringIntervalDays = days) }

    fun setFertilizingIntervalEnabled(enabled: Boolean) = mutate { it.copy(fertilizingIntervalEnabled = enabled) }

    fun setFertilizingIntervalDays(days: Int) = mutate { it.copy(fertilizingIntervalDays = days) }

    fun setUseLiquidFertilizer(enabled: Boolean) = mutate { it.copy(useLiquidFertilizer = enabled) }

    fun setRepottingIntervalEnabled(enabled: Boolean) = mutate { it.copy(repottingIntervalEnabled = enabled) }

    fun setRepottingIntervalMonths(months: Int) = mutate { it.copy(repottingIntervalMonths = months) }

    /**
     * Per-plant opt-out from the seasonal curve (#569). One of the three choices of the strength control
     * (#700, product ADR-0084), so turning it on clears the custom strength.
     */
    fun setPinIntervalToBase(pinned: Boolean) = mutate {
        it.copy(
            pinIntervalToBase = pinned,
            wateringAmplitudeOverride = if (pinned) null else it.wateringAmplitudeOverride
        )
    }

    /** Per-plant custom strength (#700); choosing one clears the pin, `null` = follow the app default. */
    fun setWateringAmplitudeOverride(amplitude: SeasonalAmplitude?) = mutate {
        it.copy(
            wateringAmplitudeOverride = amplitude,
            pinIntervalToBase = if (amplitude != null) false else it.pinIntervalToBase
        )
    }

    /** The strength control's "App default" choice: neither pinned nor a custom strength. */
    fun selectAppDefaultStrength() = mutate { it.copy(pinIntervalToBase = false, wateringAmplitudeOverride = null) }

    fun setDormancyWindow(startMonth: Int?, endMonth: Int?, dormantIntervalDays: Int? = null) {
        require(
            (startMonth == null && endMonth == null) ||
                (startMonth != null && endMonth != null && startMonth in 1..12 && endMonth in 1..12)
        )
        require(dormantIntervalDays == null || DormancyWindow.validWateringInterval(dormantIntervalDays) != null)
        mutate {
            it.copy(
                dormancyStartMonth = startMonth,
                dormancyEndMonth = endMonth,
                dormantWateringIntervalDays = if (startMonth == null || endMonth == null) null else dormantIntervalDays
            )
        }
    }

    /**
     * [FertilizingSeasonsSelector] reports the tapped season, not a full replacement set (#804) — the
     * toggle is applied to the latest [uiState] inside one atomic update. At least one season must
     * always stay active — a toggle that would empty the set is rejected, keeping the previous
     * selection (#795).
     */
    fun toggleFertilizingSeason(season: FertilizingSeason) =
        mutate { it.copy(fertilizingSeasons = toggledSeasons(it.fertilizingSeasons, season)) }

    /** Same toggle contract as [toggleFertilizingSeason], for the repotting reminder's preferred seasons (#809). */
    fun toggleRepottingSeason(season: FertilizingSeason) =
        mutate { it.copy(repottingSeasons = toggledSeasons(it.repottingSeasons, season)) }

    /** Advances the wizard; a blank name keeps step 2 and explains why. */
    fun goNext() {
        val current = _form.value.step
        if (current == AddPlantStep.DETAILS && name.isBlank()) {
            viewModelScope.launch { _events.emit(Event.ValidationError(R.string.add_plant_name_required)) }
            return
        }
        val next = AddPlantStep.entries.getOrNull(current.ordinal + 1) ?: return
        mutate { it.copy(step = next) }
    }

    /** Steps back; `false` means there is no earlier step (first step, or edit mode) and the caller leaves. */
    fun goBack(): Boolean {
        val previous = AddPlantStep.entries.getOrNull(_form.value.step.ordinal - 1)
        if (isEditMode || previous == null) return false
        mutate { it.copy(step = previous) }
        return true
    }

    fun goToStep(step: AddPlantStep) = mutate { it.copy(step = step) }

    /**
     * Set once a valid save starts, so a second tap on Add plant / Save while the first write is still
     * running can't create a duplicate plant (the screen leaves on [Event.Saved]). Cleared only if the
     * save throws, so the user can retry.
     */
    private var saveInFlight = false

    fun save() {
        if (saveInFlight) return
        if (name.isBlank()) {
            if (!isEditMode) goToStep(AddPlantStep.DETAILS)
            viewModelScope.launch { _events.emit(Event.ValidationError(R.string.add_plant_name_required)) }
            return
        }
        val form = _form.value
        saveInFlight = true
        viewModelScope.launch {
            var saved = false
            try {
                persist(form)
                saved = true
            } finally {
                if (!saved) saveInFlight = false
            }
        }
    }

    private suspend fun persist(form: AddEditPlantFormState) {
        val now = nowProvider()
        val newWateringIntervalDays = if (form.wateringIntervalEnabled) form.wateringIntervalDays else null
        val intervalChanged = newWateringIntervalDays != form.seededWateringIntervalDays
        val plant = Plant(
            id = plantId ?: 0,
            name = name.trim(),
            species = species.trim().ifBlank { null },
            room = room.trim().ifBlank { null },
            notes = notes.trim().ifBlank { null },
            coverPhotoUri = form.coverPhotoUri,
            wateringIntervalDays = newWateringIntervalDays,
            fertilizingIntervalDays = if (form.fertilizingIntervalEnabled) form.fertilizingIntervalDays else null,
            repottingIntervalDays = if (form.repottingIntervalEnabled) {
                form.repottingIntervalMonths * DAYS_PER_MONTH
            } else {
                null
            },
            createdAt = if (isEditMode) 0L else now,
            updatedAt = now,
            useLiquidFertilizer = form.useLiquidFertilizer,
            pinIntervalToBase = form.pinIntervalToBase,
            wateringAmplitudeOverride = form.wateringAmplitudeOverride,
            dormancyStartMonth = form.dormancyStartMonth,
            dormancyEndMonth = form.dormancyEndMonth,
            fertilizingSeasons = form.fertilizingSeasons,
            repottingSeasons = form.repottingSeasons,
            dormantWateringIntervalDays = form.dormantWateringIntervalDays
        )
        if (isEditMode) {
            saveEdit(form, plant, newWateringIntervalDays, intervalChanged, now)
        } else {
            saveNew(form, plant, newWateringIntervalDays, now)
        }
    }

    /** [deseasonalizedNewBase] paired with the [Plant.wateringBaseIntervalDays] value to persist. */
    private data class RecomputedBase(val deseasonalizedNewBase: Double?, val wateringBaseIntervalDays: Double?)

    /**
     * Mirrors the confidence reset in [saveEdit]: de-seasonalize a newly typed interval to today
     * (#569) so the effective interval doesn't jump on the next due-date computation. Unchanged when
     * amplitude is Off, the plant is pinned, or the interval wasn't touched — the prior base
     * (if any) is preserved rather than cleared. Extracted out of [saveEdit] to stay under Detekt's
     * `CyclomaticComplexMethod` threshold.
     */
    private suspend fun recomputeWateringBase(
        form: AddEditPlantFormState,
        existing: Plant?,
        newWateringIntervalDays: Int?,
        intervalChanged: Boolean,
        now: Long
    ): RecomputedBase {
        val baseShouldBeRecomputed = newWateringIntervalDays != null && intervalChanged && !form.pinIntervalToBase
        if (!baseShouldBeRecomputed) return RecomputedBase(null, existing?.wateringBaseIntervalDays)
        val deseasonalizedNewBase = deseasonalizedBaseOrNull(form, newWateringIntervalDays!!, now)
        return RecomputedBase(deseasonalizedNewBase, deseasonalizedNewBase ?: existing?.wateringBaseIntervalDays)
    }

    private suspend fun saveEdit(
        form: AddEditPlantFormState,
        plant: Plant,
        newWateringIntervalDays: Int?,
        intervalChanged: Boolean,
        now: Long
    ) {
        val existing = plantRepository.getPlantById(plantId!!).first()
        // A room change is a lifecycle reset too (#571), except blank/empty -> filled for the first
        // time (data entry, not a physical move).
        val roomChangeResetFires = existing != null &&
            WateringLifecycleReset.roomChangeTriggersReset(existing.room, plant.room)
        // An unprompted edit to the watering interval on this screen is a full confidence
        // reset (#568) — distinct from fine-tuning a number inside the product ADR-0006 suggestion
        // dialog, which never routes through here.
        val wateringConfidence = if (intervalChanged || roomChangeResetFires) 0 else existing?.wateringConfidence
        val (deseasonalizedNewBase, wateringBaseIntervalDays) =
            recomputeWateringBase(form, existing, newWateringIntervalDays, intervalChanged, now)
        plantRepository.updatePlant(
            plant.copy(
                wateringIntervalDays = literalToStore(form, existing, newWateringIntervalDays, intervalChanged),
                createdAt = existing?.createdAt ?: now,
                wateringDueDateOverride = existing?.wateringDueDateOverride,
                wateringDueDateOverrideMadeAt = existing?.wateringDueDateOverrideMadeAt,
                // The one-off repot plan isn't edited on this screen (Plant Detail's Repot tab owns it):
                // carry the stored values over so an unrelated edit can't silently wipe it (#809).
                repotPlanSeasonStartAt = existing?.repotPlanSeasonStartAt,
                repotPlanMadeAt = existing?.repotPlanMadeAt,
                // Hide/Unhide own this column through column-specific writes (#915):
                // an unrelated edit must never unhide a plant.
                hiddenAt = existing?.hiddenAt,
                wateringConfidence = wateringConfidence,
                wateringBaseIntervalDays = wateringBaseIntervalDays,
                // The parent link is changed only from the Propagate tab's Family card (#960): an unrelated
                // edit must carry it over, like hiddenAt above.
                propagatedFromId = existing?.propagatedFromId,
                // Room-change reset (#571): a fresh wateringResetAt anchor for the post-reset bootstrap
                // check, but deliberately no freeze window — a moved plant relies purely on the normal
                // confidence-0 gain ramp-up, unlike a REPOT reset. A manual interval edit alone leaves
                // any existing anchor/freeze untouched (out of this feature's scope).
                wateringResetAt = if (roomChangeResetFires) now else existing?.wateringResetAt,
                wateringFreezeUntil = if (roomChangeResetFires) null else existing?.wateringFreezeUntil
            )
        )
        if (intervalChanged && newWateringIntervalDays != null) {
            logManualEditAdjustment(form, existing, newWateringIntervalDays, deseasonalizedNewBase, now)
        }
        if (roomChangeResetFires) {
            logRoomChangeResetAdjustment(existing, now)
        }
        savePendingPhotos(form, plantId!!, now)
        _events.emit(Event.Saved(plantId))
    }

    /**
     * An untouched slider shows today's live value, which is not necessarily the stored literal. Writing it
     * back would re-seed the base fallback of a plant that never recorded one, so the stored literal stays
     * (#928, product ADR-0070). Turning the pin on is the exception: a pinned plant's interval is the literal,
     * so it pins the value the slider shows.
     */
    private fun literalToStore(
        form: AddEditPlantFormState,
        existing: Plant?,
        newWateringIntervalDays: Int?,
        intervalChanged: Boolean
    ): Int? {
        val newlyPinned = form.pinIntervalToBase && existing?.pinIntervalToBase != true
        return if (intervalChanged || newlyPinned) newWateringIntervalDays else existing?.wateringIntervalDays
    }

    /**
     * When the strength and the interval change in the same save, before and after can span two strengths.
     * #584 review: logs base-space before/after, not the literal typed value — matches
     * `PlantDetailViewModel.setWateringInterval()`'s equivalent `MANUAL_EDIT` fix. Extracted out of
     * [saveEdit] to keep it under Detekt's `LongMethod`/`CyclomaticComplexMethod` thresholds.
     */
    private suspend fun logManualEditAdjustment(
        form: AddEditPlantFormState,
        existing: Plant?,
        newWateringIntervalDays: Int,
        deseasonalizedNewBase: Double?,
        now: Long
    ) {
        val loggedBefore = currentBaseIntervalDaysOrLiteral(
            existing,
            existing?.wateringIntervalDays ?: newWateringIntervalDays
        )
        val loggedAfter = if (!form.pinIntervalToBase) {
            (deseasonalizedNewBase ?: newWateringIntervalDays.toDouble()).roundToInt()
        } else {
            newWateringIntervalDays
        }
        // #928: gated on a base-space change, not on the typed literal differing from the stored one.
        if (loggedAfter == loggedBefore) return
        wateringAdjustmentRepository?.recordManualEdit(
            WateringAdjustment(
                plantId = plantId!!,
                triggeredAt = now,
                trigger = WateringAdjustmentTrigger.MANUAL_EDIT,
                beforeIntervalDays = loggedBefore,
                afterIntervalDays = loggedAfter
            )
        )
    }

    /** A room-change reset changes confidence, not the interval — `before == after`, same as [WateringLifecycleReset.applyRepotReset]. */
    private suspend fun logRoomChangeResetAdjustment(existing: Plant?, now: Long) {
        val beforeAfter = currentBaseIntervalDaysOrLiteral(existing, existing?.wateringIntervalDays ?: 0)
        wateringAdjustmentRepository?.addAdjustment(
            WateringAdjustment(
                plantId = plantId!!,
                triggeredAt = now,
                trigger = WateringAdjustmentTrigger.ROOM_CHANGE_RESET,
                beforeIntervalDays = beforeAfter,
                afterIntervalDays = beforeAfter
            )
        )
    }

    private suspend fun saveNew(form: AddEditPlantFormState, plant: Plant, newWateringIntervalDays: Int?, now: Long) {
        val wateringBaseIntervalDays = if (newWateringIntervalDays != null && !form.pinIntervalToBase) {
            deseasonalizedBaseOrNull(form, newWateringIntervalDays, now)
        } else {
            null
        }
        // Re-read the parent: it may have been deleted since the form opened, and a dangling id would fail the FK.
        val parentId = form.linkedParentId?.takeIf { plantRepository.getPlantById(it).first() != null }
        val newId = plantRepository.addPlant(
            plant.copy(wateringBaseIntervalDays = wateringBaseIntervalDays, propagatedFromId = parentId)
        )
        savePendingPhotos(form, newId, now)
        _events.emit(Event.Saved(newId))
    }

    /**
     * `null` when [dataStore] is null (test-convenience seam) or the form's effective amplitude reads Off.
     */
    private suspend fun deseasonalizedBaseOrNull(form: AddEditPlantFormState, intervalDays: Int, now: Long): Double? {
        val store = dataStore ?: return null
        val amplitude = resolveWateringAmplitude(
            form.pinIntervalToBase,
            form.wateringAmplitudeOverride,
            store.seasonalAmplitudeOnce()
        )
        return if (amplitude == 0.0) {
            null
        } else {
            SeasonalWatering.deseasonalize(
                intervalDays.toDouble(),
                now.toLocalDate(),
                amplitude,
                hemisphere()
            )
        }
    }

    /**
     * The pre-edit base-space reference for [WateringAdjustment] row units (#584 review) — mirrors
     * [com.yapt.planttracker.ui.screens.plantdetail.PlantDetailViewModel]'s
     * `currentBaseIntervalDaysOrLiteral()`. Collapses to [literal] itself when [pinned] or
     * amplitude is Off, matching every other read of [Plant.wateringBaseIntervalDays].
     */
    @Suppress("ReturnCount")
    private suspend fun currentBaseIntervalDaysOrLiteral(existing: Plant?, literal: Int): Int {
        val store = dataStore ?: return literal
        val globalAmplitude = store.seasonalAmplitudeOnce()
        val amplitude = existing?.effectiveWateringAmplitude(globalAmplitude) ?: globalAmplitude
        if (amplitude == 0.0) return literal
        return (existing?.wateringBaseIntervalDays ?: literal.toDouble()).roundToInt()
    }

    fun deletePlant() {
        plantId ?: return
        viewModelScope.launch {
            plantRepository.getPlantById(plantId).first()?.let { plant ->
                plantRepository.archivePlant(plant.id)
                _events.emit(Event.ArchivedForUndo(plant.id, plant.name))
            }
        }
    }

    fun hidePlant() {
        plantId ?: return
        viewModelScope.launch {
            plantRepository.getPlantById(plantId).first()?.let { plant ->
                plantRepository.hidePlant(plant.id)
                isHidden = true
                _events.emit(Event.HiddenForUndo(plant.id, plant.name))
            }
        }
    }

    fun unhidePlant() {
        plantId ?: return
        viewModelScope.launch {
            plantRepository.unhidePlant(plantId)
            isHidden = false
            _events.emit(Event.Unhidden)
        }
    }

    private suspend fun savePendingPhotos(form: AddEditPlantFormState, plantId: Long, now: Long) {
        if (form.pendingPhotos.isEmpty()) return
        plantPhotoRepository.addPhotos(
            form.pendingPhotos.map { PlantPhoto(plantId = plantId, uri = it, capturedAt = now) }
        )
    }

    sealed class Event {
        data class Saved(val plantId: Long) : Event()
        data class ArchivedForUndo(val plantId: Long, val plantName: String) : Event()
        data class HiddenForUndo(val plantId: Long, val plantName: String) : Event()
        object Unhidden : Event()
        data class ValidationError(@StringRes val messageRes: Int) : Event()
    }

    companion object {
        /** Whole-month approximation used to convert the repotting interval to/from stored days. */
        const val DAYS_PER_MONTH = 30

        /**
         * Stored days → the months shown on the slider, rounded to the nearest whole month and
         * clamped into the slider's range. Values written by this screen are always exact multiples
         * of [DAYS_PER_MONTH], but a restored backup (or a value from a future/other client) need
         * not be — rounding keeps 405 days reading as 14 months rather than truncating to 13.
         */
        fun daysToRepottingMonths(days: Int): Int =
            ((days + DAYS_PER_MONTH / 2) / DAYS_PER_MONTH)
                .coerceIn(MIN_REPOTTING_MONTHS, MAX_REPOTTING_MONTHS)

        /** Roughly a quarter — the shortest cadence that makes sense for a fast-growing young plant. */
        const val MIN_REPOTTING_MONTHS = 3
        const val MAX_REPOTTING_MONTHS = 36
        const val DEFAULT_REPOTTING_MONTHS = 12
        private const val KEY_FORM = "add_edit_plant_form"
        private const val KEY_NAME = "add_edit_plant_name"
        private const val KEY_SPECIES = "add_edit_plant_species"
        private const val KEY_ROOM = "add_edit_plant_room"
        private const val KEY_NOTES = "add_edit_plant_notes"
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val plantRepository: PlantRepository,
        private val plantPhotoRepository: PlantPhotoRepository,
        private val plantId: Long?,
        private val dataStore: DataStore<Preferences>? = null,
        private val wateringAdjustmentRepository: WateringAdjustmentRepository? = null,
        private val propagatedFromId: Long? = null,
        private val childNameFor: (parentName: String) -> String = { it }
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
            build(extras.createSavedStateHandle()) as T

        override fun <T : ViewModel> create(modelClass: Class<T>): T = build(SavedStateHandle()) as T

        private fun build(savedStateHandle: SavedStateHandle) =
            AddEditPlantViewModel(
                plantRepository,
                plantPhotoRepository,
                plantId,
                dataStore,
                wateringAdjustmentRepository,
                propagatedFromId = propagatedFromId,
                childNameFor = childNameFor,
                savedStateHandle = savedStateHandle
            )
    }
}
