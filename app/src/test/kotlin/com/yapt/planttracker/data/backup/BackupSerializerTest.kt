package com.yapt.planttracker.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupSerializerTest {

    private val defaultCustomReminder = BackupCustomReminder(
        id = 1000L,
        plantId = 1L,
        name = "Neem oil treatment",
        intervalDays = 7,
        lastDoneAt = 1_690_000_000_000L,
        createdAt = 1_600_000_000_000L
    )

    private val defaultPlantIssue = BackupPlantIssue(
        id = 2000L,
        plantId = 1L,
        name = "Spider mites",
        startedAt = 1_600_000_000_000L,
        resolvedAt = 1_650_000_000_000L,
        resolutionNote = "Treated with neem oil",
        linkedReminderId = 1000L
    )

    private fun fullRoot() = BackupRoot(
        schemaVersion = 1,
        exportedAt = 1_700_000_000_000L,
        appVersion = "1.0",
        plants = listOf(
            BackupPlant(
                id = 1L,
                name = "Monstera",
                species = "Monstera deliciosa",
                room = "Living room",
                coverPhotoUri = "content://uri/photo.jpg",
                notes = "Loves humidity",
                wateringIntervalDays = 7,
                fertilizingIntervalDays = 14,
                repottingIntervalDays = 365,
                createdAt = 1_000_000_000_000L,
                updatedAt = 1_100_000_000_000L,
                wateringDueDateOverride = 1_700_000_000_000L,
                useLiquidFertilizer = true,
                wateringConfidence = 3,
                wateringBaseIntervalDays = 5.18,
                pinIntervalToBase = true
            )
        ),
        careLogs = listOf(
            BackupCareLog(
                id = 10L,
                plantId = 1L,
                careType = "WATER",
                loggedAt = 1_600_000_000_000L,
                notes = "Watered well",
                photoUri = "content://uri/log.jpg",
                amount = "500ml",
                wateringFeedback = "JUST_RIGHT",
                fertilizerType = "LIQUID"
            )
        ),
        settings = BackupSettings(
            notificationsEnabled = true,
            reminderHour = 9,
            reminderMinute = 0,
            keepScreenOn = true,
            combineNotifications = true,
            photoReminderEnabled = true,
            themeMode = "DARK",
            fertilizingNotificationsEnabled = false
        ),
        plantPhotos = listOf(
            BackupPlantPhoto(
                id = 100L,
                plantId = 1L,
                uri = "content://uri/gallery.jpg",
                capturedAt = 1_650_000_000_000L
            )
        ),
        customReminders = listOf(defaultCustomReminder),
        plantIssues = listOf(defaultPlantIssue)
    )

    @Test
    fun `round-trip full BackupRoot`() {
        val original = fullRoot()
        val json = backupJson.encodeToString(BackupRoot.serializer(), original)
        val decoded = backupJson.decodeFromString(BackupRoot.serializer(), json)
        assertEquals(original, decoded)
    }

    @Test
    fun `ignoreUnknownKeys allows future fields`() {
        val json = """
            {"schemaVersion":1,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[],"careLogs":[],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0},
             "foo":42,"bar":"future"}
        """.trimIndent()
        val decoded = backupJson.decodeFromString(BackupRoot.serializer(), json)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(0, decoded.plants.size)
    }

    @Test
    fun `missing optional plant fields default to null`() {
        val json = """
            {"schemaVersion":1,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[{"id":1,"name":"Aloe","createdAt":1000000000000,"updatedAt":1100000000000}],
             "careLogs":[],
             "settings":{"notificationsEnabled":false,"reminderHour":8,"reminderMinute":30}}
        """.trimIndent()
        val plant = backupJson.decodeFromString(BackupRoot.serializer(), json).plants[0]
        assertNull(plant.species)
        assertNull(plant.room)
        assertNull(plant.coverPhotoUri)
        assertNull(plant.notes)
        assertNull(plant.wateringIntervalDays)
        assertNull(plant.fertilizingIntervalDays)
        assertNull(plant.wateringDueDateOverride)
    }

    @Test
    fun `missing optional care log fields default to null`() {
        val json = """
            {"schemaVersion":1,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[],
             "careLogs":[{"id":5,"plantId":1,"careType":"WATER","loggedAt":1600000000000}],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0}}
        """.trimIndent()
        val log = backupJson.decodeFromString(BackupRoot.serializer(), json).careLogs[0]
        assertNull(log.notes)
        assertNull(log.photoUri)
        assertNull(log.amount)
        assertNull(log.wateringFeedback)
    }

    @Test
    fun `settings without themeMode default to SYSTEM`() {
        val json = """
            {"schemaVersion":5,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[],"careLogs":[],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0}}
        """.trimIndent()
        val decoded = backupJson.decodeFromString(BackupRoot.serializer(), json)
        assertEquals("SYSTEM", decoded.settings.themeMode)
    }

    @Test
    fun `settings themeMode round-trips its stored value`() {
        val json = """
            {"schemaVersion":6,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[],"careLogs":[],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0,"themeMode":"DARK"}}
        """.trimIndent()
        val decoded = backupJson.decodeFromString(BackupRoot.serializer(), json)
        assertEquals("DARK", decoded.settings.themeMode)
    }

    @Test
    fun `settings without fertilizingNotificationsEnabled default to true`() {
        val json = """
            {"schemaVersion":6,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[],"careLogs":[],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0}}
        """.trimIndent()
        val decoded = backupJson.decodeFromString(BackupRoot.serializer(), json)
        assertEquals(true, decoded.settings.fertilizingNotificationsEnabled)
    }

    @Test
    fun `settings fertilizingNotificationsEnabled round-trips its stored value`() {
        val json = """
            {"schemaVersion":7,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[],"careLogs":[],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0,
             "fertilizingNotificationsEnabled":false}}
        """.trimIndent()
        val decoded = backupJson.decodeFromString(BackupRoot.serializer(), json)
        assertEquals(false, decoded.settings.fertilizingNotificationsEnabled)
    }

    @Test
    fun `older schemaVersion 0 deserialises without error`() {
        val json = """
            {"schemaVersion":0,"exportedAt":1700000000000,"appVersion":"0.9",
             "plants":[],"careLogs":[],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0}}
        """.trimIndent()
        val decoded = backupJson.decodeFromString(BackupRoot.serializer(), json)
        assertEquals(0, decoded.schemaVersion)
    }

    @Test
    fun `all-null optional fields round-trip`() {
        val root = BackupRoot(
            schemaVersion = 1,
            exportedAt = 1_700_000_000_000L,
            appVersion = "1.0",
            plants = listOf(
                BackupPlant(
                    id = 2L,
                    name = "Cactus",
                    createdAt = 1_000_000_000_000L,
                    updatedAt = 1_000_000_000_000L
                )
            ),
            careLogs = listOf(
                BackupCareLog(
                    id = 5L,
                    plantId = 2L,
                    careType = "FERTILIZE",
                    loggedAt = 1_600_000_000_000L
                )
            ),
            settings = BackupSettings(
                notificationsEnabled = false,
                reminderHour = 8,
                reminderMinute = 0
            )
        )
        val decoded = backupJson.decodeFromString(
            BackupRoot.serializer(),
            backupJson.encodeToString(BackupRoot.serializer(), root)
        )
        assertEquals(root, decoded)
        assertNull(decoded.plants[0].species)
        assertNull(decoded.careLogs[0].wateringFeedback)
    }

    @Test
    fun `encodeDefaults=true emits null fields in serialized JSON`() {
        val plant = BackupPlant(
            id = 1L,
            name = "Aloe",
            species = null,
            room = null,
            coverPhotoUri = null,
            notes = null,
            wateringIntervalDays = null,
            fertilizingIntervalDays = null,
            createdAt = 1_000_000_000_000L,
            updatedAt = 1_000_000_000_000L,
            wateringDueDateOverride = null
            // useLiquidFertilizer omitted — Boolean default (false), not a nullable field
        )
        val json = backupJson.encodeToString(BackupPlant.serializer(), plant)
        assert(json.contains("\"species\":null")) { "Expected explicit null for species but got: $json" }
    }

    @Test
    fun `multiple plants and logs round-trip`() {
        val root = fullRoot().copy(
            plants = listOf(
                BackupPlant(id = 1L, name = "A", createdAt = 1_000L, updatedAt = 1_000L),
                BackupPlant(id = 2L, name = "B", species = "Sp", createdAt = 2_000L, updatedAt = 2_000L)
            ),
            careLogs = listOf(
                BackupCareLog(id = 1L, plantId = 1L, careType = "WATER", loggedAt = 1_000L),
                BackupCareLog(id = 2L, plantId = 2L, careType = "FERTILIZE", loggedAt = 2_000L, wateringFeedback = null)
            )
        )
        val decoded = backupJson.decodeFromString(
            BackupRoot.serializer(),
            backupJson.encodeToString(BackupRoot.serializer(), root)
        )
        assertEquals(2, decoded.plants.size)
        assertEquals(2, decoded.careLogs.size)
        assertEquals("A", decoded.plants[0].name)
        assertEquals("Sp", decoded.plants[1].species)
    }

    @Test
    fun `customReminders round-trips its stored values`() {
        val decoded = backupJson.decodeFromString(
            BackupRoot.serializer(),
            backupJson.encodeToString(BackupRoot.serializer(), fullRoot())
        )
        assertEquals(1, decoded.customReminders.size)
        val reminder = decoded.customReminders[0]
        assertEquals("Neem oil treatment", reminder.name)
        assertEquals(7, reminder.intervalDays)
        assertEquals(1_690_000_000_000L, reminder.lastDoneAt)
    }

    @Test
    fun `backup without customReminders defaults to an empty list`() {
        val json = """
            {"schemaVersion":8,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[],"careLogs":[],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0}}
        """.trimIndent()
        val decoded = backupJson.decodeFromString(BackupRoot.serializer(), json)
        assertEquals(emptyList<BackupCustomReminder>(), decoded.customReminders)
    }

    @Test
    fun `careLog customReminderId round-trips its stored value`() {
        val log = BackupCareLog(
            id = 1L,
            plantId = 1L,
            careType = "CUSTOM",
            loggedAt = 1_000L,
            customReminderId = 42L
        )
        val decoded = backupJson.decodeFromString(
            BackupCareLog.serializer(),
            backupJson.encodeToString(BackupCareLog.serializer(), log)
        )
        assertEquals(42L, decoded.customReminderId)
    }

    @Test
    fun `careLog without customReminderId defaults to null`() {
        val json = """
            {"schemaVersion":8,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[],
             "careLogs":[{"id":5,"plantId":1,"careType":"CUSTOM","loggedAt":1600000000000}],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0}}
        """.trimIndent()
        val log = backupJson.decodeFromString(BackupRoot.serializer(), json).careLogs[0]
        assertNull(log.customReminderId)
    }

    @Test
    fun `plantIssues round-trips its stored values`() {
        val decoded = backupJson.decodeFromString(
            BackupRoot.serializer(),
            backupJson.encodeToString(BackupRoot.serializer(), fullRoot())
        )
        assertEquals(1, decoded.plantIssues.size)
        val issue = decoded.plantIssues[0]
        assertEquals("Spider mites", issue.name)
        assertEquals(1_650_000_000_000L, issue.resolvedAt)
        assertEquals("Treated with neem oil", issue.resolutionNote)
        assertEquals(1000L, issue.linkedReminderId)
    }

    @Test
    fun `backup without plantIssues defaults to an empty list`() {
        val json = """
            {"schemaVersion":9,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[],"careLogs":[],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0}}
        """.trimIndent()
        val decoded = backupJson.decodeFromString(BackupRoot.serializer(), json)
        assertEquals(emptyList<BackupPlantIssue>(), decoded.plantIssues)
    }

    @Test
    fun `active plantIssue omits resolvedAt and resolutionNote`() {
        val json = """
            {"schemaVersion":10,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[],"careLogs":[],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0},
             "plantIssues":[{"id":1,"plantId":1,"name":"Root rot","startedAt":1600000000000}]}
        """.trimIndent()
        val issue = backupJson.decodeFromString(BackupRoot.serializer(), json).plantIssues[0]
        assertNull(issue.resolvedAt)
        assertNull(issue.resolutionNote)
        assertNull(issue.linkedReminderId)
    }

    @Test
    fun `wateringConfidence round-trips its stored value`() {
        val decoded = backupJson.decodeFromString(
            BackupRoot.serializer(),
            backupJson.encodeToString(BackupRoot.serializer(), fullRoot())
        )
        assertEquals(3, decoded.plants[0].wateringConfidence)
    }

    @Test
    fun `plant without wateringConfidence defaults to null`() {
        val json = """
            {"schemaVersion":10,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[{"id":1,"name":"Aloe","createdAt":1000000000000,"updatedAt":1100000000000}],
             "careLogs":[],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0}}
        """.trimIndent()
        val plant = backupJson.decodeFromString(BackupRoot.serializer(), json).plants[0]
        assertNull(plant.wateringConfidence)
    }

    @Test
    fun `wateringBaseIntervalDays and pinIntervalToBase round-trip their stored values`() {
        val decoded = backupJson.decodeFromString(
            BackupRoot.serializer(),
            backupJson.encodeToString(BackupRoot.serializer(), fullRoot())
        )
        assertEquals(5.18, decoded.plants[0].wateringBaseIntervalDays!!, 1e-9)
        assertEquals(true, decoded.plants[0].pinIntervalToBase)
    }

    @Test
    fun `plant without wateringBaseIntervalDays or pinIntervalToBase defaults to null and false`() {
        val json = """
            {"schemaVersion":10,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[{"id":1,"name":"Aloe","createdAt":1000000000000,"updatedAt":1100000000000}],
             "careLogs":[],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0}}
        """.trimIndent()
        val plant = backupJson.decodeFromString(BackupRoot.serializer(), json).plants[0]
        assertNull(plant.wateringBaseIntervalDays)
        assertEquals(false, plant.pinIntervalToBase)
    }
}
