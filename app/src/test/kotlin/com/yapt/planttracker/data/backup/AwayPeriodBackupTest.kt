package com.yapt.planttracker.data.backup

import com.yapt.planttracker.data.entity.AwayPeriodEntity
import com.yapt.planttracker.data.entity.SitterVisitEntity
import com.yapt.planttracker.data.entity.SitterVisitPlantEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AwayPeriodBackupTest {

    private fun epoch(isoDate: String) = LocalDate.parse(isoDate).toEpochDay()

    private fun visit(
        id: Long,
        day: String,
        state: String = "PLANNED",
        plants: List<BackupSitterVisitPlant> = emptyList()
    ) = BackupSitterVisit(id = id, day = day, state = state, createdAt = 9L, plants = plants)

    private fun trip(
        id: Long = 1L,
        first: String = "2026-10-10",
        last: String = "2026-10-20",
        visits: List<BackupSitterVisit> = emptyList()
    ) = BackupAwayPeriod(id = id, firstDay = first, lastDay = last, visitEveryDays = 7, createdAt = 5L, visits = visits)

    @Test
    fun `export groups visits and their plants under the right trip with ISO dates`() {
        val periods = listOf(
            AwayPeriodEntity(1L, epoch("2026-10-10"), epoch("2026-10-20"), 7, 5L),
            AwayPeriodEntity(2L, epoch("2026-12-20"), epoch("2026-12-27"), null, 6L)
        )
        val visits = listOf(
            SitterVisitEntity(10L, 1L, epoch("2026-10-14"), "CONFIRMED", 7L),
            SitterVisitEntity(11L, 2L, epoch("2026-12-22"), "PLANNED", 8L)
        )
        val visitPlants = listOf(
            SitterVisitPlantEntity(10L, 100L, true),
            SitterVisitPlantEntity(10L, 101L, false),
            SitterVisitPlantEntity(11L, 100L, null)
        )

        val exported = buildBackupAwayPeriods(periods, visits, visitPlants)

        assertEquals(
            listOf(
                BackupAwayPeriod(
                    id = 1L,
                    firstDay = "2026-10-10",
                    lastDay = "2026-10-20",
                    visitEveryDays = 7,
                    createdAt = 5L,
                    visits = listOf(
                        BackupSitterVisit(
                            id = 10L,
                            day = "2026-10-14",
                            state = "CONFIRMED",
                            createdAt = 7L,
                            plants = listOf(
                                BackupSitterVisitPlant(100L, true),
                                BackupSitterVisitPlant(101L, false)
                            )
                        )
                    )
                ),
                BackupAwayPeriod(
                    id = 2L,
                    firstDay = "2026-12-20",
                    lastDay = "2026-12-27",
                    visitEveryDays = null,
                    createdAt = 6L,
                    visits = listOf(
                        BackupSitterVisit(
                            id = 11L,
                            day = "2026-12-22",
                            state = "PLANNED",
                            createdAt = 8L,
                            plants = listOf(BackupSitterVisitPlant(100L, null))
                        )
                    )
                )
            ),
            exported
        )
    }

    @Test
    fun `restore round-trips what export wrote`() {
        val periods = listOf(AwayPeriodEntity(1L, epoch("2026-10-10"), epoch("2026-10-20"), 7, 5L))
        val visits = listOf(SitterVisitEntity(10L, 1L, epoch("2026-10-14"), "DID_NOT_HAPPEN", 7L))
        val visitPlants = listOf(SitterVisitPlantEntity(10L, 100L, false), SitterVisitPlantEntity(10L, 101L, null))

        val restored = buildBackupAwayPeriods(periods, visits, visitPlants).toRestoredAwayData(setOf(100L, 101L))

        assertEquals(periods, restored.periods)
        assertEquals(visits, restored.visits)
        assertEquals(visitPlants, restored.visitPlants)
    }

    @Test
    fun `no trips restore as nothing`() {
        val restored = emptyList<BackupAwayPeriod>().toRestoredAwayData(setOf(1L))

        assertTrue(restored.periods.isEmpty() && restored.visits.isEmpty() && restored.visitPlants.isEmpty())
    }

    @Test
    fun `a trip with an unparseable date is skipped along with its visits and the rest restores`() {
        val good = trip(id = 1L, visits = listOf(visit(10L, "2026-10-14")))
        val badFirst = trip(id = 2L, first = "not-a-date", visits = listOf(visit(11L, "2026-10-14")))
        val badLast = trip(id = 3L, last = "2026-13-45", visits = listOf(visit(12L, "2026-10-14")))
        val blank = trip(id = 4L, first = "")

        val restored = listOf(badFirst, good, badLast, blank).toRestoredAwayData(emptySet())

        assertEquals(listOf(1L), restored.periods.map { it.id })
        assertEquals(listOf(10L), restored.visits.map { it.id })
    }

    @Test
    fun `a visit with an unparseable date is skipped and its trip and sibling visits restore`() {
        val period = trip(visits = listOf(visit(10L, "garbage"), visit(11L, "2026-10-14")))

        val restored = listOf(period).toRestoredAwayData(emptySet())

        assertEquals(listOf(1L), restored.periods.map { it.id })
        assertEquals(listOf(11L), restored.visits.map { it.id })
    }

    @Test
    fun `visits are re-filtered into their trip`() {
        val period = trip(
            visits = listOf(
                visit(10L, "2026-10-09"),
                visit(11L, "2026-10-10"),
                visit(12L, "2026-10-20"),
                visit(13L, "2026-10-21")
            )
        )

        val restored = listOf(period).toRestoredAwayData(emptySet())

        assertEquals(listOf(11L, 12L), restored.visits.map { it.id })
    }

    @Test
    fun `visit plants whose plant is not in the backup are dropped`() {
        val period = trip(
            visits = listOf(
                visit(
                    10L,
                    "2026-10-14",
                    plants = listOf(
                        BackupSitterVisitPlant(1L, true),
                        BackupSitterVisitPlant(99L, true),
                        BackupSitterVisitPlant(2L, null)
                    )
                )
            )
        )

        val restored = listOf(period).toRestoredAwayData(setOf(1L, 2L))

        assertEquals(listOf(1L, 2L), restored.visitPlants.map { it.plantId })
        assertEquals(listOf(true, null), restored.visitPlants.map { it.watered })
    }

    @Test
    fun `an unknown visit state restores as PLANNED`() {
        val period = trip(visits = listOf(visit(10L, "2026-10-14", state = "FROM_THE_FUTURE")))

        assertEquals("PLANNED", listOf(period).toRestoredAwayData(emptySet()).visits.single().state)
    }

    @Test
    fun `repeated ids and repeated visit plants collapse so no insert can violate a key`() {
        val duplicatePlants = listOf(BackupSitterVisitPlant(1L, true), BackupSitterVisitPlant(1L, false))
        val first = trip(id = 1L, visits = listOf(visit(10L, "2026-10-14", plants = duplicatePlants)))
        val secondSameId = trip(id = 1L, first = "2026-11-01", last = "2026-11-05")
        val secondTrip = trip(
            id = 2L,
            first = "2026-11-01",
            last = "2026-11-05",
            visits = listOf(visit(10L, "2026-11-02"))
        )

        val restored = listOf(first, secondSameId, secondTrip).toRestoredAwayData(setOf(1L))

        assertEquals(listOf(1L, 2L), restored.periods.map { it.id })
        assertEquals(listOf(10L), restored.visits.map { it.id })
        assertEquals(listOf(true), restored.visitPlants.map { it.watered })
    }
}
