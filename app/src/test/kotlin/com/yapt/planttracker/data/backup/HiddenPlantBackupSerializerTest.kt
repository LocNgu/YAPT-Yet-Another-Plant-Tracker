package com.yapt.planttracker.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HiddenPlantBackupSerializerTest {

    private fun root(hiddenAt: Long?) = BackupRoot(
        schemaVersion = CURRENT_SCHEMA_VERSION,
        exportedAt = 1_700_000_000_000L,
        appVersion = "1.0",
        plants = listOf(
            BackupPlant(
                id = 1L,
                name = "Aloe",
                createdAt = 1_000_000_000_000L,
                updatedAt = 1_100_000_000_000L,
                hiddenAt = hiddenAt
            )
        ),
        careLogs = emptyList(),
        settings = BackupSettings(notificationsEnabled = true, reminderHour = 9, reminderMinute = 0)
    )

    @Test
    fun `hiddenAt round-trips its stored value`() {
        val json = backupJson.encodeToString(BackupRoot.serializer(), root(1_694_000_000_000L))

        val decoded = backupJson.decodeFromString(BackupRoot.serializer(), json)

        assertEquals(1_694_000_000_000L, decoded.plants[0].hiddenAt)
    }

    @Test
    fun `a visible plant stays visible through a round trip`() {
        val json = backupJson.encodeToString(BackupRoot.serializer(), root(null))

        assertNull(backupJson.decodeFromString(BackupRoot.serializer(), json).plants[0].hiddenAt)
    }

    @Test
    fun `plant from a backup written before hiddenAt imports visible`() {
        val json = """
            {"schemaVersion":22,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[{"id":1,"name":"Aloe","createdAt":1000000000000,"updatedAt":1100000000000}],
             "careLogs":[],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0}}
        """.trimIndent()

        val plant = backupJson.decodeFromString(BackupRoot.serializer(), json).plants[0]

        assertNull(plant.hiddenAt)
    }
}
