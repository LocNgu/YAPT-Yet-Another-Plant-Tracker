package com.yapt.planttracker.domain.autobackup

import java.time.LocalDate

@Suppress("MagicNumber")
enum class AutoBackupFrequency(val days: Long) {
    DAILY(1),
    WEEKLY(7);

    companion object {
        val DEFAULT = WEEKLY

        fun fromStored(value: String?): AutoBackupFrequency =
            runCatching { valueOf(value ?: "") }.getOrDefault(DEFAULT)
    }
}

object AutoBackupRetention {
    @Suppress("MagicNumber")
    val OPTIONS = listOf(3, 7, 14)
    const val DEFAULT = 7

    fun fromStored(value: Int?): Int = if (value in OPTIONS) value!! else DEFAULT
}

enum class AutoBackupFailureKind {
    FOLDER_UNAVAILABLE,
    STORAGE,
    EXPORT;

    companion object {
        fun fromStored(value: String?): AutoBackupFailureKind? =
            runCatching { valueOf(value ?: "") }.getOrNull()
    }
}

/** What Settings shows on the auto-backup status line. */
sealed interface AutoBackupStatus {
    data object Off : AutoBackupStatus
    data object AwaitingFirstRun : AutoBackupStatus
    data object FolderUnavailable : AutoBackupStatus
    data class Failed(val kind: AutoBackupFailureKind) : AutoBackupStatus
    data class Success(val at: Long, val skippedPhotos: Int) : AutoBackupStatus
}

object AutoBackupPolicy {

    fun status(
        enabled: Boolean,
        lastFailure: AutoBackupFailureKind?,
        lastSuccessAt: Long?,
        skippedPhotos: Int
    ): AutoBackupStatus = when {
        !enabled -> AutoBackupStatus.Off
        lastFailure == AutoBackupFailureKind.FOLDER_UNAVAILABLE -> AutoBackupStatus.FolderUnavailable
        lastFailure != null -> AutoBackupStatus.Failed(lastFailure)
        lastSuccessAt != null -> AutoBackupStatus.Success(lastSuccessAt, skippedPhotos)
        else -> AutoBackupStatus.AwaitingFirstRun
    }

    /**
     * One notification per failure streak: immediately for a lost folder, otherwise from the second
     * consecutive failure. [alreadyNotified] is cleared by a success or by choosing a folder again.
     */
    fun shouldNotify(
        kind: AutoBackupFailureKind,
        consecutiveFailures: Int,
        alreadyNotified: Boolean
    ): Boolean = !alreadyNotified &&
        (kind == AutoBackupFailureKind.FOLDER_UNAVAILABLE || consecutiveFailures >= 2)
}

/** The app's own backup file naming; pruning touches only names that parse here. */
object AutoBackupFiles {
    private val PATTERN = Regex("""^yapt-backup-(\d{4})-(\d{2})-(\d{2})\.yapt$""")

    private val TEMP_PATTERN = Regex("""^yapt-backup-\d{4}-\d{2}-\d{2}\.yapt\.\d+\.partial$""")

    fun fileName(date: LocalDate): String = "yapt-backup-$date.yapt"

    /** Unique per run so a stale one can never make the provider turn the name into "(1)"; never parses as a backup. */
    fun tempFileName(date: LocalDate, nowMillis: Long): String = "${fileName(date)}.$nowMillis.partial"

    fun isTempName(name: String): Boolean = TEMP_PATTERN.matches(name)

    fun parseDate(name: String): LocalDate? {
        val match = PATTERN.matchEntire(name) ?: return null
        val (y, m, d) = match.destructured
        return runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
    }

    /** Names to delete so only the newest [keep] pattern-matching files remain. */
    fun selectPrunable(names: List<String>, keep: Int): List<String> =
        names.mapNotNull { name -> parseDate(name)?.let { name to it } }
            .distinctBy { it.first }
            .sortedByDescending { it.second }
            .drop(keep)
            .map { it.first }
}
