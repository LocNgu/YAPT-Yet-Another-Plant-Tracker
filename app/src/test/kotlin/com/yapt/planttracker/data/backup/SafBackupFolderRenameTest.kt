package com.yapt.planttracker.data.backup

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.DocumentsContract
import com.yapt.planttracker.data.backup.autobackup.SafBackupFolder
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SafBackupFolderRenameTest {

    private val resolver = mockk<ContentResolver>(relaxed = true)
    private val context = mockk<Context> { every { contentResolver } returns resolver }
    private val tree = Uri.parse("content://fake/tree/root")
    private val temp = Uri.parse("content://fake/tree/root/document/temp")
    private val final = Uri.parse("content://fake/tree/root/document/final")
    private val folder by lazy { SafBackupFolder(context, tree) }

    @Before
    fun setUp() {
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.getTreeDocumentId(any()) } returns "root"
        every { DocumentsContract.buildDocumentUriUsingTree(any(), any()) } returns Uri.parse("content://fake/parent")
    }

    @After
    fun tearDown() = unmockkStatic(DocumentsContract::class)

    private fun nameCursor(name: String?) = MatrixCursor(arrayOf("_display_name")).apply { addRow(arrayOf(name)) }

    @Test
    fun `rename returns the display name the provider gave`() {
        every { DocumentsContract.renameDocument(resolver, temp, "a.yapt") } returns final
        every { resolver.query(final, any(), null, null, null) } returns nameCursor("a (1).yapt")
        assertEquals("a (1).yapt", folder.rename(temp, "a.yapt"))
    }

    @Test
    fun `rename with an unreadable display name still counts as the requested name`() {
        every { DocumentsContract.renameDocument(resolver, temp, "a.yapt") } returns final
        every { resolver.query(final, any(), null, null, null) } returns null
        assertEquals("a.yapt", folder.rename(temp, "a.yapt"))
    }

    @Test
    fun `unsupported rename copies into the final file and deletes the temp`() {
        every { DocumentsContract.renameDocument(resolver, temp, "a.yapt") } throws UnsupportedOperationException()
        every { DocumentsContract.createDocument(resolver, any(), any(), "a.yapt") } returns final
        val sink = ByteArrayOutputStream()
        every { resolver.openInputStream(temp) } returns ByteArrayInputStream(byteArrayOf(1, 2, 3))
        every { resolver.openOutputStream(final, "w") } returns sink
        every { resolver.query(final, any(), null, null, null) } returns nameCursor("a.yapt")
        every { DocumentsContract.deleteDocument(resolver, any()) } returns true

        assertEquals("a.yapt", folder.rename(temp, "a.yapt"))
        assertEquals(listOf<Byte>(1, 2, 3), sink.toByteArray().toList())
        verify { DocumentsContract.deleteDocument(resolver, temp) }
    }

    @Test
    fun `failed copy deletes the partial final and keeps the temp`() {
        every { DocumentsContract.renameDocument(resolver, temp, "a.yapt") } throws UnsupportedOperationException()
        every { DocumentsContract.createDocument(resolver, any(), any(), "a.yapt") } returns final
        every { resolver.openInputStream(temp) } returns object : java.io.InputStream() {
            override fun read(): Int = throw java.io.IOException("disk")
        }
        every { resolver.openOutputStream(final, "w") } returns mockk<OutputStream>(relaxed = true)
        every { DocumentsContract.deleteDocument(resolver, any()) } returns true

        assertNull(folder.rename(temp, "a.yapt"))
        verify { DocumentsContract.deleteDocument(resolver, final) }
        verify(exactly = 0) { DocumentsContract.deleteDocument(resolver, temp) }
    }
}
