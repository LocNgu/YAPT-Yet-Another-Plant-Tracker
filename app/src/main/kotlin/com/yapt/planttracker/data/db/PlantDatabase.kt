package com.yapt.planttracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yapt.planttracker.data.entity.CareLogEntity
import com.yapt.planttracker.data.entity.PlantEntity

@Database(
    entities = [PlantEntity::class, CareLogEntity::class],
    version = 2,
    exportSchema = true
)
abstract class PlantDatabase : RoomDatabase() {

    abstract fun plantDao(): PlantDao
    abstract fun careLogDao(): CareLogDao

    companion object {
        @Volatile
        private var INSTANCE: PlantDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plants ADD COLUMN wateringDueDateOverride INTEGER")
            }
        }

        fun getInstance(context: Context): PlantDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    PlantDatabase::class.java,
                    "yapt_database"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
