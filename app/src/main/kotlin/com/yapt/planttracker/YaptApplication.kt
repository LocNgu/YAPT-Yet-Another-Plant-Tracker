package com.yapt.planttracker

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.preferences.SettingsDefaults
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.CustomReminderRepository
import com.yapt.planttracker.data.repository.PlantIssueRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.featureflag.FeatureFlags
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.domain.schedule.seasonalAmplitudeOnce
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import com.yapt.planttracker.domain.usecase.SeasonalGraduationFixup
import com.yapt.planttracker.notification.NotificationHelper
import com.yapt.planttracker.worker.PostWateringReminderScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

internal suspend fun writeDefaultReminderTimeIfAbsent(dataStore: DataStore<Preferences>) {
    val prefs = dataStore.data.first()
    if (prefs[SettingsKeys.REMINDER_HOUR] == null) {
        dataStore.edit {
            it[SettingsKeys.REMINDER_HOUR] = SettingsDefaults.REMINDER_HOUR
            it[SettingsKeys.REMINDER_MINUTE] = SettingsDefaults.REMINDER_MINUTE
        }
    }
}

open class YaptApplication : Application() {

    /**
     * Process-wide scope, unaffected by any one screen's `viewModelScope` being cancelled — exposed
     * (rather than kept private) so `PlantDetailViewModel.Factory` (#531 review round 1, product
     * ADR-0048) can hand it to a ViewModel whose coalesced interval-tap writes must survive the screen
     * being left before their debounce window elapses.
     */
    internal val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    internal var isAppForeground: Boolean = false
        private set

    val database by lazy { PlantDatabase.getInstance(this) }

    val plantRepository by lazy { PlantRepository(database.plantDao()) }
    val careLogRepository by lazy { CareLogRepository(database.careLogDao()) }
    val plantPhotoRepository by lazy { PlantPhotoRepository(database.plantPhotoDao()) }
    val customReminderRepository by lazy { CustomReminderRepository(database.customReminderDao()) }
    val plantIssueRepository by lazy { PlantIssueRepository(database.plantIssueDao()) }
    val wateringAdjustmentRepository by lazy { WateringAdjustmentRepository(database.wateringAdjustmentDao()) }
    val featureFlags by lazy { FeatureFlags(settingsDataStore) }
    val quickLogUseCase by lazy {
        QuickLogUseCase(
            this,
            plantRepository,
            careLogRepository,
            plantPhotoRepository,
            settingsDataStore,
            database,
            wateringAdjustmentRepository,
            onWaterLogged = ::schedulePostWateringReminder
        )
    }

    suspend fun schedulePostWateringReminder(loggedAt: Long) {
        PostWateringReminderScheduler.scheduleIfEnabled(this, settingsDataStore, loggedAt)
    }

    /**
     * #702 one-time backfill — see [SeasonalGraduationFixup]. `runCatching`-wrapped so a bug in this
     * reconciliation can never crash app start; a failure simply leaves the one-time flag unset,
     * retrying on the next launch.
     */
    private suspend fun runSeasonalGraduationFixupIfNeeded() {
        runCatching {
            val plants = plantRepository.getAllPlants().first() + plantRepository.getArchivedPlants().first()
            val request = SeasonalGraduationFixup.FixupRequest(
                plants = plants,
                amplitude = settingsDataStore.seasonalAmplitudeOnce(),
                hemisphere = SeasonalWatering.currentHemisphere()
            )
            SeasonalGraduationFixup.maybeRun(
                request = request,
                plantRepository = plantRepository,
                wateringAdjustmentRepository = wateringAdjustmentRepository,
                dataStore = settingsDataStore
            )
        }
    }

    internal fun setAppForeground(foreground: Boolean) {
        isAppForeground = foreground
    }

    /**
     * Fire-and-forget app-start background work, split out of [onCreate] and left `open` purely so
     * Robolectric can suppress it (#757). Under Robolectric a fresh `YaptApplication` is created for
     * *every* test method while [PlantDatabase.getInstance] and the [settingsDataStore] delegate are
     * both process-wide singletons shared across the whole JVM fork, so this coroutine ran
     * concurrently with — and against the same database as — whatever test happened to be executing.
     * When [SeasonalGraduationFixup]'s plant snapshot landed after a test had inserted its fixture, the
     * fixup rewrote that fixture's `wateringBaseIntervalDays`, which is exactly the column
     * `SkipWateringReceiverTest`'s product ADR-0007 invariant guard asserts is never written. The override
     * lives in `TestYaptApplication` (unit-test source set), which Robolectric selects by its own
     * `Test` + <application simple name> convention as well as by the explicit
     * `app/src/test/resources/robolectric.properties` registration — see that class's KDoc.
     */
    protected open fun launchAppStartWork() {
        applicationScope.launch {
            writeDefaultReminderTimeIfAbsent(settingsDataStore)
            runSeasonalGraduationFixupIfNeeded()
        }
    }

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannel(this)
        launchAppStartWork()
    }
}
