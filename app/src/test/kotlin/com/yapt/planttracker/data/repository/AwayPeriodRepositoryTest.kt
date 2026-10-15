package com.yapt.planttracker.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yapt.planttracker.data.db.PlantDatabase
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.data.entity.SitterVisitEntity
import com.yapt.planttracker.domain.model.AwayPeriod
import com.yapt.planttracker.domain.model.SitterVisit
import com.yapt.planttracker.domain.model.SitterVisitPlant
import com.yapt.planttracker.domain.model.SitterVisitState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AwayPeriodRepositoryTest {

    private lateinit var db: PlantDatabase
    private lateinit var repository: AwayPeriodRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PlantDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = AwayPeriodRepository(db.awayPeriodDao(), db.sitterVisitDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun addPlant(name: String): Long = db.plantDao().insertPlant(
        PlantEntity(
            name = name,
            species = null,
            room = null,
            coverPhotoUri = null,
            notes = null,
            wateringIntervalDays = 7,
            fertilizingIntervalDays = null,
            createdAt = 1_000L,
            updatedAt = 1_000L
        )
    )

    private fun trip(first: LocalDate, last: LocalDate, rhythm: Int? = null) =
        AwayPeriod(firstDay = first, lastDay = last, visitEveryDays = rhythm, createdAt = 5_000L)

    @Test
    fun `trips round-trip every field and come back ordered by first day`() = runTest {
        val laterId = repository.add(trip(LocalDate.of(2026, 12, 20), LocalDate.of(2027, 1, 3)))
        val earlierId = repository.add(trip(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20), rhythm = 5))

        val all = repository.observeAll().first()

        assertEquals(listOf(earlierId, laterId), all.map { it.id })
        assertEquals(
            AwayPeriod(earlierId, LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20), 5, 5_000L),
            all[0]
        )
        assertEquals(all, repository.getAll())
    }

    @Test
    fun `update changes dates and deletes the visits passed with it`() = runTest {
        val plant = addPlant("Fern")
        val tripId = repository.add(trip(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20)))
        val keep = repository.saveVisit(visit(tripId, LocalDate.of(2026, 10, 12), plant))
        val drop = repository.saveVisit(visit(tripId, LocalDate.of(2026, 10, 18), plant))
        val shortened = repository.getAll().single().copy(lastDay = LocalDate.of(2026, 10, 15))

        repository.update(shortened, visitsOutside = repository.getVisits(tripId).filter { it.id == drop })

        assertEquals(LocalDate.of(2026, 10, 15), repository.getAll().single().lastDay)
        assertEquals(listOf(keep), repository.getVisits(tripId).map { it.id })
    }

    @Test
    fun `a visit round-trips with its plants, and saving again replaces the plant list`() = runTest {
        val fern = addPlant("Fern")
        val cactus = addPlant("Cactus")
        val tripId = repository.add(trip(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20)))
        val planned = SitterVisit(
            awayPeriodId = tripId,
            day = LocalDate.of(2026, 10, 14),
            plants = listOf(SitterVisitPlant(fern), SitterVisitPlant(cactus)),
            createdAt = 7_000L
        )

        val visitId = repository.saveVisit(planned)
        assertEquals(listOf(planned.copy(id = visitId)), repository.observeVisits(tripId).first())

        val confirmed = planned.copy(
            id = visitId,
            state = SitterVisitState.CONFIRMED,
            plants = listOf(SitterVisitPlant(fern, watered = true))
        )
        assertEquals(visitId, repository.saveVisit(confirmed))
        assertEquals(listOf(confirmed), repository.getVisits(tripId))
    }

    @Test
    fun `deleteVisits removes only the given visits`() = runTest {
        val plant = addPlant("Fern")
        val tripId = repository.add(trip(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20)))
        val first = repository.saveVisit(visit(tripId, LocalDate.of(2026, 10, 12), plant))
        repository.saveVisit(visit(tripId, LocalDate.of(2026, 10, 16), plant))

        repository.deleteVisits(repository.getVisits(tripId).filter { it.id != first })

        assertEquals(listOf(first), repository.getVisits(tripId).map { it.id })
    }

    @Test
    fun `deleting a trip cascades to its visits and their plants`() = runTest {
        val plant = addPlant("Fern")
        val tripId = repository.add(trip(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20)))
        repository.saveVisit(visit(tripId, LocalDate.of(2026, 10, 12), plant))

        repository.delete(repository.getAll().single())

        assertTrue(repository.getAll().isEmpty())
        assertTrue(db.sitterVisitDao().getAllVisits().isEmpty())
    }

    @Test
    fun `deleting a plant removes it from visit lists but keeps the visit`() = runTest {
        val fern = addPlant("Fern")
        val cactus = addPlant("Cactus")
        val tripId = repository.add(trip(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20)))
        repository.saveVisit(
            SitterVisit(
                awayPeriodId = tripId,
                day = LocalDate.of(2026, 10, 12),
                plants = listOf(SitterVisitPlant(fern), SitterVisitPlant(cactus))
            )
        )

        db.plantDao().deletePlant(checkNotNull(db.plantDao().getPlantById(cactus).first()))

        assertEquals(listOf(fern), repository.getVisits(tripId).single().plants.map { it.plantId })
    }

    @Test
    fun `an unknown stored state reads back as planned`() = runTest {
        val tripId = repository.add(trip(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20)))
        db.sitterVisitDao().insertVisit(
            SitterVisitEntity(
                awayPeriodId = tripId,
                day = LocalDate.of(2026, 10, 12).toEpochDay(),
                state = "SOMETHING_NEW",
                createdAt = 1L
            )
        )

        assertEquals(SitterVisitState.PLANNED, repository.getVisits(tripId).single().state)
    }

    private fun visit(tripId: Long, day: LocalDate, plantId: Long) = SitterVisit(
        awayPeriodId = tripId,
        day = day,
        plants = listOf(SitterVisitPlant(plantId)),
        createdAt = 1L
    )
}
