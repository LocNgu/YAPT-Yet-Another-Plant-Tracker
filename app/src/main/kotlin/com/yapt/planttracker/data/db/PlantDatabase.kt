package com.yapt.planttracker.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.yapt.planttracker.data.entity.CareLogEntity
import com.yapt.planttracker.data.entity.CustomReminderEntity
import com.yapt.planttracker.data.entity.PlantEntity
import com.yapt.planttracker.data.entity.PlantPhotoEntity

@Database(
    entities = [PlantEntity::class, CareLogEntity::class, PlantPhotoEntity::class, CustomReminderEntity::class],
    version = PlantDatabase.DB_VERSION,
    exportSchema = true
)
abstract class PlantDatabase : RoomDatabase() {

    abstract fun plantDao(): PlantDao
    abstract fun careLogDao(): CareLogDao
    abstract fun plantPhotoDao(): PlantPhotoDao
    abstract fun customReminderDao(): CustomReminderDao

    companion object {
        // Single source of truth for the schema version, shared with the @Database
        // annotation above so the developer-mode build-info row can never drift from it (#520).
        const val DB_VERSION = 8

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

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plant_photos_new` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`plantId` INTEGER NOT NULL, " +
                        "`uri` TEXT NOT NULL, " +
                        "`capturedAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`plantId`) REFERENCES `plants`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "INSERT INTO plant_photos_new (id, plantId, uri, capturedAt) " +
                        "SELECT MIN(id), plantId, uri, MIN(capturedAt) FROM plant_photos GROUP BY plantId, uri"
                )
                db.execSQL("DROP TABLE plant_photos")
                db.execSQL("ALTER TABLE plant_photos_new RENAME TO plant_photos")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_plant_photos_plantId` ON `plant_photos` (`plantId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_plant_photos_plantId_uri` ON `plant_photos` (`plantId`, `uri`)"
                )
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plants ADD COLUMN archivedAt INTEGER")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plants ADD COLUMN repottingIntervalDays INTEGER")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `custom_reminders` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`plantId` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`intervalDays` INTEGER NOT NULL, " +
                        "`lastDoneAt` INTEGER, " +
                        "`createdAt` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`plantId`) REFERENCES `plants`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_custom_reminders_plantId` ON `custom_reminders` (`plantId`)"
                )
                // No FK constraint: a CUSTOM log's customReminderId may outlive its reminder (#232),
                // so a plain ALTER TABLE ADD COLUMN is sufficient — no table rebuild needed.
                db.execSQL("ALTER TABLE care_logs ADD COLUMN customReminderId INTEGER")
            }
        }

        fun getInstance(context: Context): PlantDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    PlantDatabase::class.java,
                    "yapt_database"
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
