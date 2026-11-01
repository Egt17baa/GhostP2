package org.ghostp2.app.photo

import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream
import java.io.OutputStream

@RunWith(RobolectricTestRunner::class)
class PhotoSaverTest {
    private val resolver = RuntimeEnvironment.getApplication().contentResolver
    private val target = Uri.parse("content://documentos/foto.jpg")

    private fun jpegLike(): ByteArray = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4, 5, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun savesExactBytes() = runBlocking {
        val out = ByteArrayOutputStream()
        shadowOf(resolver).registerOutputStream(target, out)
        val jpeg = jpegLike()
        assertTrue(PhotoSaver.save(resolver, target, jpeg))
        assertArrayEquals(jpegLike(), out.toByteArray())
        // El array vivo del motor queda intacto.
        assertArrayEquals(jpegLike(), jpeg)
    }

    @Test
    fun zeroedPhotoIsNotSaved() = runBlocking {
        var opened = 0
        shadowOf(resolver).registerOutputStreamSupplier(target) {
            opened++
            ByteArrayOutputStream()
        }
        assertFalse(PhotoSaver.save(resolver, target, ByteArray(9)))
        val headerZeroed = jpegLike().also { it[0] = 0; it[1] = 0 }
        assertFalse(PhotoSaver.save(resolver, target, headerZeroed))
        assertEquals(0, opened)
    }

    @Test
    fun writeFailureGivesFalse() = runBlocking {
        shadowOf(resolver).registerOutputStream(target, object : OutputStream() {
            override fun write(b: Int) = throw java.io.IOException("lleno")
            override fun write(b: ByteArray, off: Int, len: Int) = throw java.io.IOException("lleno")
        })
        assertFalse(PhotoSaver.save(resolver, target, jpegLike()))
    }
}
