package com.yapt.planttracker.data.backup.autobackup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

class BackupFolderUnavailableException(cause: Throwable? = null) : Exception("Backup folder unavailable", cause)

data class BackupFolderEntry(val name: String, val uri: Uri)

interface BackupFolder {
    fun isAccessible(): Boolean

    /** @throws BackupFolderUnavailableException when the folder or its grant is gone. */
    fun list(): List<BackupFolderEntry>

    /** @return null when the provider refused to create the file (typically no space). */
    fun create(name: String): Uri?

    fun delete(entry: BackupFolderEntry): Boolean
}

class SafBackupFolder(private val context: Context, private val treeUri: Uri) : BackupFolder {

    private val resolver get() = context.contentResolver
    private val parentDocUri: Uri
        get() = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))

    override fun isAccessible(): Boolean {
        val granted = resolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission && it.isWritePermission
        }
        return granted && runCatching { list() }.isSuccess
    }

    override fun list(): List<BackupFolderEntry> = try {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )
        val entries = mutableListOf<BackupFolderEntry>()
        val cursor = resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null
        ) ?: throw BackupFolderUnavailableException()
        cursor.use {
            while (it.moveToNext()) {
                val name = it.getString(1) ?: continue
                entries += BackupFolderEntry(
                    name,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, it.getString(0))
                )
            }
        }
        entries
    } catch (e: BackupFolderUnavailableException) {
        throw e
    } catch (e: SecurityException) {
        throw BackupFolderUnavailableException(e)
    } catch (e: IllegalArgumentException) {
        throw BackupFolderUnavailableException(e)
    } catch (e: UnsupportedOperationException) {
        throw BackupFolderUnavailableException(e)
    }

    override fun create(name: String): Uri? = try {
        DocumentsContract.createDocument(resolver, parentDocUri, "application/octet-stream", name)
    } catch (e: SecurityException) {
        throw BackupFolderUnavailableException(e)
    } catch (e: java.io.FileNotFoundException) {
        throw BackupFolderUnavailableException(e)
    }

    override fun delete(entry: BackupFolderEntry): Boolean = runCatching {
        DocumentsContract.deleteDocument(resolver, entry.uri)
    }.getOrDefault(false)
}
