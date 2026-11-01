package org.ghostp2.app.photo

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoBitmapsTest {
    private fun jpeg(width: Int = 64, height: Int = 48): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(30, 160, 90))
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        bitmap.recycle()
        return out.toByteArray()
    }

    /** Decodificador que cuenta las llamadas y devuelve un bitmap de [width]×[height]. */
    private class Counting(val width: Int = 10, val height: Int = 10) : (ByteArray, Int) -> Bitmap? {
        var calls = 0
        override fun invoke(bytes: ByteArray, sidePx: Int): Bitmap? {
            calls++
            return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }

    @Test
    fun decodesOncePerItem() {
        val decoder = Counting()
        val bitmaps = PhotoBitmaps(decode = decoder)
        val bytes = jpeg()
        val first = bitmaps.thumbnail("a", bytes)
        assertNotNull(first)
        assertSame(first, bitmaps.thumbnail("a", bytes))
        assertSame(first, bitmaps.thumbnail("a", bytes))
        assertEquals(1, decoder.calls)
        bitmaps.thumbnail("b", bytes)
        assertEquals(2, decoder.calls)
    }

    @Test
    fun realDecodeWorksAndSubsamples() {
        val bitmaps = PhotoBitmaps()
        val image = bitmaps.thumbnail("a", jpeg(400, 200), sidePx = 100)
        assertNotNull(image)
        // inSampleSize 4 (el lado mayor queda justo en 100, aún ≥ 100): 100×50.
        assertEquals(100, image!!.width)
        assertEquals(50, image.height)
        // Con 101 ya no cabe dividir entre 4: inSampleSize 2, 200×100.
        assertEquals(200, PhotoBitmaps().thumbnail("b", jpeg(400, 200), sidePx = 101)!!.width)
        assertNotNull(bitmaps.full(jpeg(400, 200), 1024))
    }

    @Test
    fun zeroedBytesGiveNull() {
        val decoder = Counting()
        val bitmaps = PhotoBitmaps(decode = decoder)
        val bytes = jpeg().also { it.fill(0) }
        assertNull(bitmaps.thumbnail("a", bytes))
        assertNull(bitmaps.full(bytes, 1024))
        assertEquals(0, decoder.calls)
        // Con el decodificador real, unos bytes a cero tampoco dan imagen (ni un cierre).
        assertNull(decodeSampled(ByteArray(1000), 100))
        assertNull(PhotoBitmaps().thumbnail("b", ByteArray(1000)))
        // Un array diminuto o vacío tampoco.
        assertNull(bitmaps.thumbnail("c", ByteArray(0)))
        assertNull(bitmaps.thumbnail("d", byteArrayOf(0xFF.toByte())))
    }

    @Test
    fun zeroingBeforeHeaderCheckIsDetected() {
        // El motor pone a cero desde el índice 0: cabecera ya a cero y el resto aún intacto.
        val live = jpeg()
        live[0] = 0
        live[1] = 0
        val decoder = Counting()
        val bitmaps = PhotoBitmaps(decode = decoder)
        assertNull(bitmaps.thumbnail("a", live))
        assertNull(bitmaps.full(live, 1024))
        assertEquals(0, decoder.calls)
    }

    @Test
    fun zeroingDuringDecodeIsDetected() {
        // El motor pone a cero el array vivo mientras se decodifica la copia: no se muestra ni se guarda en caché.
        val live = jpeg()
        val bitmaps = PhotoBitmaps(decode = { copy, side ->
            live.fill(0)
            decodeSampled(copy, side)
        })
        assertNull(bitmaps.thumbnail("a", live))
        assertNull(bitmaps.cachedIds().singleOrNull())
    }

    @Test
    fun decoderCopyIsWipedAndLiveArrayUntouched() {
        val live = jpeg()
        val original = live.copyOf()
        var seen: ByteArray? = null
        val bitmaps = PhotoBitmaps(decode = { copy, _ ->
            assertTrue(copy !== live)
            seen = copy
            Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        })
        assertNotNull(bitmaps.thumbnail("a", live))
        assertTrue(live.contentEquals(original))
        assertTrue(seen!!.all { it == 0.toByte() })
    }

    @Test
    fun failedDecodeGivesNullAndIsNotRetried() {
        var calls = 0
        val bitmaps = PhotoBitmaps(decode = { _, _ -> calls++; null })
        val bytes = jpeg()
        assertNull(bitmaps.thumbnail("a", bytes))
        assertNull(bitmaps.thumbnail("a", bytes))
        assertEquals(1, calls)
        // Un decodificador que devuelve un bitmap vacío tampoco vale.
        val empty = PhotoBitmaps(decode = { _, _ -> throw IllegalStateException() })
        assertNull(empty.thumbnail("a", bytes))
    }

    @Test
    fun cachedPhotoZeroedLaterGivesNull() {
        val bitmaps = PhotoBitmaps(decode = Counting())
        val live = jpeg()
        assertNotNull(bitmaps.thumbnail("a", live))
        live.fill(0)
        assertNull(bitmaps.thumbnail("a", live))
        assertFalse("a" in bitmaps.cachedIds())
    }

    @Test
    fun retainEvictsOthers() {
        val decoder = Counting()
        val bitmaps = PhotoBitmaps(decode = decoder)
        val bytes = jpeg()
        bitmaps.thumbnail("a", bytes)
        bitmaps.thumbnail("b", bytes)
        bitmaps.thumbnail("c", bytes)
        bitmaps.retain(setOf("b"))
        assertEquals(setOf("b"), bitmaps.cachedIds())
        bitmaps.thumbnail("b", bytes)
        assertEquals(3, decoder.calls)
        bitmaps.thumbnail("a", bytes)
        assertEquals(4, decoder.calls)
        bitmaps.clear()
        assertTrue(bitmaps.cachedIds().isEmpty())
    }

    @Test
    fun cacheRespectsByteBudget() {
        // Cada bitmap de 10×10 ARGB ocupa 400 bytes: caben 2 en 1000.
        val bitmaps = PhotoBitmaps(maxBytes = 1000, decode = Counting())
        val bytes = jpeg()
        bitmaps.thumbnail("a", bytes)
        bitmaps.thumbnail("b", bytes)
        bitmaps.thumbnail("c", bytes)
        assertTrue(bitmaps.cachedBytes() <= 1000)
        assertEquals(setOf("b", "c"), bitmaps.cachedIds())
    }
}
