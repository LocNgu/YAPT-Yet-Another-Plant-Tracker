package com.yapt.planttracker.ui.screens.away

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.repository.AwayPeriodRepository
import com.yapt.planttracker.data.repository.CareLogRepository
import com.yapt.planttracker.data.repository.PlantPhotoRepository
import com.yapt.planttracker.data.repository.PlantRepository
import com.yapt.planttracker.data.repository.WateringAdjustmentRepository
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.model.SitterVisit
import com.yapt.planttracker.domain.model.SitterVisitPlant
import com.yapt.planttracker.domain.model.SitterVisitState
import com.yapt.planttracker.domain.usecase.QuickLogUseCase
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executor

/**
 * An in-memory Room database with the real repositories and [QuickLogUseCase], shared by the sitter-flow
 * ViewModel tests. Room runs on the test thread (#912) so a mutation's events can't race a `join()`.
 */
class SitterFlowFixture {

    val db: PlantDatabase
    val plantRepo: PlantRepository
    val careLogRepo: CareLogRepository
    val awayRepo: AwayPeriodRepository
    val adjustments: WateringAdjustmentRepository
    val useCase: QuickLogUseCase
    val today: LocalDate = LocalDate.now()
    val postWateringReminders = mutableListOf<Long>()

    init {
        val synchronous = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PlantDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(synchronous)
            .setTransactionExecutor(synchronous)
            .build()
        plantRepo = PlantRepository(db.plantDao())
        careLogRepo = CareLogRepository(db.careLogDao())
        awayRepo = AwayPeriodRepository(db.awayPeriodDao())
        adjustments = WateringAdjustmentRepository(db.wateringAdjustmentDao())
        val dataStore: DataStore<Preferences> = mockk { every { data } returns flowOf(emptyPreferences()) }
        useCase = QuickLogUseCase(
            mockk<Application>(relaxed = true),
            plantRepo,
            careLogRepo,
            PlantPhotoRepository(db.plantPhotoDao()),
            dataStore,
            db,
            adjustments,
            onWaterLogged = { postWateringReminders.add(it) },
            awayPeriodRepository = awayRepo
        )
    }

    fun close() = db.close()

    fun millis(day: LocalDate, hour: Int = 12): Long =
        day.atTime(hour, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** A plant on a fixed [interval] (never reseasoned), last watered on [lastWatered] when given. */
    @Suppress("LongParameterList")
    suspend fun addPlant(
        name: String,
        interval: Int? = 7,
        lastWatered: LocalDate? = null,
        room: String? = null,
        photo: String? = null,
        confidence: Int? = 3
    ): Plant {
        val plant = Plant(
            name = name,
            room = room,
            coverPhotoUri = photo,
            wateringIntervalDays = interval,
            wateringConfidence = confidence,
            pinIntervalToBase = true,
            createdAt = 0L,
            updatedAt = 0L
        )
        val saved = plant.copy(id = plantRepo.addPlant(plant))
        lastWatered?.let {
            careLogRepo.addLog(CareLog(plantId = saved.id, careType = CareType.WATER, loggedAt = millis(it)))
        }
        return saved
    }

    suspend fun addTrip(first: LocalDate, last: LocalDate, visitEveryDays: Int? = null): AwayPeriod {
        val period = AwayPeriod(firstDay = first, lastDay = last, visitEveryDays = visitEveryDays, createdAt = 1L)
        return period.copy(id = awayRepo.addPeriod(period))
    }

    suspend fun addVisit(
        trip: AwayPeriod,
        day: LocalDate,
        state: SitterVisitState = SitterVisitState.PLANNED,
        vararg plants: Pair<Plant, Boolean?>
    ): SitterVisit {
        val visit = SitterVisit(
            awayPeriodId = trip.id,
            day = day,
            state = state,
            plants = plants.map { SitterVisitPlant(it.first.id, it.second) },
            createdAt = 1L
        )
        return visit.copy(id = awayRepo.addVisit(visit))
    }

    suspend fun waterLogs(plantId: Long): List<CareLog> =
        careLogRepo.getAllLogs().first().filter { it.plantId == plantId && it.careType == CareType.WATER }

    suspend fun visits(): List<SitterVisit> = awayRepo.getAllWithVisits().flatMap { it.visits }
}
