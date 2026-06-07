package com.yapt.planttracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yapt.planttracker.data.entity.CareLogEntity
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.data.entity.PlantPhotoEntity

@Database(
    entities = [PlantEntity::class, CareLogEntity::class, PlantPhotoEntity::class],
    version = 4,
    exportSchema = true
)
abstract class PlantDatabase : RoomDatabase() {

    abstract fun plantDao(): PlantDao
    abstract fun careLogDao(): CareLogDao
    abstract fun plantPhotoDao(): PlantPhotoDao

    companion object {
        @Volatile
        private var INSTANCE: PlantDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plants ADD COLUMN wateringDueDateOverride INTEGER")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plants ADD COLUMN useLiquidFertilizer INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE care_logs ADD COLUMN fertilizerType TEXT NOT NULL DEFAULT 'UNSPECIFIED'")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plant_photos` (" +
                    "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`plantId` INTEGER NOT NULL, " +
                    "`uri` TEXT NOT NULL, " +
                    "`capturedAt` INTEGER NOT NULL, " +
                    "FOREIGN KEY(`plantId`) REFERENCES `plants`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_plant_photos_plantId` ON `plant_photos` (`plantId`)"
                )
                db.execSQL(
                    "INSERT INTO plant_photos (plantId, uri, capturedAt) " +
                    "SELECT id, coverPhotoUri, createdAt FROM plants WHERE coverPhotoUri IS NOT NULL"
                )
            }
        }

        fun getInstance(context: Context): PlantDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    PlantDatabase::class.java,
                    "yapt_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
