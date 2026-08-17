package com.yapt.planttracker.data.repository

import com.yapt.planttracker.data.db.CustomReminderDao
import com.yapt.planttracker.data.entity.CustomReminderEntity
import com.yapt.planttracker.domain.model.CustomReminder
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class CustomReminderRepository(private val customReminderDao: CustomReminderDao) {

    fun getRemindersForPlant(plantId: Long): Flow<List<CustomReminder>> =
        customReminderDao.getRemindersForPlant(plantId).map { list -> list.map { it.toDomain() } }

    suspend fun getRemindersForPlantOnce(plantId: Long): List<CustomReminder> =
        customReminderDao.getRemindersForPlantOnce(plantId).map { it.toDomain() }

    suspend fun getReminderById(id: Long): CustomReminder? =
        customReminderDao.getReminderById(id)?.toDomain()

    suspend fun addReminder(reminder: CustomReminder): Long =
        customReminderDao.insertReminder(reminder.toEntity())

    suspend fun updateReminder(reminder: CustomReminder) =
        customReminderDao.updateReminder(reminder.toEntity())

    suspend fun deleteReminder(reminder: CustomReminder) =
        customReminderDao.deleteReminder(reminder.toEntity())
}

private fun CustomReminderEntity.toDomain() = CustomReminder(
    id = id,
    plantId = plantId,
    name = name,
    intervalDays = intervalDays,
    lastDoneAt = lastDoneAt,
    createdAt = createdAt
)

private fun CustomReminder.toEntity() = CustomReminderEntity(
    id = id,
    plantId = plantId,
    name = name,
    intervalDays = intervalDays,
    lastDoneAt = lastDoneAt,
    createdAt = createdAt
)
