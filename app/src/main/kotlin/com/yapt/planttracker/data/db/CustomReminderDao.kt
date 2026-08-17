package com.yapt.planttracker.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yapt.planttracker.data.entity.CustomReminderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CustomReminderDao {

    @Query("SELECT * FROM custom_reminders WHERE plantId = :plantId ORDER BY createdAt ASC")
    fun getRemindersForPlant(plantId: Long): Flow<List<CustomReminderEntity>>

    @Query("SELECT * FROM custom_reminders WHERE plantId = :plantId ORDER BY createdAt ASC")
    suspend fun getRemindersForPlantOnce(plantId: Long): List<CustomReminderEntity>

    @Query("SELECT * FROM custom_reminders WHERE id = :id LIMIT 1")
    suspend fun getReminderById(id: Long): CustomReminderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: CustomReminderEntity): Long

    @Update
    suspend fun updateReminder(reminder: CustomReminderEntity)

    @Delete
    suspend fun deleteReminder(reminder: CustomReminderEntity)

    @Query("DELETE FROM custom_reminders")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(reminders: List<CustomReminderEntity>): List<Long>
}
