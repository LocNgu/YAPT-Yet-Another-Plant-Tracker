package com.yapt.planttracker.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlantFamilyBackupTest {

    private fun root(parent: Long?) = BackupRoot(
        schemaVersion = CURRENT_SCHEMA_VERSION,
        exportedAt = 1_700_000_000_000L,
        appVersion = "1.0",
        plants = listOf(
            BackupPlant(id = 1L, name = "Mother", createdAt = 1L, updatedAt = 1L),
            BackupPlant(id = 2L, name = "Cutting", createdAt = 1L, updatedAt = 1L, propagatedFromId = parent)
        ),
        careLogs = listOf(
            BackupCareLog(id = 1L, plantId = 1L, careType = "PROPAGATE", loggedAt = 5L)
        ),
        settings = BackupSettings(notificationsEnabled = true, reminderHour = 9, reminderMinute = 0)
    )

    @Test
    fun `schema version is 24`() {
        assertEquals(24, CURRENT_SCHEMA_VERSION)
    }

    @Test
    fun `propagatedFromId and a PROPAGATE log round-trip`() {
        val json = backupJson.encodeToString(BackupRoot.serializer(), root(1L))

        val decoded = backupJson.decodeFromString(BackupRoot.serializer(), json)

        assertEquals(1L, decoded.plants[1].propagatedFromId)
        assertEquals("PROPAGATE", decoded.careLogs[0].careType)
    }

    @Test
    fun `a backup written before schema 24 imports with no parent`() {
        val json = """
            {"schemaVersion":23,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[{"id":1,"name":"Aloe","createdAt":1,"updatedAt":1}],
             "careLogs":[],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0}}
        """.trimIndent()

        val plant = backupJson.decodeFromString(BackupRoot.serializer(), json).plants[0]

        assertNull(plant.propagatedFromId)
    }

    @Test
    fun `valid links are kept`() {
        val links = sanitizePropagatedLinks(setOf(1, 2, 3), mapOf(1L to null, 2L to 1L, 3L to 2L))

        assertEquals(mapOf(2L to 1L, 3L to 2L), links)
    }

    @Test
    fun `dangling and self links are dropped`() {
        val links = sanitizePropagatedLinks(setOf(1, 2), mapOf(1L to 1L, 2L to 99L))

        assertTrue(links.isEmpty())
    }

    @Test
    fun `a two-plant cycle is dropped entirely`() {
        val links = sanitizePropagatedLinks(setOf(1, 2), mapOf(1L to 2L, 2L to 1L))

        assertTrue(links.isEmpty())
    }

    @Test
    fun `a longer cycle is dropped but a tail leading into it keeps its link`() {
        val links = sanitizePropagatedLinks(
            setOf(1, 2, 3, 4),
            mapOf(1L to 2L, 2L to 3L, 3L to 1L, 4L to 1L)
        )

        assertEquals(mapOf(4L to 1L), links)
    }
}
