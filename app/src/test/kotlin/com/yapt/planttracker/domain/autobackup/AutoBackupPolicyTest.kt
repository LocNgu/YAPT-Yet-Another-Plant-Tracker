package com.yapt.planttracker.domain.autobackup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AutoBackupPolicyTest {

    @Test
    fun `selectPrunable keeps the newest N and only matches the exact pattern`() {
        val names = listOf(
            "yapt-backup-2026-11-01.yapt",
            "yapt-backup-2026-11-04.yapt",
            "yapt-backup-2026-10-30.yapt",
            "yapt-backup-2026-11-02.yapt",
            "yapt-backup-2026-11-04 (1).yapt",
            "notes.txt",
            "yapt-backup-2026-13-45.yapt",
            "my-yapt-backup-2026-01-01.yapt"
        )
        assertEquals(
            listOf("yapt-backup-2026-11-01.yapt", "yapt-backup-2026-10-30.yapt"),
            AutoBackupFiles.selectPrunable(names, keep = 2)
        )
    }

    @Test
    fun `selectPrunable deletes nothing when under the limit`() {
        assertTrue(AutoBackupFiles.selectPrunable(listOf("yapt-backup-2026-11-01.yapt"), 3).isEmpty())
    }

    @Test
    fun `file name round trips`() {
        val date = LocalDate.of(2026, 1, 5)
        assertEquals("yapt-backup-2026-01-05.yapt", AutoBackupFiles.fileName(date))
        assertEquals(date, AutoBackupFiles.parseDate(AutoBackupFiles.fileName(date)))
        assertNull(AutoBackupFiles.parseDate("yapt-backup-2026-1-5.yapt"))
    }

    @Test
    fun `temp names are recognised exactly and never parse as a backup`() {
        val date = LocalDate.of(2026, 1, 5)
        val temp = AutoBackupFiles.tempFileName(date, 1700000000000L)
        assertEquals("yapt-backup-2026-01-05.yapt.1700000000000.partial", temp)
        assertTrue(AutoBackupFiles.isTempName(temp))
        assertNull(AutoBackupFiles.parseDate(temp))
        assertFalse(AutoBackupFiles.isTempName(AutoBackupFiles.fileName(date)))
        assertFalse(AutoBackupFiles.isTempName("yapt-backup-2026-01-05.yapt.partial"))
        assertFalse(AutoBackupFiles.isTempName("x$temp"))
        assertFalse(AutoBackupFiles.isTempName("$temp.bak"))
    }

    @Test
    fun `second consecutive failure notifies once`() {
        val kind = AutoBackupFailureKind.EXPORT
        assertFalse(AutoBackupPolicy.shouldNotify(kind, 1, alreadyNotified = false))
        assertTrue(AutoBackupPolicy.shouldNotify(kind, 2, alreadyNotified = false))
        assertFalse(AutoBackupPolicy.shouldNotify(kind, 3, alreadyNotified = true))
    }

    @Test
    fun `lost folder notifies immediately but only once per streak`() {
        val kind = AutoBackupFailureKind.FOLDER_UNAVAILABLE
        assertTrue(AutoBackupPolicy.shouldNotify(kind, 1, alreadyNotified = false))
        assertFalse(AutoBackupPolicy.shouldNotify(kind, 2, alreadyNotified = true))
    }

    @Test
    fun `status precedence`() {
        assertEquals(AutoBackupStatus.Off, AutoBackupPolicy.status(false, AutoBackupFailureKind.EXPORT, 5L, 0))
        assertEquals(
            AutoBackupStatus.FolderUnavailable,
            AutoBackupPolicy.status(true, AutoBackupFailureKind.FOLDER_UNAVAILABLE, 5L, 0)
        )
        assertEquals(
            AutoBackupStatus.Failed(AutoBackupFailureKind.STORAGE),
            AutoBackupPolicy.status(true, AutoBackupFailureKind.STORAGE, 5L, 0)
        )
        assertEquals(AutoBackupStatus.Success(5L, 2), AutoBackupPolicy.status(true, null, 5L, 2))
        assertEquals(AutoBackupStatus.AwaitingFirstRun, AutoBackupPolicy.status(true, null, null, 0))
    }

    @Test
    fun `stored values fall back to defaults`() {
        assertEquals(AutoBackupFrequency.WEEKLY, AutoBackupFrequency.fromStored("bogus"))
        assertEquals(7, AutoBackupRetention.fromStored(5))
        assertNull(AutoBackupFailureKind.fromStored(null))
    }
}
