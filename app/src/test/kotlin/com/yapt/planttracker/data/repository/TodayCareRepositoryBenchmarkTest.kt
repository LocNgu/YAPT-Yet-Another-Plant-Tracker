package com.yapt.planttracker.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.entity.CareLogEntity
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.data.entity.PlantPhotoEntity
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId

/**
 * Informational benchmark for #841 — logs numbers only, asserts no timings (flaky in CI). Run with
 * `./gradlew testDebugUnitTest --tests '*TodayCareRepositoryBenchmarkTest*'` and read the
 * `YAPT_BENCH` line, which `app/build.gradle.kts` promotes to the console.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TodayCareRepositoryBenchmarkTest {

    private lateinit var db: PlantDatabase
    private var firstPlantId = 0L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `benchmark first emission and re-emission after a care log insert`() = runBlocking {
        seed()
        val repository = repository()

        repeat(WARMUP_RUNS) { measureFirstEmission(repository) }
        val first = List(RUNS) { measureFirstEmission(repository) }

        val reEmission = measureReEmissions(repository)

        println(
            "YAPT_BENCH today-queue plants=$PLANTS logs=${PLANTS * LOGS_PER_PLANT} " +
                "photos=${PLANTS * PHOTOS_PER_PLANT} " +
                "firstEmissionMedianMs=${median(first)} firstEmissionSamplesMs=$first " +
                "reEmissionMedianMs=${median(reEmission)} reEmissionSamplesMs=$reEmission"
        )
    }

    private suspend fun seed() {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val dayMs = { daysAgo: Long -> today.minusDays(daysAgo).atStartOfDay(zone).toInstant().toEpochMilli() }
        val plantIds = List(PLANTS) { index ->
            db.plantDao().insertPlant(
                PlantEntity(
                    name = "Plant $index",
                    species = null,
                    room = null,
                    coverPhotoUri = null,
                    notes = null,
                    wateringIntervalDays = 7,
                    fertilizingIntervalDays = 30,
                    repottingIntervalDays = 365,
                    createdAt = dayMs(900L),
                    updatedAt = dayMs(900L)
                )
            )
        }
        val types = listOf("WATER", "WATER", "WATER", "FERTILIZE", "NOTE", "PRUNE", "REPOT", "PHOTO")
        firstPlantId = plantIds.first()
        val logs = ArrayList<CareLogEntity>()
        val photos = ArrayList<PlantPhotoEntity>()
        for (plantId in plantIds) {
            for (i in 0 until LOGS_PER_PLANT) {
                val type = types[i % types.size]
                logs += CareLogEntity(
                    plantId = plantId,
                    careType = type,
                    loggedAt = dayMs(1L + i * 3L),
                    notes = null,
                    photoUri = if (type == "PHOTO") "file://photo/$plantId/$i.jpg" else null,
                    amount = null,
                    wateringFeedback = null
                )
            }
            for (i in 0 until PHOTOS_PER_PLANT) {
                photos += PlantPhotoEntity(
                    plantId = plantId,
                    uri = "file://gallery/$plantId/$i.jpg",
                    capturedAt = dayMs(2L + i * 5L)
                )
            }
        }
        db.careLogDao().insertAll(logs)
        db.plantPhotoDao().insertAll(photos)
    }

    private fun repository(): TodayCareRepository {
        val dataStore = mockk<DataStore<Preferences>> {
            every { data } returns flowOf(emptyPreferences())
        }
        return TodayCareRepository(
            PlantRepository(db.plantDao()),
            CareLogRepository(db.careLogDao()),
            CustomReminderRepository(db.customReminderDao()),
            PlantIssueRepository(db.plantIssueDao()),
            PlantPhotoRepository(db.plantPhotoDao()),
            dataStore,
            flowOf(LocalDate.now())
        )
    }

    private suspend fun measureFirstEmission(repository: TodayCareRepository): Double {
        val channel = Channel<Unit>(Channel.UNLIMITED)
        val scope = CoroutineScope(Dispatchers.Default)
        val start = System.nanoTime()
        repository.observeQueue().onEach { channel.trySend(Unit) }.launchIn(scope)
        withTimeout(TIMEOUT_MS) { channel.receive() }
        val elapsed = (System.nanoTime() - start) / NANOS_PER_MS
        scope.cancel()
        return elapsed
    }

    private suspend fun measureReEmissions(repository: TodayCareRepository): List<Double> {
        val channel = Channel<Unit>(Channel.UNLIMITED)
        val scope = CoroutineScope(Dispatchers.Default)
        repository.observeQueue().onEach { channel.trySend(Unit) }.launchIn(scope)
        withTimeout(TIMEOUT_MS) { channel.receive() }
        val plantId = firstPlantId
        val samples = ArrayList<Double>()
        repeat(WARMUP_RUNS + RUNS) { run ->
            delay(SETTLE_MS)
            do {
                val drained = channel.tryReceive().isSuccess
            } while (drained)
            val start = System.nanoTime()
            db.careLogDao().insertLog(
                CareLogEntity(
                    plantId = plantId,
                    careType = "WATER",
                    loggedAt = System.currentTimeMillis() + run,
                    notes = null,
                    photoUri = null,
                    amount = null,
                    wateringFeedback = null
                )
            )
            withTimeout(TIMEOUT_MS) { channel.receive() }
            val elapsed = (System.nanoTime() - start) / NANOS_PER_MS
            if (run >= WARMUP_RUNS) samples += elapsed
        }
        scope.cancel()
        return samples
    }

    private fun median(values: List<Double>): Double = values.sorted()[values.size / 2]

    private companion object {
        const val PLANTS = 50
        const val LOGS_PER_PLANT = 100
        const val PHOTOS_PER_PLANT = 10
        const val WARMUP_RUNS = 3
        const val RUNS = 9
        const val SETTLE_MS = 300L
        const val TIMEOUT_MS = 60_000L
        const val NANOS_PER_MS = 1_000_000.0
    }
}
