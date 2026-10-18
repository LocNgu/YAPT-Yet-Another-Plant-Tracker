package com.yapt.planttracker.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.yapt.planttracker.data.entity.AwayPeriodEntity
import com.yapt.planttracker.data.entity.SitterVisitEntity
import com.yapt.planttracker.data.entity.SitterVisitPlantEntity
import kotlinx.coroutines.flow.Flow

data class SitterVisitWithPlants(
    @Embedded val visit: SitterVisitEntity,
    @Relation(parentColumn = "id", entityColumn = "visitId")
    val plants: List<SitterVisitPlantEntity>
)

data class AwayPeriodWithVisitsRelation(
    @Embedded val period: AwayPeriodEntity,
    @Relation(entity = SitterVisitEntity::class, parentColumn = "id", entityColumn = "awayPeriodId")
    val visits: List<SitterVisitWithPlants>
)

@Dao
@Suppress("TooManyFunctions")
abstract class AwayPeriodDao {

    @Transaction
    @Query("SELECT * FROM away_periods ORDER BY firstDay ASC, id ASC")
    abstract fun observeAllWithVisits(): Flow<List<AwayPeriodWithVisitsRelation>>

    @Transaction
    @Query("SELECT * FROM away_periods ORDER BY firstDay ASC, id ASC")
    abstract suspend fun getAllWithVisits(): List<AwayPeriodWithVisitsRelation>

    @Query("SELECT * FROM away_periods ORDER BY firstDay ASC, id ASC")
    abstract fun observeAllPeriods(): Flow<List<AwayPeriodEntity>>

    @Query("SELECT * FROM away_periods ORDER BY firstDay ASC, id ASC")
    abstract suspend fun getAllPeriods(): List<AwayPeriodEntity>

    @Query("SELECT * FROM sitter_visits ORDER BY day ASC, id ASC")
    abstract suspend fun getAllVisits(): List<SitterVisitEntity>

    @Query("SELECT * FROM sitter_visit_plants ORDER BY visitId ASC, plantId ASC")
    abstract suspend fun getAllVisitPlants(): List<SitterVisitPlantEntity>

    @Insert
    abstract suspend fun insertPeriod(period: AwayPeriodEntity): Long

    @Update
    abstract suspend fun updatePeriod(period: AwayPeriodEntity)

    @Delete
    abstract suspend fun deletePeriod(period: AwayPeriodEntity)

    @Insert
    abstract suspend fun insertVisit(visit: SitterVisitEntity): Long

    @Update
    abstract suspend fun updateVisit(visit: SitterVisitEntity)

    @Query("DELETE FROM sitter_visits WHERE id IN (:ids)")
    abstract suspend fun deleteVisits(ids: List<Long>)

    @Insert
    abstract suspend fun insertVisitPlants(plants: List<SitterVisitPlantEntity>)

    @Query("DELETE FROM sitter_visit_plants WHERE visitId = :visitId")
    abstract suspend fun deleteVisitPlants(visitId: Long)

    @Query("DELETE FROM away_periods")
    abstract suspend fun deleteAll()

    @Transaction
    open suspend fun insertVisitWithPlants(visit: SitterVisitEntity, plants: List<SitterVisitPlantEntity>): Long {
        val visitId = insertVisit(visit)
        insertVisitPlants(plants.map { it.copy(visitId = visitId) })
        return visitId
    }

    @Transaction
    open suspend fun replaceVisitPlants(visit: SitterVisitEntity, plants: List<SitterVisitPlantEntity>) {
        updateVisit(visit)
        deleteVisitPlants(visit.id)
        insertVisitPlants(plants.map { it.copy(visitId = visit.id) })
    }

    @Transaction
    open suspend fun updatePeriodAndDeleteVisits(period: AwayPeriodEntity, visitIds: List<Long>) {
        updatePeriod(period)
        if (visitIds.isNotEmpty()) deleteVisits(visitIds)
    }

    // Backup restore: ids come from the (already de-duplicated) backup, so plain inserts are enough
    // and REPLACE's delete-then-insert (which would cascade) is never needed.
    @Insert
    abstract suspend fun insertPeriods(periods: List<AwayPeriodEntity>): List<Long>

    @Insert
    abstract suspend fun insertVisits(visits: List<SitterVisitEntity>): List<Long>
}
