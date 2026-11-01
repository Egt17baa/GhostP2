package org.ghostp2.app.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.runBlocking
import org.ghostp2.core.protocol.PeerSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoEncoderTest {
    // Solo la prueba escribe un archivo (ExifInterface solo guarda etiquetas en archivos); el código no.
    @get:Rule val temp = TemporaryFolder()

    private fun jpeg(width: Int, height: Int, quality: Int = 90, fill: (Bitmap) -> Unit = { it.eraseColor(Color.rgb(200, 120, 40)) }): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        fill(bitmap)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** Escribe etiquetas EXIF en un JPEG pasando por un archivo temporal de la prueba. */
    private fun withExif(input: ByteArray, edit: (ExifInterface) -> Unit): ByteArray {
        val file = temp.newFile("entrada.jpg")
        file.writeBytes(input)
        ExifInterface(file).apply(edit).saveAttributes()
        return file.readBytes().also { file.delete() }
    }

    private fun size(jpeg: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        return o.outWidth to o.outHeight
    }

    @Test
    fun outputIsJpegWithoutExif() {
        val input = withExif(jpeg(64, 48)) {
            it.setAttribute(ExifInterface.TAG_MAKE, "Acme")
            it.setLatLong(40.4168, -3.7038)
        }
        // La entrada sí tiene los metadatos.
        ExifInterface(ByteArrayInputStream(input)).let {
            assertEquals("Acme", it.getAttribute(ExifInterface.TAG_MAKE))
            assertTrue(it.latLong != null)
        }
        val out = PhotoEncoder.encode(input, 2048)
        assertEquals(0xFF.toByte(), out[0])
        assertEquals(0xD8.toByte(), out[1])
        val exif = ExifInterface(ByteArrayInputStream(out))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.latLong)
    }

    @Test
    fun inputBufferIsWiped() {
        val input = jpeg(32, 32)
        PhotoEncoder.encode(input, 2048)
        assertArrayEquals(ByteArray(input.size), input)
    }

    @Test
    fun longSideIsCapped() {
        assertEquals(2048 to 512, size(PhotoEncoder.encode(jpeg(4000, 1000), 2048)))
    }

    @Test
    fun subsampledImageIsCappedExactly() {
        // 8000×2000 se decodifica con inSampleSize 2 (4000×1000) y luego se escala a 2048×512.
        assertEquals(2048 to 512, size(PhotoEncoder.encode(jpeg(8000, 2000), 2048)))
    }

    @Test
    fun smallImageIsNotUpscaled() {
        assertEquals(300 to 200, size(PhotoEncoder.encode(jpeg(300, 200), 2048)))
    }

    @Test
    fun orientationIsApplied() {
        val input = withExif(jpeg(100, 50)) {
            it.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        }
        val out = PhotoEncoder.encode(input, 2048)
        assertEquals(50 to 100, size(out))
        assertEquals(
            ExifInterface.ORIENTATION_UNDEFINED,
            ExifInterface(ByteArrayInputStream(out)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED),
        )
    }

    @Test
    fun rotationGoesClockwise() {
        // Mitad izquierda roja y derecha azul; con orientación 6 (girar 90° a la derecha) la roja queda arriba.
        val source = jpeg(100, 50) { bitmap ->
            for (x in 0 until 100) for (y in 0 until 50) bitmap.setPixel(x, y, if (x < 50) Color.RED else Color.BLUE)
        }
        val input = withExif(source) {
            it.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
        }
        val out = PhotoEncoder.encode(input, 2048)
        val bitmap = BitmapFactory.decodeByteArray(out, 0, out.size)
        assertTrue(Color.red(bitmap.getPixel(25, 20)) > 200 && Color.blue(bitmap.getPixel(25, 20)) < 60)
        assertTrue(Color.blue(bitmap.getPixel(25, 80)) > 200 && Color.red(bitmap.getPixel(25, 80)) < 60)
    }

    @Test
    fun uriIsReadInMemory() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://fotos/1")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(jpeg(300, 200)))
        assertEquals(300 to 200, size(PhotoEncoder.encode(context.contentResolver, uri, 2048)))
    }

    @Test
    fun oversizedUriIsTooLarge() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val uri = Uri.parse("content://fotos/2")
        // Flujo de 64 MiB + 1 bytes generado al vuelo.
        val huge = object : InputStream() {
            var left = PhotoEncoder.MAX_INPUT_BYTES + 1L
            override fun read(): Int = if (left-- > 0) 0 else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val n = minOf(len.toLong(), left).toInt()
                left -= n
                return n
            }
        }
        shadowOf(context.contentResolver).registerInputStream(uri, huge)
        try {
            PhotoEncoder.encode(context.contentResolver, uri, 2048)
            fail("Debería fallar")
        } catch (e: PhotoException) {
            assertEquals(PhotoException.Reason.TOO_LARGE, e.reason)
        }
    }

    @Test
    fun noisyImageFitsTheLimit() {
        val random = Random(7)
        val input = jpeg(3000, 3000, quality = 100) { bitmap ->
            val pixels = IntArray(bitmap.width * bitmap.height) { random.nextInt() or (0xFF shl 24) }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
        val out = PhotoEncoder.encode(input, 3072)
        assertTrue("${out.size} bytes", out.size <= PeerSession.MAX_PHOTO_BYTES)
        assertEquals(0xD8.toByte(), out[1])
    }

    @Test
    fun garbageIsUnreadable() {
        for (input in listOf(ByteArray(0), Random(1).nextBytes(4096), byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3))) {
            try {
                PhotoEncoder.encode(input, 2048)
                fail("Debería fallar")
            } catch (e: PhotoException) {
                assertEquals(PhotoException.Reason.UNREADABLE, e.reason)
            }
        }
    }
}
