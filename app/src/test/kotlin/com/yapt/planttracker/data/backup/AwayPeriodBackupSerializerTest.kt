package com.yapt.planttracker.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal fun fullAwayPeriod() = BackupAwayPeriod(
    id = 4000L,
    firstDay = "2026-10-10",
    lastDay = "2026-10-20",
    visitEveryDays = 7,
    createdAt = 1_650_000_000_000L,
    visits = listOf(
        BackupSitterVisit(
            id = 4100L,
            day = "2026-10-14",
            state = "CONFIRMED",
            createdAt = 1_650_000_100_000L,
            plants = listOf(
                BackupSitterVisitPlant(plantId = 1L, watered = true),
                BackupSitterVisitPlant(plantId = 2L, watered = false),
                BackupSitterVisitPlant(plantId = 3L, watered = null)
            )
        )
    )
)

class AwayPeriodBackupSerializerTest {

    private fun rootWithTrip() = BackupRoot(
        schemaVersion = 22,
        exportedAt = 1_700_000_000_000L,
        appVersion = "1.0",
        plants = emptyList(),
        careLogs = emptyList(),
        settings = BackupSettings(notificationsEnabled = true, reminderHour = 9, reminderMinute = 0),
        awayPeriods = listOf(fullAwayPeriod())
    )

    @Test
    fun `awayPeriods round-trips a trip with every field set and a confirmed visit`() {
        val decoded = backupJson.decodeFromString(
            BackupRoot.serializer(),
            backupJson.encodeToString(BackupRoot.serializer(), rootWithTrip())
        )
        assertEquals(listOf(fullAwayPeriod()), decoded.awayPeriods)
        val visit = decoded.awayPeriods[0].visits[0]
        assertEquals("CONFIRMED", visit.state)
        assertEquals(listOf(true, false, null), visit.plants.map { it.watered })
    }

    @Test
    fun `awayPeriods dates are written as ISO-8601 strings`() {
        val json = backupJson.encodeToString(BackupRoot.serializer(), rootWithTrip())
        assert(json.contains("\"firstDay\":\"2026-10-10\"")) { "Expected an ISO firstDay but got: $json" }
        assert(json.contains("\"day\":\"2026-10-14\"")) { "Expected an ISO visit day but got: $json" }
    }

    @Test
    fun `backup without awayPeriods defaults to no trips`() {
        val json = """
            {"schemaVersion":21,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[],"careLogs":[],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0}}
        """.trimIndent()
        val decoded = backupJson.decodeFromString(BackupRoot.serializer(), json)
        assertEquals(emptyList<BackupAwayPeriod>(), decoded.awayPeriods)
    }

    @Test
    fun `a minimal trip defaults to no rhythm and no visits`() {
        val json = """
            {"schemaVersion":22,"exportedAt":1700000000000,"appVersion":"1.0",
             "plants":[],"careLogs":[],
             "settings":{"notificationsEnabled":true,"reminderHour":9,"reminderMinute":0},
             "awayPeriods":[{"id":1,"firstDay":"2026-10-10","lastDay":"2026-10-20","createdAt":1}]}
        """.trimIndent()
        val trip = backupJson.decodeFromString(BackupRoot.serializer(), json).awayPeriods[0]
        assertNull(trip.visitEveryDays)
        assertEquals(emptyList<BackupSitterVisit>(), trip.visits)
    }
}
