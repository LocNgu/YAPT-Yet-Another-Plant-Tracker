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
import com.yapt.planttracker.data.entity.PlantIssueEntity
import com.yapt.planttracker.data.entity.PlantPhotoEntity
import com.yapt.planttracker.data.entity.WateringAdjustmentEntity
import com.yapt.planttracker.domain.schedule.SeasonalAmplitude
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import java.time.LocalDate

@Database(
    entities = [
        PlantEntity::class,
        CareLogEntity::class,
        PlantPhotoEntity::class,
        CustomReminderEntity::class,
        PlantIssueEntity::class,
        WateringAdjustmentEntity::class
    ],
    version = PlantDatabase.DB_VERSION,
    exportSchema = true
)
abstract class PlantDatabase : RoomDatabase() {

    abstract fun plantDao(): PlantDao
    abstract fun careLogDao(): CareLogDao
    abstract fun plantPhotoDao(): PlantPhotoDao
    abstract fun customReminderDao(): CustomReminderDao
    abstract fun plantIssueDao(): PlantIssueDao
    abstract fun wateringAdjustmentDao(): WateringAdjustmentDao

    companion object {
        // Single source of truth for the schema version, shared with the @Database
        // annotation above so the developer-mode build-info row can never drift from it (#520).
        const val DB_VERSION = 13

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

        @Suppress("MagicNumber")
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `plant_issues` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`plantId` INTEGER NOT NULL, " +
                        "`name` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, " +
                        "`resolvedAt` INTEGER, " +
                        "`resolutionNote` TEXT, " +
                        "`linkedReminderId` INTEGER, " +
                        "FOREIGN KEY(`plantId`) REFERENCES `plants`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_plant_issues_plantId` ON `plant_issues` (`plantId`)"
                )
            }
        }

        // #568: wateringConfidence backs the multiplicative + confidence-weighted adaptive watering
        // model (technical ADR-0021). Ships unconditionally regardless of `adaptive_watering` flag
        // state, so flipping the flag off/on never loses learned state.
        @Suppress("MagicNumber")
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plants ADD COLUMN wateringConfidence INTEGER")
            }
        }

        // #569 (product ADR-0026): wateringBaseIntervalDays is a season-neutral reference used only
        // when SEASONAL_WATERING is on and the plant isn't pinned. Not a pure ALTER — every existing
        // plant's base is de-seasonalized to *migration day* (`wateringIntervalDays / season(today)`)
        // so its effective interval on migration day is unchanged, regardless of what month the
        // migration happens to run in. Always uses SeasonalAmplitude.STANDARD (a Room migration can't
        // read the not-yet-chosen DataStore amplitude setting synchronously, and STANDARD is the
        // registry default once the flag is turned on). pinIntervalToBase defaults false for every
        // existing row. Both columns ship unconditionally regardless of the flag's state.
        @Suppress("MagicNumber")
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plants ADD COLUMN wateringBaseIntervalDays REAL")
                db.execSQL("ALTER TABLE plants ADD COLUMN pinIntervalToBase INTEGER NOT NULL DEFAULT 0")

                val migrationDate = LocalDate.now()
                val seasonFactor = SeasonalWatering.season(
                    migrationDate,
                    SeasonalAmplitude.STANDARD.value,
                    SeasonalWatering.currentHemisphere()
                )
                db.query("SELECT id, wateringIntervalDays FROM plants WHERE wateringIntervalDays IS NOT NULL")
                    .use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(0)
                            val intervalDays = cursor.getInt(1)
                            val base = intervalDays / seasonFactor
                            db.execSQL(
                                "UPDATE plants SET wateringBaseIntervalDays = ? WHERE id = ?",
                                arrayOf<Any>(base, id)
                            )
                        }
                    }
            }
        }

        // #572: watering_adjustments backs the "Why this date?" sheet's "Recent adjustments" list — a
        // dedicated table (not a CareLog replay, product ADR-0028) since a dialog dismissal or manual
        // interval edit changes wateringConfidence/base without ever writing a CareLog row. Ships
        // unconditionally regardless of `adaptive_watering` flag state, mirroring wateringConfidence's
        // precedent — rows are only ever written while the flag is on, but the table itself always exists.
        @Suppress("MagicNumber")
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `watering_adjustments` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`plantId` INTEGER NOT NULL, " +
                        "`triggeredAt` INTEGER NOT NULL, " +
                        "`trigger` TEXT NOT NULL, " +
                        "`beforeIntervalDays` INTEGER NOT NULL, " +
                        "`afterIntervalDays` INTEGER NOT NULL, " +
                        "FOREIGN KEY(`plantId`) REFERENCES `plants`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_watering_adjustments_plantId` ON `watering_adjustments` (`plantId`)"
                )
            }
        }

        // #571: wateringResetAt/wateringFreezeUntil back the REPOT/room-change confidence-reset
        // lifecycle events — plain columns written once as a side effect at reset time (never derived
        // live from querying REPOT log history), so editing/deleting a past REPOT log can't spuriously
        // re-trigger a reset. Ship unconditionally regardless of `adaptive_watering` flag state,
        // mirroring wateringConfidence's precedent.
        @Suppress("MagicNumber")
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plants ADD COLUMN wateringResetAt INTEGER")
                db.execSQL("ALTER TABLE plants ADD COLUMN wateringFreezeUntil INTEGER")
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
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                        MIGRATION_11_12,
                        MIGRATION_12_13
                    )
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
