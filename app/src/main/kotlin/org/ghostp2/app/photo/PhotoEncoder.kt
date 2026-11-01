package org.ghostp2.app.photo

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.ghostp2.core.protocol.PeerSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** Una foto que no se puede preparar para el envío. Sin mensaje: nada de la foto ni de su origen. */
class PhotoException(val reason: Reason) : Exception() {
    enum class Reason {
        /** No se puede leer o no es una imagen decodificable. */
        UNREADABLE,

        /** Demasiado grande: el original pasa de 64 MiB o el JPEG no cabe en [PeerSession.MAX_PHOTO_BYTES]. */
        TOO_LARGE,

        /** El motor no está `Online`: no se envía nada. */
        NOT_ACTIVE,
    }
}

/**
 * Recodifica una imagen a JPEG sin metadatos (el JPEG que genera `Bitmap.compress` no lleva EXIF), con
 * la orientación EXIF ya aplicada a los píxeles, el lado mayor como mucho `maxSidePx` (nunca se amplía)
 * y como mucho [PeerSession.MAX_PHOTO_BYTES] bytes. Todo en memoria: ni caché ni archivos temporales.
 */
object PhotoEncoder {
    /** Tamaño máximo del original que se lee en memoria. */
    const val MAX_INPUT_BYTES = 64 * 1024 * 1024

    private val QUALITIES = intArrayOf(85, 75, 65, 55, 45)
    private const val SHRINK_FACTOR = 0.75
    private const val MAX_SHRINKS = 3

    /**
     * Recodifica [input] (bloqueante, usa CPU: llamarla fuera del hilo principal). Rellena [input] con
     * ceros al terminar, salga bien o mal. Lanza [PhotoException] con `UNREADABLE` o `TOO_LARGE`.
     */
    fun encode(input: ByteArray, maxSidePx: Int): ByteArray {
        require(maxSidePx > 0) { "maxSidePx debe ser positivo" }
        var decoded: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PhotoException(PhotoException.Reason.UNREADABLE)

            // Contra bombas de descompresión: se decodifica submuestreado, con el lado mayor aún ≥ maxSidePx.
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSidePx) sample *= 2
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            decoded = BitmapFactory.decodeByteArray(input, 0, input.size, options)
                ?: throw PhotoException(PhotoException.Reason.UNREADABLE)
            val orientation = readOrientation(input)

            var side = maxSidePx
            repeat(MAX_SHRINKS + 1) {
                compressFitting(decoded, orientation, side)?.let { return it }
                side = (side * SHRINK_FACTOR).toInt().coerceAtLeast(1)
            }
            throw PhotoException(PhotoException.Reason.TOO_LARGE)
        } catch (_: OutOfMemoryError) {
            // El bitmap no cabe en memoria: se trata como foto demasiado grande, sin cerrar la app.
            throw PhotoException(PhotoException.Reason.TOO_LARGE)
        } finally {
            decoded?.recycle()
            input.fill(0)
        }
    }

    /**
     * Lee el `Uri` en memoria en `Dispatchers.IO` (más de [MAX_INPUT_BYTES] ⇒ `TOO_LARGE`) y lo recodifica
     * en `Dispatchers.Default`.
     */
    suspend fun encode(resolver: ContentResolver, uri: Uri, maxSidePx: Int): ByteArray {
        val input = withContext(Dispatchers.IO) { readAll(resolver, uri) }
        // encode(input, …) rellena `input` con ceros también si se cancela antes de empezar.
        try {
            return withContext(Dispatchers.Default) { encode(input, maxSidePx) }
        } finally {
            input.fill(0)
        }
    }

    private fun readAll(resolver: ContentResolver, uri: Uri): ByteArray {
        val buffer = WipeableBuffer()
        try {
            val stream = try {
                resolver.openInputStream(uri)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } ?: throw PhotoException(PhotoException.Reason.UNREADABLE)
            stream.use { copyLimited(it, buffer) }
            return buffer.toByteArray()
        } catch (_: IOException) {
            throw PhotoException(PhotoException.Reason.UNREADABLE)
        } catch (_: SecurityException) {
            throw PhotoException(PhotoException.Reason.UNREADABLE)
        } finally {
            buffer.wipe()
        }
    }

    private fun copyLimited(stream: InputStream, out: WipeableBuffer) {
        val chunk = ByteArray(64 * 1024)
        try {
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                if (out.size() + n > MAX_INPUT_BYTES) throw PhotoException(PhotoException.Reason.TOO_LARGE)
                out.write(chunk, 0, n)
            }
        } finally {
            chunk.fill(0)
        }
    }

    private fun readOrientation(input: ByteArray): Int = try {
        ExifInterface(ByteArrayInputStream(input)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // EXIF ilegible: la imagen se envía tal como se decodifica.
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Orienta y escala [source] a lado mayor ≤ [side] y lo comprime; `null` si ni a la calidad mínima cabe. */
    private fun compressFitting(source: Bitmap, orientation: Int, side: Int): ByteArray? {
        val transposed = orientation in TRANSPOSING
        // Tamaño final ya orientado; nunca se amplía.
        val orientedW = if (transposed) source.height else source.width
        val orientedH = if (transposed) source.width else source.height
        val scale = minOf(1.0, side.toDouble() / max(orientedW, orientedH))
        val targetW = (orientedW * scale).roundToInt().coerceAtLeast(1)
        val targetH = (orientedH * scale).roundToInt().coerceAtLeast(1)
        // Escala en las coordenadas de origen (ejes exactos para que el resultado mida targetW×targetH).
        val srcTargetW = if (transposed) targetH else targetW
        val srcTargetH = if (transposed) targetW else targetH
        val matrix = Matrix().apply {
            setScale(srcTargetW.toFloat() / source.width, srcTargetH.toFloat() / source.height)
            postConcat(orientationMatrix(orientation))
        }
        val scaled = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        val out = WipeableBuffer()
        try {
            for (quality in QUALITIES) {
                out.reset()
                if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw PhotoException(PhotoException.Reason.UNREADABLE)
                if (out.size() <= PeerSession.MAX_PHOTO_BYTES) return out.toByteArray()
            }
            return null
        } finally {
            out.wipe()
            // createBitmap puede devolver el mismo bitmap si no hay nada que transformar.
            if (scaled !== source) scaled.recycle()
        }
    }

    private val TRANSPOSING = setOf(
        ExifInterface.ORIENTATION_TRANSPOSE,
        ExifInterface.ORIENTATION_ROTATE_90,
        ExifInterface.ORIENTATION_TRANSVERSE,
        ExifInterface.ORIENTATION_ROTATE_270,
    )

    private fun orientationMatrix(orientation: Int): Matrix = Matrix().apply {
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            else -> Unit
        }
    }

    /** `ByteArrayOutputStream` cuyo búfer interno se puede rellenar con ceros. */
    private class WipeableBuffer : ByteArrayOutputStream(64 * 1024) {
        fun wipe() = buf.fill(0)

        // Al crecer, el búfer viejo se copia y se abandona: se rellena con ceros antes.
        override fun write(b: ByteArray, off: Int, len: Int) {
            if (count + len > buf.size) {
                val old = buf
                super.write(b, off, len)
                if (old !== buf) old.fill(0)
            } else {
                super.write(b, off, len)
            }
        }

        override fun write(b: Int) {
            val old = buf
            super.write(b)
            if (old !== buf) old.fill(0)
        }
    }
}
