package com.yapt.planttracker.ui.screens.addplant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlagRegistry
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.PlantPhoto
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeOnce
import com.yapt.planttracker.util.toLocalDate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class AddEditPlantViewModel(
    private val plantRepository: PlantRepository,
    private val plantPhotoRepository: PlantPhotoRepository,
    private val plantId: Long?,
    // Nullable + defaulted so the many existing tests constructing this VM directly don't all need
    // updating; null is treated the same as SEASONAL_WATERING being off (#569).
    private val dataStore: DataStore<Preferences>? = null
) : ViewModel() {

    val isEditMode: Boolean = plantId != null

    var name by mutableStateOf("")
    var species by mutableStateOf("")
    var room by mutableStateOf("")
    var notes by mutableStateOf("")
    var coverPhotoUri by mutableStateOf<String?>(null)
    var wateringIntervalDays by mutableIntStateOf(7)
    var wateringIntervalEnabled by mutableStateOf(false)
    var fertilizingIntervalDays by mutableIntStateOf(30)
    var fertilizingIntervalEnabled by mutableStateOf(false)
    var useLiquidFertilizer by mutableStateOf(false)

    /**
     * Repotting is scheduled in **months**, not days — nobody repots on a day-precise cadence, and
     * the sane floor is roughly a quarter (fast-growing young plants) with most plants on a 1–3 year
     * cycle. Persisted as `months * DAYS_PER_MONTH` in `Plant.repottingIntervalDays` so the schedule
     * logic and the DB schema stay day-based (see product ADR-0022).
     */
    var repottingIntervalMonths by mutableIntStateOf(DEFAULT_REPOTTING_MONTHS)
    var repottingIntervalEnabled by mutableStateOf(false)

    /** Per-plant opt-out from the seasonal curve (#569) — surfaced only while [seasonalWateringEnabled]. */
    var pinIntervalToBase by mutableStateOf(false)

    /**
     * Whether the amplitude picker / "Pin interval" switch should render at all — mirrors
     * [com.yapt.planttracker.ui.screens.plantdetail.PlantDetailViewModel.tabsEnabled]'s pattern of
     * reading the flag straight off [dataStore] rather than taking a `FeatureFlags` constructor
     * param, to stay under Detekt's `LongParameterList` threshold.
     */
    val seasonalWateringEnabled: StateFlow<Boolean> = (dataStore?.data ?: emptyFlow())
        .map { prefs ->
            prefs[FeatureFlags.preferenceKeyFor(FeatureFlagRegistry.SEASONAL_WATERING)]
                ?: FeatureFlagRegistry.SEASONAL_WATERING.default
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * The watering interval as loaded from the DB (or `null` for a new plant), used to detect an
     * unprompted edit on this screen — as opposed to applying an ADR-0006 suggestion, which never
     * routes through this screen. An edit here is a full [Plant.wateringConfidence] reset (#568):
     * the user is asserting a new baseline (moved the plant, repotted, changed pot size), unlike
     * fine-tuning the number inside the suggestion dialog itself.
     */
    private var loadedWateringIntervalDays: Int? = null

    val pendingPhotos = mutableStateListOf<String>()

    val rooms: StateFlow<List<String>> = plantRepository.getAllRooms()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events

    init {
        plantId?.let { id ->
            viewModelScope.launch {
                plantRepository.getPlantById(id).first()?.let { plant ->
                    name = plant.name
                    species = plant.species ?: ""
                    room = plant.room ?: ""
                    notes = plant.notes ?: ""
                    coverPhotoUri = plant.coverPhotoUri
                    plant.wateringIntervalDays?.let {
                        wateringIntervalDays = it
                        wateringIntervalEnabled = true
                    }
                    loadedWateringIntervalDays = plant.wateringIntervalDays
                    plant.fertilizingIntervalDays?.let {
                        fertilizingIntervalDays = it
                        fertilizingIntervalEnabled = true
                    }
                    plant.repottingIntervalDays?.let {
                        repottingIntervalMonths = daysToRepottingMonths(it)
                        repottingIntervalEnabled = true
                    }
                    useLiquidFertilizer = plant.useLiquidFertilizer
                    pinIntervalToBase = plant.pinIntervalToBase
                }
            }
        }
    }

    fun addPhoto(uri: String) {
        if (pendingPhotos.none { it == uri }) {
            pendingPhotos.add(uri)
        }
        coverPhotoUri = uri
    }

    fun save() {
        if (name.isBlank()) {
            viewModelScope.launch { _events.emit(Event.ValidationError("Plant name is required")) }
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val newWateringIntervalDays = if (wateringIntervalEnabled) wateringIntervalDays else null
            val intervalChanged = newWateringIntervalDays != loadedWateringIntervalDays
            val plant = Plant(
                id = plantId ?: 0,
                name = name.trim(),
                species = species.trim().ifBlank { null },
                room = room.trim().ifBlank { null },
                notes = notes.trim().ifBlank { null },
                coverPhotoUri = coverPhotoUri,
                wateringIntervalDays = newWateringIntervalDays,
                fertilizingIntervalDays = if (fertilizingIntervalEnabled) fertilizingIntervalDays else null,
                repottingIntervalDays = if (repottingIntervalEnabled) {
                    repottingIntervalMonths * DAYS_PER_MONTH
                } else {
                    null
                },
                createdAt = if (isEditMode) 0L else now,
                updatedAt = now,
                useLiquidFertilizer = useLiquidFertilizer,
                pinIntervalToBase = pinIntervalToBase
            )
            if (isEditMode) {
                saveEdit(plant, newWateringIntervalDays, intervalChanged, now)
            } else {
                saveNew(plant, newWateringIntervalDays, now)
            }
        }
    }

    private suspend fun saveEdit(plant: Plant, newWateringIntervalDays: Int?, intervalChanged: Boolean, now: Long) {
        val existing = plantRepository.getPlantById(plantId!!).first()
        // An unprompted edit to the watering interval on this screen is a full confidence
        // reset (#568) — distinct from fine-tuning a number inside the ADR-0006 suggestion
        // dialog, which never routes through here.
        val wateringConfidence = if (intervalChanged) 0 else existing?.wateringConfidence
        // Mirrors the confidence reset above: de-seasonalize the newly typed value to today
        // (#569) so the effective interval doesn't jump on the next due-date computation.
        // Unchanged when SEASONAL_WATERING is off, the plant is pinned, or the interval
        // wasn't touched — the prior base (if any) is preserved rather than cleared.
        val baseShouldBeRecomputed = newWateringIntervalDays != null && intervalChanged && !pinIntervalToBase
        val wateringBaseIntervalDays = if (baseShouldBeRecomputed) {
            deseasonalizedBaseOrNull(newWateringIntervalDays!!, now) ?: existing?.wateringBaseIntervalDays
        } else {
            existing?.wateringBaseIntervalDays
        }
        plantRepository.updatePlant(
            plant.copy(
                createdAt = existing?.createdAt ?: now,
                wateringDueDateOverride = existing?.wateringDueDateOverride,
                wateringConfidence = wateringConfidence,
                wateringBaseIntervalDays = wateringBaseIntervalDays
            )
        )
        savePendingPhotos(plantId!!, now)
        _events.emit(Event.Saved(plantId))
    }

    private suspend fun saveNew(plant: Plant, newWateringIntervalDays: Int?, now: Long) {
        val wateringBaseIntervalDays = if (newWateringIntervalDays != null && !pinIntervalToBase) {
            deseasonalizedBaseOrNull(newWateringIntervalDays, now)
        } else {
            null
        }
        val newId = plantRepository.addPlant(plant.copy(wateringBaseIntervalDays = wateringBaseIntervalDays))
        savePendingPhotos(newId, now)
        _events.emit(Event.Saved(newId))
    }

    /**
     * `null` when SEASONAL_WATERING is off ([dataStore] is null or the flag reads off) — see
     * [seasonalWateringEnabled].
     */
    private suspend fun deseasonalizedBaseOrNull(intervalDays: Int, now: Long): Double? {
        val store = dataStore ?: return null
        val amplitude = store.seasonalAmplitudeOnce()
        return if (amplitude == 0.0) {
            null
        } else {
            SeasonalWatering.deseasonalize(
                intervalDays.toDouble(),
                now.toLocalDate(),
                amplitude,
                SeasonalWatering.currentHemisphere()
            )
        }
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

    private suspend fun savePendingPhotos(plantId: Long, now: Long) {
        if (pendingPhotos.isEmpty()) return
        plantPhotoRepository.addPhotos(
            pendingPhotos.map { PlantPhoto(plantId = plantId, uri = it, capturedAt = now) }
        )
    }

    sealed class Event {
        data class Saved(val plantId: Long) : Event()
        data class ArchivedForUndo(val plantId: Long, val plantName: String) : Event()
        data class ValidationError(val message: String) : Event()
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
    }

    class Factory(
        private val plantRepository: PlantRepository,
        private val plantPhotoRepository: PlantPhotoRepository,
        private val plantId: Long?,
        private val dataStore: DataStore<Preferences>? = null
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AddEditPlantViewModel(plantRepository, plantPhotoRepository, plantId, dataStore) as T
    }
}
