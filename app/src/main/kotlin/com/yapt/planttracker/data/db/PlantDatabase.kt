package com.yapt.planttracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.yapt.planttracker.data.entity.CareLogEntity
import com.yapt.planttracker.data.entity.PlantEntity

@Database(
    entities = [PlantEntity::class, CareLogEntity::class],
    version = 1,
    exportSchema = true
)
abstract class PlantDatabase : RoomDatabase() {

    abstract fun plantDao(): PlantDao
    abstract fun careLogDao(): CareLogDao

    companion object {
        @Volatile
        private var INSTANCE: PlantDatabase? = null

        fun getInstance(context: Context): PlantDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    PlantDatabase::class.java,
                    "yapt_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
