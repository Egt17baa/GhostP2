package org.ghostp2.app.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.ghostp2.core.protocol.PeerSession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun outputHasOnlyJfifHeader() {
        // Ni siquiera el perfil ICC sRGB que añade Skia en APP2.
        val found = markers(PhotoEncoder.encode(jpeg(64, 48), 2048))
        assertEquals(0xE0, found.first())
        assertTrue(found.none { it in 0xE1..0xEF || it == 0xFE })
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

    private fun noise(width: Int, height: Int, seed: Int = 3): ByteArray {
        val random = Random(seed)
        return jpeg(width, height, quality = 100) { bitmap ->
            val pixels = IntArray(bitmap.width * bitmap.height) { random.nextInt() or (0xFF shl 24) }
            bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        }
    }

    @Test
    fun compressLoopEndsInTooLarge() {
        val input = noise(800, 800)
        try {
            PhotoEncoder.encode(input, 2048, maxBytes = 500) {}
            fail("Debería fallar")
        } catch (e: PhotoException) {
            assertEquals(PhotoException.Reason.TOO_LARGE, e.reason)
        }
        assertArrayEquals(ByteArray(input.size), input)
    }

    @Test
    fun shrinkStartsFromTheCurrentSize() {
        // 600×600 (menor que maxSidePx): si no cabe a calidad 45, se reduce desde 600 (a 450), no desde 2048.
        val input = noise(600, 600)
        val decoded = BitmapFactory.decodeByteArray(input, 0, input.size)
        val atMinimum = ByteArrayOutputStream().also { decoded.compress(Bitmap.CompressFormat.JPEG, 45, it) }.size()
        val out = PhotoEncoder.encode(input, 2048, maxBytes = atMinimum - 1) {}
        assertEquals(450 to 450, size(out))
        assertTrue(out.size < atMinimum)
    }

    @Test
    fun cancellationStopsAndWipes() {
        val input = jpeg(64, 64)
        try {
            PhotoEncoder.encode(input, 2048, PeerSession.MAX_PHOTO_BYTES) { throw CancellationException("cancelada") }
            fail("Debería cancelarse")
        } catch (_: CancellationException) {
        }
        assertArrayEquals(ByteArray(input.size), input)
    }

    @Test
    fun decodedPixelsAreCapped() {
        // R26: el submuestreo normal deja ≥ maxSidePx; si eso pasa de ~24 Mpx se dobla otra vez.
        assertEquals(1, PhotoEncoder.sampleSize(4000, 3000, 2048))
        assertEquals(2, PhotoEncoder.sampleSize(8000, 2000, 2048))
        assertEquals(2, PhotoEncoder.sampleSize(6000, 6000, 3072))
        assertEquals(4, PhotoEncoder.sampleSize(16000, 12000, 2048))
        for ((w, h, side) in listOf(Triple(6143, 6143, 3072), Triple(20000, 20000, 1280), Triple(50000, 1000, 3072))) {
            val sample = PhotoEncoder.sampleSize(w, h, side)
            assertTrue((w / sample).toLong() * (h / sample) <= PhotoEncoder.MAX_DECODED_PIXELS)
        }
    }

    @Test
    fun transparencyBecomesWhite() {
        val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.TRANSPARENT)
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val out = PhotoEncoder.encode(png, 2048)
        val pixel = BitmapFactory.decodeByteArray(out, 0, out.size).getPixel(20, 20)
        assertTrue(Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240)
    }

    /** Marcadores de los segmentos de cabecera de un JPEG, hasta el inicio de los datos (SOS). */
    private fun markers(jpeg: ByteArray): List<Int> {
        assertEquals(0xFF, jpeg[0].toInt() and 0xFF)
        assertEquals(0xD8, jpeg[1].toInt() and 0xFF)
        val found = mutableListOf<Int>()
        var i = 2
        while (i + 4 <= jpeg.size) {
            assertEquals(0xFF, jpeg[i].toInt() and 0xFF)
            val marker = jpeg[i + 1].toInt() and 0xFF
            found += marker
            if (marker == 0xDA) break
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            i += 2 + length
        }
        return found
    }

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    /** APP1 Exif (TIFF little-endian) con la marca y una miniatura JPEG en IFD1. */
    private fun exifWithThumbnail(thumbnail: ByteArray): ByteArray {
        val make = "Acme\u0000".toByteArray(Charsets.US_ASCII)
        val buffer = java.nio.ByteBuffer.allocate(8 + 2 + 12 + 4 + 2 + 24 + 4 + make.size + thumbnail.size)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val ifd0 = 8
        val ifd1 = ifd0 + 2 + 12 + 4
        val makeAt = ifd1 + 2 + 24 + 4
        val thumbAt = makeAt + make.size
        buffer.put("II".toByteArray()).putShort(42).putInt(ifd0)
        buffer.putShort(1).putShort(0x010F).putShort(2).putInt(make.size).putInt(makeAt).putInt(ifd1)
        buffer.putShort(2)
        buffer.putShort(0x0201).putShort(4).putInt(1).putInt(thumbAt)
        buffer.putShort(0x0202).putShort(4).putInt(1).putInt(thumbnail.size)
        buffer.putInt(0)
        buffer.put(make).put(thumbnail)
        return "Exif\u0000\u0000".toByteArray(Charsets.US_ASCII) + buffer.array()
    }

    @Test
    fun metadataSegmentsAreStripped() {
        val base = jpeg(64, 48)
        val xmp = "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta xmlns:x='adobe:ns:meta/'><gps>40.4,-3.7</gps></x:xmpmeta>"
        val input = byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            segment(0xE1, exifWithThumbnail(jpeg(16, 16))) +
            segment(0xE1, xmp.toByteArray(Charsets.UTF_8)) +
            segment(0xE2, "MPF\u0000datos".toByteArray(Charsets.US_ASCII)) +
            segment(0xED, "Photoshop 3.0\u00008BIM".toByteArray(Charsets.US_ASCII)) +
            segment(0xFE, "comentario".toByteArray(Charsets.US_ASCII)) +
            base.copyOfRange(2, base.size)
        // La entrada sí los tiene (y ExifInterface ve la marca y la miniatura).
        assertTrue(markers(input).containsAll(listOf(0xE1, 0xE2, 0xED, 0xFE)))
        ExifInterface(ByteArrayInputStream(input)).let {
            assertEquals("Acme", it.getAttribute(ExifInterface.TAG_MAKE))
            assertTrue(it.hasThumbnail())
        }
        val out = PhotoEncoder.encode(input, 2048)
        val found = markers(out)
        assertTrue("Segmentos: ${found.map { Integer.toHexString(it) }}", found.none { it in 0xE1..0xEF || it == 0xFE })
        assertFalse(ExifInterface(ByteArrayInputStream(out)).hasThumbnail())
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
