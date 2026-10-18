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
        repository = AwayPeriodRepository(db.awayPeriodDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertPlant(name: String): Long = db.plantDao().insertPlant(
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

    private fun period(first: LocalDate, last: LocalDate, every: Int? = null, createdAt: Long = 5_000L) =
        AwayPeriod(firstDay = first, lastDay = last, visitEveryDays = every, createdAt = createdAt)

    private val oct10 = LocalDate.of(2026, 10, 10)
    private val oct20 = LocalDate.of(2026, 10, 20)

    @Test
    fun `a trip round-trips every field`() = runTest {
        val id = repository.addPeriod(period(oct10, oct20, every = 7))

        val stored = repository.getAll().single()

        assertEquals(AwayPeriod(id, oct10, oct20, 7, 5_000L), stored)
    }

    @Test
    fun `a trip without a sitter rhythm stores null`() = runTest {
        repository.addPeriod(period(oct10, oct20))

        assertEquals(null, repository.getAll().single().visitEveryDays)
    }

    @Test
    fun `trips come back ordered by first day`() = runTest {
        val later = repository.addPeriod(period(LocalDate.of(2026, 12, 20), LocalDate.of(2026, 12, 27)))
        val earlier = repository.addPeriod(period(oct10, oct20))

        assertEquals(listOf(earlier, later), repository.getAll().map { it.id })
        assertEquals(listOf(earlier, later), repository.observeAll().first().map { it.id })
        assertEquals(listOf(earlier, later), repository.observeAllWithVisits().first().map { it.period.id })
    }

    @Test
    fun `updatePeriod changes the stored dates and rhythm`() = runTest {
        val id = repository.addPeriod(period(oct10, oct20))

        repository.updatePeriod(AwayPeriod(id, oct10, LocalDate.of(2026, 10, 18), 5, 5_000L))

        assertEquals(AwayPeriod(id, oct10, LocalDate.of(2026, 10, 18), 5, 5_000L), repository.getAll().single())
    }

    @Test
    fun `deletePeriod removes only that trip`() = runTest {
        val keep = repository.addPeriod(period(oct10, oct20))
        val drop = repository.addPeriod(period(LocalDate.of(2026, 12, 20), LocalDate.of(2026, 12, 27)))

        repository.deletePeriod(repository.getAll().first { it.id == drop })

        assertEquals(listOf(keep), repository.getAll().map { it.id })
    }

    @Test
    fun `observeAll emits again after a change`() = runTest {
        assertTrue(repository.observeAll().first().isEmpty())

        repository.addPeriod(period(oct10, oct20))

        assertEquals(1, repository.observeAll().first().size)
    }

    @Test
    fun `a visit round-trips its plants and unconfirmed null watered flags`() = runTest {
        val periodId = repository.addPeriod(period(oct10, oct20))
        val monstera = insertPlant("Monstera")
        val ficus = insertPlant("Ficus")

        val visitId = repository.addVisit(
            SitterVisit(
                awayPeriodId = periodId,
                day = LocalDate.of(2026, 10, 14),
                plants = listOf(SitterVisitPlant(ficus), SitterVisitPlant(monstera)),
                createdAt = 6_000L
            )
        )

        val stored = repository.getAllWithVisits().single().visits.single()
        assertEquals(visitId, stored.id)
        assertEquals(periodId, stored.awayPeriodId)
        assertEquals(LocalDate.of(2026, 10, 14), stored.day)
        assertEquals(SitterVisitState.PLANNED, stored.state)
        assertEquals(6_000L, stored.createdAt)
        assertEquals(listOf(SitterVisitPlant(monstera, null), SitterVisitPlant(ficus, null)), stored.plants)
    }

    @Test
    fun `updateVisit stores the state and per-plant watered flags and replaces the plant list`() = runTest {
        val periodId = repository.addPeriod(period(oct10, oct20))
        val monstera = insertPlant("Monstera")
        val ficus = insertPlant("Ficus")
        val cactus = insertPlant("Cactus")
        val visitId = repository.addVisit(
            SitterVisit(
                awayPeriodId = periodId,
                day = LocalDate.of(2026, 10, 14),
                plants = listOf(SitterVisitPlant(monstera), SitterVisitPlant(ficus))
            )
        )

        repository.updateVisit(
            SitterVisit(
                id = visitId,
                awayPeriodId = periodId,
                day = LocalDate.of(2026, 10, 15),
                state = SitterVisitState.CONFIRMED,
                plants = listOf(SitterVisitPlant(monstera, true), SitterVisitPlant(cactus, false)),
                createdAt = 6_000L
            )
        )

        val stored = repository.getAllWithVisits().single().visits.single()
        assertEquals(LocalDate.of(2026, 10, 15), stored.day)
        assertEquals(SitterVisitState.CONFIRMED, stored.state)
        assertEquals(listOf(SitterVisitPlant(monstera, true), SitterVisitPlant(cactus, false)), stored.plants)
    }

    @Test
    fun `an unknown stored visit state reads back as PLANNED`() = runTest {
        val periodId = repository.addPeriod(period(oct10, oct20))
        db.awayPeriodDao().insertVisit(
            SitterVisitEntity(awayPeriodId = periodId, day = oct10.toEpochDay(), state = "FUTURE_STATE", createdAt = 1L)
        )

        assertEquals(SitterVisitState.PLANNED, repository.getAllWithVisits().single().visits.single().state)
    }

    @Test
    fun `each state round-trips`() = runTest {
        val periodId = repository.addPeriod(period(oct10, oct20))
        SitterVisitState.entries.forEachIndexed { index, state ->
            repository.addVisit(
                SitterVisit(awayPeriodId = periodId, day = oct10.plusDays(index.toLong()), state = state)
            )
        }

        assertEquals(
            SitterVisitState.entries.toList(),
            repository.getAllWithVisits().single().visits.map { it.state }
        )
    }

    @Test
    fun `visits come back ordered by day`() = runTest {
        val periodId = repository.addPeriod(period(oct10, oct20))
        val late = repository.addVisit(SitterVisit(awayPeriodId = periodId, day = LocalDate.of(2026, 10, 18)))
        val early = repository.addVisit(SitterVisit(awayPeriodId = periodId, day = LocalDate.of(2026, 10, 12)))

        assertEquals(listOf(early, late), repository.getAllWithVisits().single().visits.map { it.id })
    }

    @Test
    fun `deleteVisits removes the visits and their plants and leaves the trip`() = runTest {
        val periodId = repository.addPeriod(period(oct10, oct20))
        val plant = insertPlant("Monstera")
        val keep = repository.addVisit(
            SitterVisit(awayPeriodId = periodId, day = oct10, plants = listOf(SitterVisitPlant(plant)))
        )
        val drop = repository.addVisit(
            SitterVisit(awayPeriodId = periodId, day = oct20, plants = listOf(SitterVisitPlant(plant)))
        )

        repository.deleteVisits(listOf(drop))

        assertEquals(listOf(keep), repository.getAllWithVisits().single().visits.map { it.id })
        assertEquals(listOf(keep), db.awayPeriodDao().getAllVisitPlants().map { it.visitId })
        assertEquals(1, repository.getAll().size)
    }

    @Test
    fun `deleteVisits with no ids is a no-op`() = runTest {
        val periodId = repository.addPeriod(period(oct10, oct20))
        repository.addVisit(SitterVisit(awayPeriodId = periodId, day = oct10))

        repository.deleteVisits(emptyList())

        assertEquals(1, repository.getAllWithVisits().single().visits.size)
    }

    @Test
    fun `deleting a trip cascades to its visits and their plants`() = runTest {
        val periodId = repository.addPeriod(period(oct10, oct20))
        val plant = insertPlant("Monstera")
        repository.addVisit(
            SitterVisit(awayPeriodId = periodId, day = oct10, plants = listOf(SitterVisitPlant(plant)))
        )

        repository.deletePeriod(repository.getAll().single())

        assertTrue(db.awayPeriodDao().getAllPeriods().isEmpty())
        assertTrue(db.awayPeriodDao().getAllVisits().isEmpty())
        assertTrue(db.awayPeriodDao().getAllVisitPlants().isEmpty())
        assertEquals(1, db.plantDao().getAllPlants().first().size)
    }

    @Test
    fun `deleting a plant cascades to its visit plants only`() = runTest {
        val periodId = repository.addPeriod(period(oct10, oct20))
        val doomed = insertPlant("Monstera")
        val survivor = insertPlant("Ficus")
        repository.addVisit(
            SitterVisit(
                awayPeriodId = periodId,
                day = oct10,
                plants = listOf(SitterVisitPlant(doomed), SitterVisitPlant(survivor))
            )
        )

        db.plantDao().deletePlant(db.plantDao().getPlantById(doomed).first()!!)

        val visit = repository.getAllWithVisits().single().visits.single()
        assertEquals(listOf(survivor), visit.plants.map { it.plantId })
        assertEquals(1, repository.getAll().size)
    }

    @Test
    fun `observeAllWithVisits reflects a visit added later`() = runTest {
        val periodId = repository.addPeriod(period(oct10, oct20))
        assertTrue(repository.observeAllWithVisits().first().single().visits.isEmpty())

        repository.addVisit(SitterVisit(awayPeriodId = periodId, day = oct10))

        assertEquals(1, repository.observeAllWithVisits().first().single().visits.size)
    }
}
