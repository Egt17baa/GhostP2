package org.ghostp2.app.photo

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
class PhotoSaverTest {
    /** Proveedor de documentos falso: solo apunta los borrados de documentos. */
    class RecordingProvider : ContentProvider() {
        val deleted = mutableListOf<Uri>()

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
            if (method == "android:deleteDocument") {
                @Suppress("DEPRECATION")
                deleted += extras?.getParcelable<Uri>("uri")!! // DocumentsContract.EXTRA_URI (oculta)
            }
            return null
        }

        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private val resolver = RuntimeEnvironment.getApplication().contentResolver
    private val target = DocumentsContract.buildDocumentUri(AUTHORITY, "foto")
    private lateinit var provider: RecordingProvider

    @Before
    fun setUp() {
        provider = Robolectric.buildContentProvider(RecordingProvider::class.java).create(AUTHORITY).get()
    }

    /** JPEG mínimo bien formado: SOI, APP0 (JFIF), [extra] (segmentos antes del SOS), SOS con datos y EOI. */
    private fun jpeg(extra: ByteArray = ByteArray(0)): ByteArray =
        bytes(0xFF, 0xD8) +
            bytes(0xFF, 0xE0, 0x00, 0x06) + "JFIF".toByteArray() +
            extra +
            bytes(0xFF, 0xDA, 0x00, 0x02, 0x11, 0x22, 0x33) +
            bytes(0xFF, 0xD9)

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun savesExactBytes() = runBlocking {
        val out = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(target, out)
        val live = jpeg()
        assertTrue(PhotoSaver.save(resolver, target, live))
        assertArrayEquals(jpeg(), out.toByteArray())
        // El array vivo del motor queda intacto y el documento, sin borrar.
        assertArrayEquals(jpeg(), live)
        assertTrue(provider.deleted.isEmpty())
    }

    @Test
    fun metadataNeverReachesDisk() = runBlocking {
        // R27: una foto recibida con EXIF (APP1) y comentario (COM) se guarda sin ellos.
        val exif = bytes(0xFF, 0xE1, 0x00, 0x08) + "Exif".toByteArray() + bytes(0, 0)
        val comment = bytes(0xFF, 0xFE, 0x00, 0x04) + "hi".toByteArray()
        val out = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(target, out)
        val live = jpeg(exif + comment)
        assertTrue(PhotoSaver.save(resolver, target, live))
        assertArrayEquals(jpeg(), out.toByteArray())
        assertArrayEquals(jpeg(exif + comment), live)
    }

    @Test
    fun unexpectedStructureIsNotSavedAndDocumentIsDeleted() = runBlocking {
        var opened = 0
        shadowOf(resolver).registerOutputStreamSupplier(target) {
            opened++
            ByteArrayOutputStream()
        }
        // Sin segmentos tras el SOI (no se puede recorrer: falla cerrado).
        assertFalse(PhotoSaver.save(resolver, target, bytes(0xFF, 0xD8, 1, 2, 3, 0xFF, 0xD9)))
        // Sin EOI al final.
        assertFalse(PhotoSaver.save(resolver, target, jpeg().copyOf(jpeg().size - 2)))
        assertEquals(0, opened)
        assertEquals(listOf(target, target), provider.deleted)
    }

    @Test
    fun zeroedPhotoIsNotSaved() = runBlocking {
        var opened = 0
        shadowOf(resolver).registerOutputStreamSupplier(target) {
            opened++
            ByteArrayOutputStream()
        }
        assertFalse(PhotoSaver.save(resolver, target, ByteArray(20)))
        val headerZeroed = jpeg().also { it[0] = 0; it[1] = 0 }
        assertFalse(PhotoSaver.save(resolver, target, headerZeroed))
        assertEquals(0, opened)
        // El documento vacío que creó el selector se borra.
        assertEquals(listOf(target, target), provider.deleted)
    }

    @Test
    fun goneDocumentIsDeleted() = runBlocking {
        PhotoSaver.discard(resolver, target)
        assertEquals(listOf(target), provider.deleted)
    }

    @Test
    fun writeFailureDeletesDocument() = runBlocking {
        shadowOf(resolver).registerOutputStream(target, object : OutputStream() {
            override fun write(b: Int) = throw IOException("lleno")
            override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("lleno")
        })
        assertFalse(PhotoSaver.save(resolver, target, jpeg()))
        assertEquals(listOf(target), provider.deleted)
    }

    @Test
    fun unexpectedExceptionDeletesDocument() = runBlocking {
        shadowOf(resolver).registerOutputStreamSupplier(target) { throw IllegalStateException("proveedor roto") }
        assertFalse(PhotoSaver.save(resolver, target, jpeg()))
        assertEquals(listOf(target), provider.deleted)
    }

    private companion object {
        const val AUTHORITY = "org.ghostp2.pruebas.documentos"
    }
}
