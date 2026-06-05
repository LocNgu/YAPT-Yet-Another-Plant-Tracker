package com.yapt.planttracker.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupSerializerTest {

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
                createdAt = 1_000_000_000_000L,
                updatedAt = 1_100_000_000_000L
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
                wateringFeedback = "JUST_RIGHT"
            )
        ),
        settings = BackupSettings(
            notificationsEnabled = true,
            reminderHour = 9,
            reminderMinute = 0
        )
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
}
