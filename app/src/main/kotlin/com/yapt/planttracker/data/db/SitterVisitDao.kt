package com.yapt.planttracker.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import com.yapt.planttracker.data.entity.SitterVisitEntity
import com.yapt.planttracker.data.entity.SitterVisitPlantEntity
import kotlinx.coroutines.flow.Flow

data class SitterVisitWithPlants(
    @Embedded val visit: SitterVisitEntity,
    @Relation(parentColumn = "id", entityColumn = "visitId")
    val plants: List<SitterVisitPlantEntity>
)

@Dao
interface SitterVisitDao {

    @Transaction
    @Query("SELECT * FROM sitter_visits WHERE awayPeriodId = :awayPeriodId ORDER BY day, id")
    fun observeVisits(awayPeriodId: Long): Flow<List<SitterVisitWithPlants>>

    @Transaction
    @Query("SELECT * FROM sitter_visits WHERE awayPeriodId = :awayPeriodId ORDER BY day, id")
    suspend fun getVisits(awayPeriodId: Long): List<SitterVisitWithPlants>

    @Transaction
    @Query("SELECT * FROM sitter_visits ORDER BY awayPeriodId, day, id")
    suspend fun getAllVisits(): List<SitterVisitWithPlants>

    @Insert
    suspend fun insertVisit(visit: SitterVisitEntity): Long

    @Update
    suspend fun updateVisit(visit: SitterVisitEntity)

    @Query("DELETE FROM sitter_visit_plants WHERE visitId = :visitId")
    suspend fun deleteVisitPlants(visitId: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVisitPlants(plants: List<SitterVisitPlantEntity>)

    /** Inserts or updates [visit] and replaces its whole plant list; returns the visit's id. */
    @Transaction
    suspend fun saveVisit(visit: SitterVisitEntity, plants: List<SitterVisitPlantEntity>): Long {
        val visitId = if (visit.id == 0L) {
            insertVisit(visit)
        } else {
            updateVisit(visit)
            visit.id
        }
        deleteVisitPlants(visitId)
        insertVisitPlants(plants.map { it.copy(visitId = visitId) })
        return visitId
    }
}
