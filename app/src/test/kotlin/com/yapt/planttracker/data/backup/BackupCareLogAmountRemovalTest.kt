package com.yapt.planttracker.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// The Amount field was dropped in #442. Backups written before that still carry "amount" on each care log and
// must keep restoring, so the backup schema version did not need a bump (rules/backup.md).
class BackupCareLogAmountRemovalTest {

    private val olderBackup = """
        {"schemaVersion":24,"exportedAt":1700000000000,"appVersion":"0.40.0",
         "plants":[],
         "careLogs":[{"id":5,"plantId":1,"careType":"WATER","loggedAt":1600000000000,
                      "notes":"Soaked","photoUri":null,"amount":"250 ml","wateringFeedback":"TOO_LATE",
                      "fertilizerType":"UNSPECIFIED","customReminderId":null}],
         "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0}}
    """.trimIndent()

    @Test
    fun `an older backup whose care logs carry amount still deserializes with every other field`() {
        val log = backupJson.decodeFromString(BackupRoot.serializer(), olderBackup).careLogs[0]

        assertEquals(5L, log.id)
        assertEquals("WATER", log.careType)
        assertEquals("Soaked", log.notes)
        assertEquals("TOO_LATE", log.wateringFeedback)
    }

    @Test
    fun `a new export no longer writes amount`() {
        val log = backupJson.decodeFromString(BackupRoot.serializer(), olderBackup).careLogs[0]

        val json = backupJson.encodeToString(BackupCareLog.serializer(), log)

        assertFalse("amount must not be written any more: $json", json.contains("amount"))
    }
}
