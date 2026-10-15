package com.yapt.planttracker.data.backup

import com.yapt.planttracker.data.db.SitterVisitWithPlants
import com.yapt.planttracker.data.entity.AwayPeriodEntity
import com.yapt.planttracker.data.entity.SitterVisitEntity
import com.yapt.planttracker.data.entity.SitterVisitPlantEntity
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class AwayBackupMappingTest {

    private val firstDay = LocalDate.of(2026, 10, 10)
    private val lastDay = LocalDate.of(2026, 10, 20)

    private val periodRow = AwayPeriodEntity(
        id = 4L,
        firstDay = firstDay.toEpochDay(),
        lastDay = lastDay.toEpochDay(),
        visitEveryDays = 5,
        createdAt = 1_000L
    )
    private val visitRow = SitterVisitEntity(
        id = 9L,
        awayPeriodId = 4L,
        day = LocalDate.of(2026, 10, 14).toEpochDay(),
        state = "CONFIRMED",
        createdAt = 2_000L
    )
    private val visitPlantRows = listOf(
        SitterVisitPlantEntity(visitId = 9L, plantId = 1L, watered = true),
        SitterVisitPlantEntity(visitId = 9L, plantId = 2L, watered = false)
    )

    @Test
    fun `export writes ISO dates and nests visits and their plants`() {
        val exported = AwayBackupMapping.toBackup(listOf(periodRow), listOf(SitterVisitWithPlants(visitRow, visitPlantRows)))

        assertEquals(
            listOf(
                BackupAwayPeriod(
                    id = 4L,
                    firstDay = "2026-10-10",
                    lastDay = "2026-10-20",
                    visitEveryDays = 5,
                    createdAt = 1_000L,
                    visits = listOf(
                        BackupSitterVisit(
                            id = 9L,
                            day = "2026-10-14",
                            state = "CONFIRMED",
                            createdAt = 2_000L,
                            plants = listOf(
                                BackupSitterVisitPlant(plantId = 1L, watered = true),
                                BackupSitterVisitPlant(plantId = 2L, watered = false)
                            )
                        )
                    )
                )
            ),
            exported
        )
    }

    @Test
    fun `export then restore gives back the same rows`() {
        val exported = AwayBackupMapping.toBackup(listOf(periodRow), listOf(SitterVisitWithPlants(visitRow, visitPlantRows)))

        val restored = AwayBackupMapping.fromBackup(exported, plantIds = setOf(1L, 2L))

        assertEquals(AwayRestoreRows(listOf(periodRow), listOf(visitRow), visitPlantRows), restored)
    }

    @Test
    fun `a trip with an unparseable or reversed date is skipped with its visits`() {
        val good = backupTrip(id = 1L, first = "2026-10-10", last = "2026-10-20")
        val trips = listOf(
            good,
            backupTrip(id = 2L, first = "10/11/2026", last = "2026-11-20"),
            backupTrip(id = 3L, first = "2026-12-10", last = "not a date"),
            backupTrip(id = 4L, first = "2027-01-10", last = "2027-01-01")
        )

        val restored = AwayBackupMapping.fromBackup(trips, plantIds = setOf(1L))

        assertEquals(listOf(1L), restored.periods.map { it.id })
        assertEquals(listOf(100L), restored.visits.map { it.id })
    }

    @Test
    fun `a visit with an unparseable date or outside its trip is skipped`() {
        val trip = backupTrip(id = 1L, first = "2026-10-10", last = "2026-10-20").copy(
            visits = listOf(
                backupVisit(id = 1L, day = "2026-10-10"),
                backupVisit(id = 2L, day = "2026-10-20"),
                backupVisit(id = 3L, day = "2026-10-21"),
                backupVisit(id = 4L, day = "2026-10-09"),
                backupVisit(id = 5L, day = "")
            )
        )

        val restored = AwayBackupMapping.fromBackup(listOf(trip), plantIds = setOf(1L))

        assertEquals(listOf(1L, 2L), restored.visits.map { it.id })
        assertEquals(listOf(1L, 2L), restored.visitPlants.map { it.visitId })
    }

    @Test
    fun `visit plants whose plant is not in the backup are dropped`() {
        val trip = backupTrip(id = 1L, first = "2026-10-10", last = "2026-10-20").copy(
            visits = listOf(
                backupVisit(id = 1L, day = "2026-10-12").copy(
                    plants = listOf(
                        BackupSitterVisitPlant(plantId = 1L),
                        BackupSitterVisitPlant(plantId = 77L, watered = true),
                        BackupSitterVisitPlant(plantId = 1L, watered = true)
                    )
                )
            )
        )

        val restored = AwayBackupMapping.fromBackup(listOf(trip), plantIds = setOf(1L))

        assertEquals(listOf(SitterVisitPlantEntity(visitId = 1L, plantId = 1L, watered = null)), restored.visitPlants)
    }

    @Test
    fun `an unknown visit state is kept as stored`() {
        val trip = backupTrip(id = 1L, first = "2026-10-10", last = "2026-10-20").copy(
            visits = listOf(backupVisit(id = 1L, day = "2026-10-12").copy(state = "FROM_THE_FUTURE"))
        )

        assertEquals("FROM_THE_FUTURE", AwayBackupMapping.fromBackup(listOf(trip), emptySet()).visits.single().state)
    }

    private fun backupTrip(id: Long, first: String, last: String) = BackupAwayPeriod(
        id = id,
        firstDay = first,
        lastDay = last,
        createdAt = 1L,
        visits = listOf(backupVisit(id = id * 100, day = first))
    )

    private fun backupVisit(id: Long, day: String) = BackupSitterVisit(
        id = id,
        day = day,
        state = "PLANNED",
        createdAt = 1L,
        plants = listOf(BackupSitterVisitPlant(plantId = 1L))
    )
}
