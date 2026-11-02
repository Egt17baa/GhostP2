package org.ghostp2.app.photo

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
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
 * Recodifica una imagen a JPEG sin metadatos (el JPEG que genera `Bitmap.compress` no lleva EXIF, XMP,
 * ICC ni IPTC), con la orientación EXIF ya aplicada a los píxeles, en sRGB, sin transparencia (se pinta
 * sobre blanco), con el lado mayor como mucho `maxSidePx` (nunca se amplía) y como mucho
 * [PeerSession.MAX_PHOTO_BYTES] bytes. Todo en memoria: ni caché ni archivos temporales. Los píxeles de
 * los bitmaps intermedios se borran antes de reciclarlos.
 */
object PhotoEncoder {
    /** Tamaño máximo del original que se lee en memoria. */
    const val MAX_INPUT_BYTES = 64 * 1024 * 1024

    /**
     * Máximo de píxeles decodificados (R26): si el submuestreo normal deja más, se dobla una vez más
     * `inSampleSize` aunque el lado mayor quede algo por debajo de `maxSidePx`.
     */
    const val MAX_DECODED_PIXELS = 24_000_000L

    private val QUALITIES = intArrayOf(85, 75, 65, 55, 45)
    private const val SHRINK_FACTOR = 0.75
    private const val MAX_SHRINKS = 3
    private const val READ_CHUNK = 64 * 1024

    /**
     * Recodifica [input] (bloqueante, usa CPU: llamarla fuera del hilo principal). Rellena [input] con
     * ceros al terminar, salga bien o mal. Lanza [PhotoException] con `UNREADABLE` o `TOO_LARGE`.
     */
    fun encode(input: ByteArray, maxSidePx: Int): ByteArray = encode(input, maxSidePx, PeerSession.MAX_PHOTO_BYTES) {}

    /**
     * Como [encode], con el límite de bytes [maxBytes] (las pruebas lo bajan) y [checkActive], que se
     * llama en cada paso de los bucles y lanza `CancellationException` para dejarlo enseguida.
     */
    internal fun encode(input: ByteArray, maxSidePx: Int, maxBytes: Int, checkActive: () -> Unit): ByteArray {
        require(maxSidePx > 0) { "maxSidePx debe ser positivo" }
        var current: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PhotoException(PhotoException.Reason.UNREADABLE)

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSidePx)
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                // Mutable para poder borrar sus píxeles con eraseColor antes de reciclarlo.
                inMutable = true
            }
            val decoded = BitmapFactory.decodeByteArray(input, 0, input.size, options)
                ?: throw PhotoException(PhotoException.Reason.UNREADABLE)
            current = decoded
            checkActive()

            // Primer bitmap orientado y escalado (sobre blanco); el decodificado se libera enseguida.
            current = render(decoded, orientationMatrix(readOrientation(input)), max(decoded.width, decoded.height), maxSidePx)
            decoded.wipeAndRecycle()

            for (attempt in 0..MAX_SHRINKS) {
                checkActive()
                if (attempt > 0) {
                    // Se reduce desde el tamaño actual (que puede ser menor que maxSidePx), no desde el ajuste.
                    val previous = current!!
                    val side = (max(previous.width, previous.height) * SHRINK_FACTOR).toInt().coerceAtLeast(1)
                    current = render(previous, Matrix(), max(previous.width, previous.height), side)
                    previous.wipeAndRecycle()
                }
                compressFitting(current!!, maxBytes, checkActive)?.let { return it }
            }
            throw PhotoException(PhotoException.Reason.TOO_LARGE)
        } catch (_: OutOfMemoryError) {
            // El bitmap no cabe en memoria: se trata como foto demasiado grande, sin cerrar la app.
            throw PhotoException(PhotoException.Reason.TOO_LARGE)
        } finally {
            current?.wipeAndRecycle()
            input.fill(0)
        }
    }

    /**
     * Lee el `Uri` en memoria en `Dispatchers.IO` (más de [MAX_INPUT_BYTES] ⇒ `TOO_LARGE`) y lo recodifica
     * en `Dispatchers.Default`. Ambos pasos corren bajo `NonCancellable` para no perder la referencia a un
     * resultado sin borrar: si la corrutina se cancela, los bucles lo notan y paran, y lo que haya salido
     * se rellena con ceros antes de lanzar `CancellationException`.
     */
    suspend fun encode(resolver: ContentResolver, uri: Uri, maxSidePx: Int): ByteArray {
        val job = currentCoroutineContext()[Job]
        val checkActive = { if (job != null && !job.isActive) throw CancellationException("Foto cancelada") }
        val input = withContext(NonCancellable + Dispatchers.IO) { readAll(resolver, uri, checkActive) }
        if (job != null && !job.isActive) {
            input.fill(0)
            throw CancellationException("Foto cancelada")
        }
        val output = withContext(NonCancellable + Dispatchers.Default) {
            encode(input, maxSidePx, PeerSession.MAX_PHOTO_BYTES, checkActive)
        }
        if (job != null && !job.isActive) {
            output.fill(0)
            throw CancellationException("Foto cancelada")
        }
        return output
    }

    /** `inSampleSize`: potencia de 2 con el lado mayor aún ≥ [maxSidePx] y, aun así, ≤ [MAX_DECODED_PIXELS]. */
    internal fun sampleSize(width: Int, height: Int, maxSidePx: Int): Int {
        var sample = 1
        while (max(width, height) / (sample * 2) >= maxSidePx) sample *= 2
        while ((width / sample).toLong() * (height / sample) > MAX_DECODED_PIXELS) sample *= 2
        return sample
    }

    private fun readAll(resolver: ContentResolver, uri: Uri, checkActive: () -> Unit): ByteArray {
        val known = knownSize(resolver, uri)
        if (known > MAX_INPUT_BYTES) throw PhotoException(PhotoException.Reason.TOO_LARGE)
        val buffer = WipeableBuffer(if (known > 0) known.toInt() else READ_CHUNK)
        try {
            val stream = try {
                resolver.openInputStream(uri)
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } ?: throw PhotoException(PhotoException.Reason.UNREADABLE)
            stream.use { copyLimited(it, buffer, checkActive) }
            return buffer.take()
        } catch (_: IOException) {
            throw PhotoException(PhotoException.Reason.UNREADABLE)
        } catch (_: SecurityException) {
            throw PhotoException(PhotoException.Reason.UNREADABLE)
        } catch (_: OutOfMemoryError) {
            throw PhotoException(PhotoException.Reason.TOO_LARGE)
        } finally {
            buffer.wipe()
        }
    }

    /** Tamaño del contenido si el proveedor lo sabe (para reservar el búfer de una vez); si no, -1. */
    private fun knownSize(resolver: ContentResolver, uri: Uri): Long {
        val fromDescriptor = try {
            val afd = resolver.openAssetFileDescriptor(uri, "r")
            try {
                afd?.length ?: -1
            } finally {
                afd?.close()
            }
        } catch (_: Exception) {
            // Sin descriptor (o el proveedor no lo da): se prueba con OpenableColumns.SIZE.
            -1
        }
        if (fromDescriptor > 0) return fromDescriptor
        return try {
            val cursor = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            try {
                val column = cursor?.getColumnIndex(OpenableColumns.SIZE) ?: -1
                if (cursor != null && column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else -1
            } finally {
                cursor?.close()
            }
        } catch (_: Exception) {
            // Tamaño desconocido: el búfer crece al leer.
            -1
        }.takeIf { it > 0 } ?: -1
    }

    private fun copyLimited(stream: InputStream, out: WipeableBuffer, checkActive: () -> Unit) {
        val chunk = ByteArray(READ_CHUNK)
        try {
            while (true) {
                checkActive()
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

    /**
     * Bitmap nuevo (mutable, opaco, sRGB) con [source] transformado por [orientation] y escalado para que
     * su lado mayor sea min([sourceLong], [side]) (nunca se amplía), pintado sobre blanco: las imágenes
     * con transparencia no quedan con fondo negro en el JPEG.
     */
    private fun render(source: Bitmap, orientation: Matrix, sourceLong: Int, side: Int): Bitmap {
        val bounds = RectF(0f, 0f, source.width.toFloat(), source.height.toFloat())
        val oriented = RectF().also { orientation.mapRect(it, bounds) }
        val scale = minOf(1.0, side.toDouble() / sourceLong)
        val targetW = (oriented.width() * scale).roundToInt().coerceAtLeast(1)
        val targetH = (oriented.height() * scale).roundToInt().coerceAtLeast(1)
        val matrix = Matrix(orientation).apply {
            postTranslate(-oriented.left, -oriented.top)
            postScale(targetW / oriented.width(), targetH / oriented.height())
        }
        val target = createBitmap(targetW, targetH)
        val canvas = Canvas(target)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG))
        return target
    }

    /** Comprime [bitmap] a la primera calidad que quepa en [maxBytes]; `null` si ni a la mínima cabe. */
    private fun compressFitting(bitmap: Bitmap, maxBytes: Int, checkActive: () -> Unit): ByteArray? {
        val out = WipeableBuffer(READ_CHUNK)
        try {
            for (quality in QUALITIES) {
                checkActive()
                out.reset()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw PhotoException(PhotoException.Reason.UNREADABLE)
                if (out.size() <= maxBytes) return stripAppSegments(out.toByteArray())
            }
            return null
        } finally {
            out.wipe()
        }
    }

    /**
     * Quita del JPEG de `compress` los segmentos APP1…APP15 y COM anteriores a los datos (SOS). Skia
     * añade un perfil ICC sRGB en APP2: no identifica a nadie, pero la salida no lleva ningún metadato
     * (sin perfil, los visores suponen sRGB, que es lo que es). Solo queda APP0 (JFIF). Rellena [jpeg] con
     * ceros y devuelve un array nuevo; si la estructura no es la esperada, `UNREADABLE` (falla cerrado).
     */
    internal fun stripAppSegments(jpeg: ByteArray): ByteArray {
        val out = WipeableBuffer(jpeg.size)
        try {
            if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) {
                throw PhotoException(PhotoException.Reason.UNREADABLE)
            }
            out.write(jpeg, 0, 2)
            var i = 2
            while (true) {
                if (i + 4 > jpeg.size || jpeg[i] != 0xFF.toByte()) throw PhotoException(PhotoException.Reason.UNREADABLE)
                val marker = jpeg[i + 1].toInt() and 0xFF
                if (marker == 0xDA) {
                    out.write(jpeg, i, jpeg.size - i)
                    break
                }
                val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
                if (length < 2 || i + 2 + length > jpeg.size) throw PhotoException(PhotoException.Reason.UNREADABLE)
                if (marker !in 0xE1..0xEF && marker != 0xFE) out.write(jpeg, i, 2 + length)
                i += 2 + length
            }
            return out.take()
        } finally {
            out.wipe()
            jpeg.fill(0)
        }
    }

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

    /** Borra los píxeles (si se puede) y recicla. */
    private fun Bitmap.wipeAndRecycle() {
        if (isRecycled) return
        if (isMutable) eraseColor(0)
        recycle()
    }

    /** `ByteArrayOutputStream` cuyo búfer interno se puede rellenar con ceros. */
    private class WipeableBuffer(initial: Int) : ByteArrayOutputStream(initial) {
        fun wipe() = buf.fill(0)

        /**
         * El contenido: si llena el búfer exacto (tamaño conocido de antemano) se entrega el búfer mismo,
         * sin otra copia; si no, una copia (y el búfer se borra con [wipe]).
         */
        fun take(): ByteArray {
            if (count != buf.size) return toByteArray()
            val exact = buf
            buf = ByteArray(0)
            count = 0
            return exact
        }

        // Al crecer, el búfer viejo se copia y se abandona: se rellena con ceros antes.
        override fun write(b: ByteArray, off: Int, len: Int) {
            val old = buf
            super.write(b, off, len)
            if (old !== buf) old.fill(0)
        }

        override fun write(b: Int) {
            val old = buf
            super.write(b)
            if (old !== buf) old.fill(0)
        }
    }
}
