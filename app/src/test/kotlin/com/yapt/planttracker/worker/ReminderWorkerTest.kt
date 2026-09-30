package com.yapt.planttracker.worker

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.datastore.preferences.core.edit
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.yapt.planttracker.R
import com.yapt.planttracker.YaptApplication
import com.yapt.planttracker.data.preferences.SettingsKeys
import com.yapt.planttracker.domain.model.CareLog
import com.yapt.planttracker.domain.model.CareType
import com.yapt.planttracker.domain.model.CustomReminder
import com.yapt.planttracker.domain.model.Plant
import com.yapt.planttracker.domain.schedule.SeasonalFertilizing
import com.yapt.planttracker.domain.schedule.SeasonalWatering
import com.yapt.planttracker.settingsDataStore
import com.yapt.planttracker.ui.util.repotPlannedNotificationRes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReminderWorkerTest {

    private lateinit var app: YaptApplication
    private lateinit var notificationManager: NotificationManager

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        clearDatabase()
        notificationManager = app.getSystemService(NotificationManager::class.java)
        notificationManager.cancelAll()
    }

    @After
    fun tearDown() {
        clearDatabase()
        notificationManager.cancelAll()
        // Drop the per-test preference overrides so tests stay order-independent.
        runBlocking {
            app.settingsDataStore.edit {
                it.remove(SettingsKeys.FERTILIZING_NOTIFICATIONS_ENABLED)
                it.remove(SettingsKeys.COMBINE_NOTIFICATIONS)
                it.remove(SettingsKeys.NOTIFICATIONS_ENABLED)
                it.remove(SettingsKeys.POST_WATERING_REMINDER_ENABLED)
            }
        }
    }

    private fun setFertilizingNotificationsEnabled(enabled: Boolean) = runBlocking {
        app.settingsDataStore.edit { it[SettingsKeys.FERTILIZING_NOTIFICATIONS_ENABLED] = enabled }
    }

    // Room's synchronous clearAllTables() would run on Robolectric's main thread and throw;
    // the DAO deletes are suspend and dispatch off it. Children before parents for the FK.
    private fun clearDatabase() = runBlocking {
        app.database.customReminderDao().deleteAll()
        app.database.careLogDao().deleteAll()
        app.database.plantPhotoDao().deleteAll()
        app.database.plantDao().deleteAll()
    }

    private fun runWorker(): ListenableWorker.Result =
        runBlocking { TestListenableWorkerBuilder<ReminderWorker>(app).build().doWork() }

    @Test
    fun `doWork returns success and posts nothing when notification permission is denied`() = runBlocking {
        // POST_NOTIFICATIONS is denied by default in Robolectric. Add a due plant to prove the
        // permission gate short-circuits before any notification is posted.
        app.plantRepository.addPlant(
            Plant(name = "Monstera", wateringIntervalDays = 3, createdAt = 0L, updatedAt = 0L)
        )

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, shadowOf(notificationManager).size())
    }

    @Test
    fun `doWork posts a reminder for a due plant when permission is granted`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // A never-watered plant with a watering interval is due today, so it yields one reminder.
        app.plantRepository.addPlant(
            Plant(name = "Fern", wateringIntervalDays = 5, createdAt = 0L, updatedAt = 0L)
        )

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, shadowOf(notificationManager).size())
    }

    @Test
    fun `doWork posts a reminder for a plant overdue for repotting`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Never-repotted plant: first-due anchors at createdAt + interval (far in the past here),
        // so it is overdue and the worker's conditional lastRepotting query must fire.
        app.plantRepository.addPlant(
            Plant(name = "Bonsai", repottingIntervalDays = 365, createdAt = 0L, updatedAt = 0L)
        )

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, shadowOf(notificationManager).size())
    }

    @Test
    fun `doWork still notifies a repotting-only plant when fertilizing notifications are off`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        setFertilizingNotificationsEnabled(false)
        // Regression (#232 + #223): turning off fertilizing notifications must not suppress an
        // unrelated repotting reminder. Never repotted, created at epoch -> long overdue.
        app.plantRepository.addPlant(
            Plant(
                name = "Bonsai",
                wateringIntervalDays = null,
                repottingIntervalDays = 365,
                createdAt = 0L,
                updatedAt = 0L
            )
        )

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, shadowOf(notificationManager).size())
    }

    @Test
    fun `doWork suppresses a fertilizing-only plant when fertilizing notifications are off`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        setFertilizingNotificationsEnabled(false)
        // Non-liquid plant, created at epoch (past the 30-day fertilize grace), no watering interval
        // -> only fertilizing is due.
        app.plantRepository.addPlant(
            Plant(
                name = "Pothos",
                wateringIntervalDays = null,
                fertilizingIntervalDays = 14,
                createdAt = 0L,
                updatedAt = 0L
            )
        )

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, shadowOf(notificationManager).size())
    }

    @Test
    fun `doWork still notifies a watering-and-fertilizing plant when fertilizing notifications are off`() =
        runBlocking {
            shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            setFertilizingNotificationsEnabled(false)
            // Watering due today (never watered, has interval) AND fertilizing overdue -> full reminder.
            app.plantRepository.addPlant(
                Plant(
                    name = "Calathea",
                    wateringIntervalDays = 5,
                    fertilizingIntervalDays = 14,
                    createdAt = 0L,
                    updatedAt = 0L
                )
            )

            val result = runWorker()

            assertEquals(ListenableWorker.Result.success(), result)
            assertEquals(1, shadowOf(notificationManager).size())
        }

    @Test
    fun `doWork posts a reminder for a plant overdue for a custom reminder`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val plantId = app.plantRepository.addPlant(
            Plant(name = "Sick Fiddle Leaf", createdAt = 0L, updatedAt = 0L)
        )
        app.customReminderRepository.addReminder(
            CustomReminder(
                plantId = plantId,
                name = "Neem oil treatment",
                intervalDays = 7,
                createdAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10)
            )
        )

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, shadowOf(notificationManager).size())
    }

    @Test
    fun `doWork reframes to a Check title with Watered and Not now when the plant is watering-due`() =
        runBlocking {
            shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            app.plantRepository.addPlant(
                Plant(name = "Fern", wateringIntervalDays = 5, createdAt = 0L, updatedAt = 0L)
            )

            runWorker()

            val notification = notificationManager.activeNotifications.first().notification
            assertEquals("Check Fern", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
            val actionTitles = notification.actions.orEmpty().map { it.title.toString() }
            assertEquals(listOf("Watered", "Not now"), actionTitles)
        }

    /**
     * #586 acceptance criterion: the action set is **fixed**, never varied by how overdue the plant
     * is. Unpredictable buttons between firings would cost more than the one attribution the fixed
     * set gives up. Narrowed from three to two actions by #738 (product ADR-0039), which drops
     * "Still moist" entirely.
     */
    @Test
    fun `doWork offers the same two actions however overdue the plant is`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val plantId = app.plantRepository.addPlant(
            Plant(name = "Fern", wateringIntervalDays = 5, createdAt = 0L, updatedAt = 0L)
        )
        app.careLogRepository.addLog(
            CareLog(
                plantId = plantId,
                careType = CareType.WATER,
                loggedAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(90)
            )
        )

        runWorker()

        val notification = notificationManager.activeNotifications.first().notification
        val actionTitles = notification.actions.orEmpty().map { it.title.toString() }
        assertEquals(listOf("Watered", "Not now"), actionTitles)
    }

    @Test
    fun `doWork does not reframe a repotting-only reminder`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // No watering interval -> not watering-due, so the check reframing must not apply;
        // this reminder is repotting-only.
        app.plantRepository.addPlant(
            Plant(
                name = "Bonsai",
                wateringIntervalDays = null,
                repottingIntervalDays = 365,
                createdAt = 0L,
                updatedAt = 0L
            )
        )

        runWorker()

        val notification = notificationManager.activeNotifications.first().notification
        assertEquals("Bonsai", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(0, notification.actions.orEmpty().size)
    }

    // ---- Planned repot (#809, product ADR-0057) ----

    /** The first day of the season today is in, in the hemisphere the worker itself resolves. */
    private fun currentSeasonStart(): LocalDate {
        val hemisphere = SeasonalWatering.currentHemisphere()
        val today = LocalDate.now()
        val season = SeasonalFertilizing.season(today, hemisphere)
        var start = today.withDayOfMonth(1)
        while (SeasonalFertilizing.season(start.minusMonths(1), hemisphere) == season) start = start.minusMonths(1)
        return start
    }

    private fun startOfDayMillis(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private suspend fun addPlanOnlyPlant(name: String, planSeasonStart: LocalDate) {
        app.plantRepository.addPlant(
            Plant(
                name = name,
                wateringIntervalDays = null,
                repottingIntervalDays = null,
                repotPlanSeasonStartAt = startOfDayMillis(planSeasonStart),
                repotPlanMadeAt = startOfDayMillis(planSeasonStart.minusMonths(6)),
                createdAt = 0L,
                updatedAt = 0L
            )
        )
    }

    @Test
    fun `doWork notifies a plan-only plant with no interval while its season is under way`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        setFertilizingNotificationsEnabled(false)
        val seasonStart = currentSeasonStart()
        addPlanOnlyPlant("Bonsai", seasonStart)

        runWorker()

        val season = SeasonalFertilizing.season(seasonStart, SeasonalWatering.currentHemisphere())
        val notification = notificationManager.activeNotifications.single().notification
        assertEquals("Bonsai", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(
            app.getString(season.repotPlannedNotificationRes()),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        )
        assertEquals(0, notification.actions.orEmpty().size)
    }

    @Test
    fun `doWork counts a lapsed plan's overdue days from its season end`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val currentStart = currentSeasonStart()
        addPlanOnlyPlant("Bonsai", currentStart.minusMonths(3))

        runWorker()

        // The previous season's last day is the day before the current one started.
        val days = ChronoUnit.DAYS.between(currentStart.minusDays(1), LocalDate.now()).toInt()
        val notification = notificationManager.activeNotifications.single().notification
        assertEquals(
            app.resources.getQuantityString(R.plurals.notification_repotting_plan_overdue, days, days),
            notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString()
        )
    }

    @Test
    fun `doWork posts nothing for a plan-only plant whose season is still ahead`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        addPlanOnlyPlant("Bonsai", currentSeasonStart().plusMonths(3))

        runWorker()

        assertEquals(0, shadowOf(notificationManager).size())
    }

    @Test
    fun `doWork posts nothing when no plant is due`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // No watering interval configured -> not scheduled -> no reminder.
        app.plantRepository.addPlant(
            Plant(name = "Cactus", wateringIntervalDays = null, createdAt = 0L, updatedAt = 0L)
        )

        val result = runWorker()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, shadowOf(notificationManager).size())
    }

    @Test
    fun `dormant watering posts no reminder in per-plant or combined mode`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val month = LocalDate.now().monthValue
        app.plantRepository.addPlant(
            Plant(
                name = "Dormant Cactus",
                wateringIntervalDays = 5,
                dormancyStartMonth = month,
                dormancyEndMonth = month,
                createdAt = 0L,
                updatedAt = 0L
            )
        )

        for (combined in listOf(false, true)) {
            app.settingsDataStore.edit { it[SettingsKeys.COMBINE_NOTIFICATIONS] = combined }
            notificationManager.cancelAll()
            assertEquals(ListenableWorker.Result.success(), runWorker())
            assertEquals(0, shadowOf(notificationManager).size())
        }
    }

    @Test
    fun `dormant-only cadence uses latest watering before deciding notification is due`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val month = LocalDate.now().monthValue
        val plantId = app.plantRepository.addPlant(
            Plant(
                name = "Dormant Cactus",
                wateringIntervalDays = null,
                dormancyStartMonth = month,
                dormancyEndMonth = month,
                dormantWateringIntervalDays = 28,
                createdAt = 0L,
                updatedAt = 0L
            )
        )
        app.careLogRepository.addLog(
            CareLog(plantId = plantId, careType = CareType.WATER, loggedAt = System.currentTimeMillis())
        )
        // Insert an older backfill afterward: chronological recency, not insertion order, owns the cadence anchor.
        app.careLogRepository.addLog(
            CareLog(
                plantId = plantId,
                careType = CareType.WATER,
                loggedAt = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(60)
            )
        )

        assertEquals(ListenableWorker.Result.success(), runWorker())
        assertEquals(0, shadowOf(notificationManager).size())
    }

    @Test
    fun `daily cleanup preserves the independent post-watering notification`() = runBlocking {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // The settings DataStore is a process-wide singleton, so another test class (e.g. BootReceiverTest)
        // can leave either switch off; the post-watering worker posts nothing then, so set both explicitly.
        app.settingsDataStore.edit {
            it[SettingsKeys.NOTIFICATIONS_ENABLED] = true
            it[SettingsKeys.POST_WATERING_REMINDER_ENABLED] = true
        }
        TestListenableWorkerBuilder<PostWateringReminderWorker>(app).build().doWork()
        app.plantRepository.addPlant(
            Plant(name = "Cactus", wateringIntervalDays = null, createdAt = 0L, updatedAt = 0L)
        )

        runWorker()

        assertEquals(
            listOf(PostWateringReminderWorker.NOTIFICATION_ID),
            notificationManager.activeNotifications.map { it.id }
        )
    }
}
