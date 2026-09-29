package com.yapt.planttracker.util

import androidx.room.withTransaction
import com.yapt.planttracker.data.db.PlantDatabase
import java.io.File

/**
 * Reclaims photo files nothing in the database references anymore (#736/#559).
 *
 * The referenced set is `plants.coverPhotoUri` (including archived plants) ∪ `care_logs.photoUri` ∪
 * `plant_photos.uri`. One URI is frequently referenced from more than one of those tables (a photo log
 * writes both `care_logs.photoUri` and a `plant_photos` row, and either may also be a plant's
 * `coverPhotoUri`), so this sweep — not an eager delete at each row's own deletion site — is the only
 * safe way to know a file is truly unreferenced. See technical ADR for the full rationale.
 *
 * Only `filesDir/images` (in-app camera captures) and `filesDir/restored_photos` (`.yapt` restore
 * output) are walked, non-recursively — a gallery-picked `content://` reference is never one of ours to
 * delete, and nothing else on disk is touched. [MIN_AGE_MILLIS] protects an in-flight capture
 * (`CameraPhotoState.pendingCameraFile`, not yet written to any row) and a stale `*.processing` temp
 * file left behind by [ImageUtils.compressCameraImage] from a crash mid-compression — the latter is
 * genuinely garbage and is reclaimed once it clears the same age floor.
 *
 * The referenced-set read and the delete both happen inside one [PlantDatabase.withTransaction] so no
 * concurrent Room write can land in between and make a file look orphaned when it briefly isn't.
 */
object OrphanPhotoSweeper {

    const val MIN_AGE_MILLIS: Long = 24L * 60 * 60 * 1000

    private val SWEPT_DIR_NAMES = listOf("images", "restored_photos")

    data class SweepResult(val filesDeleted: Int, val bytesDeleted: Long)

    suspend fun sweep(
        database: PlantDatabase,
        packageName: String,
        filesDir: File,
        now: Long = System.currentTimeMillis(),
        minAgeMillis: Long = MIN_AGE_MILLIS
    ): SweepResult = database.withTransaction {
        val referenced = collectReferencedCanonicalPaths(database, packageName, filesDir)
        var filesDeleted = 0
        var bytesDeleted = 0L
        for (dirName in SWEPT_DIR_NAMES) {
            val files = File(filesDir, dirName).listFiles()?.filter { it.isFile }.orEmpty()
            for (file in files) {
                if (!isEligibleForDeletion(file, referenced, now, minAgeMillis)) continue
                val length = file.length()
                if (file.delete()) {
                    filesDeleted++
                    bytesDeleted += length
                }
            }
        }
        SweepResult(filesDeleted, bytesDeleted)
    }

    private fun isEligibleForDeletion(file: File, referenced: Set<String>, now: Long, minAgeMillis: Long): Boolean {
        val isOldEnough = now - file.lastModified() >= minAgeMillis
        val canonicalPath = runCatching { file.canonicalPath }.getOrNull()
        return isOldEnough && canonicalPath != null && canonicalPath !in referenced
    }

    private suspend fun collectReferencedCanonicalPaths(
        database: PlantDatabase,
        packageName: String,
        filesDir: File
    ): Set<String> {
        val rawRefs = database.plantDao().getAllCoverPhotoUris() +
            database.careLogDao().getAllPhotoUris() +
            database.plantPhotoDao().getAllPhotoUriStrings()
        return rawRefs.mapNotNull { ref ->
            normalizeToFile(ref, packageName, filesDir)?.let { file ->
                runCatching { file.canonicalPath }.getOrNull()
            }
        }.toSet()
    }

    /**
     * Normalizes one stored photo reference to the on-disk [File] it names, or `null` when the
     * reference is not a file this app owns (in particular, any gallery `content://` URI). Pure and
     * plain-JVM testable — [packageName]/[filesDir] are injected rather than read from a `Context`.
     *
     * - no scheme (a bare absolute path — what `.yapt` restore writes) → used as-is.
     * - `file://` → its path.
     * - `content://<packageName>.fileprovider/plant_images/…` (our own `FileProvider` authority, see
     *   `res/xml/file_paths.xml`) → `filesDir/images/…`.
     * - any other `content://` (gallery-picker, or an unrecognised authority) → `null`.
     */
    internal fun normalizeToFile(ref: String, packageName: String, filesDir: File): File? = when {
        ref.startsWith("content://") -> normalizeContentUri(ref, packageName, filesDir)
        ref.startsWith("file://") -> ref.removePrefix("file://").takeIf { it.isNotEmpty() }?.let { File(it) }
        ref.contains("://") -> null // any other scheme (e.g. http/https) is never ours.
        else -> File(ref)
    }

    private fun normalizeContentUri(ref: String, packageName: String, filesDir: File): File? {
        val withoutScheme = ref.removePrefix("content://")
        val slashIndex = withoutScheme.indexOf('/')
        val authority = if (slashIndex == -1) withoutScheme else withoutScheme.substring(0, slashIndex)
        if (authority != "$packageName.fileprovider") return null

        val path = if (slashIndex == -1) "" else withoutScheme.substring(slashIndex + 1)
        val segments = path.split('/').filter { it.isNotEmpty() }
        return if (segments.size < 2 || segments[0] != "plant_images") {
            null
        } else {
            File(filesDir, "images/" + segments.drop(1).joinToString("/"))
        }
    }
}
