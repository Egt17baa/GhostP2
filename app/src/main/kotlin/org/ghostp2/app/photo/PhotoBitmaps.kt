package org.ghostp2.app.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Decodifica un JPEG submuestreado: `inSampleSize` (potencia de 2) sube mientras el lado mayor siga
 * siendo ≥ [sidePx]. `null` si no es una imagen decodificable o no cabe en memoria (nunca lanza).
 */
internal fun decodeSampled(bytes: ByteArray, sidePx: Int): Bitmap? {
    if (sidePx <= 0) return null
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / (sample * 2) >= sidePx) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (_: RuntimeException) {
        null
    } catch (_: OutOfMemoryError) {
        null
    }
}

/**
 * Lectura segura del array VIVO de una foto del motor ([org.ghostp2.engine.ChatContent.Photo.jpeg]),
 * que el motor puede rellenar con ceros en cualquier momento (presupuesto de fotos, "Borrar chat",
 * parar el motor), incluso mientras se lee. Lo comparten [PhotoBitmaps] y [PhotoSaver]:
 *
 * 1. Se copia el array.
 * 2. **Después** de copiar se comprueba la cabecera JPEG (`FF D8`) del array vivo (y de la copia). El
 *    motor pone a cero desde el índice 0 hacia delante, así que si la cabecera sigue intacta tras la
 *    copia, la puesta a cero no había empezado mientras se copiaba: la copia está entera.
 * 3. Se usa la copia con [block] y, al terminar, la copia se rellena con ceros.
 *
 * `null` si el array ya estaba (o empezó a estar) a cero, o si [block] devuelve `null`.
 */
internal inline fun <T : Any> withIntactCopy(jpeg: ByteArray, block: (ByteArray) -> T?): T? {
    if (jpeg.size < 2) return null
    val copy = jpeg.copyOf()
    try {
        if (!hasJpegHeader(jpeg) || !hasJpegHeader(copy)) return null
        return block(copy)
    } finally {
        copy.fill(0)
    }
}

/** El array empieza por la marca SOI de JPEG (`FF D8`): no se ha empezado a poner a cero. */
internal fun hasJpegHeader(bytes: ByteArray): Boolean =
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()

/**
 * Bitmaps de las fotos del chat, solo en memoria (nunca en disco). [thumbnail] decodifica cada elemento
 * una sola vez y lo guarda en una caché LRU por id de elemento, limitada a [maxBytes] bytes de píxeles
 * (`Bitmap.byteCount`); los fallos también se recuerdan (no se reintenta en cada recomposición). [full]
 * no usa caché (visor). Es seguro llamarla desde cualquier hilo; conviene fuera del principal.
 *
 * Un `null` significa que la foto no se puede mostrar: el motor la puso a cero ("Foto descartada para
 * liberar memoria") o no es una imagen válida. Nunca se devuelve una imagen decodificada de bytes que
 * se estaban poniendo a cero: se comprueba la cabecera tras copiar y otra vez tras decodificar.
 */
class PhotoBitmaps(
    maxBytes: Int = 32 * 1024 * 1024,
    private val decode: (ByteArray, Int) -> Bitmap? = ::decodeSampled,
) {
    private val cache = object : LruCache<String, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

    // Ids cuya decodificación falló (protegido por sí mismo).
    private val failed = HashSet<String>()

    /**
     * Miniatura del elemento [itemId] (lado mayor ≥ [sidePx] tras submuestrear) o `null` si no se puede
     * mostrar. Con la foto ya en caché solo se comprueba que el array vivo no se haya puesto a cero.
     */
    fun thumbnail(itemId: String, jpeg: ByteArray, sidePx: Int = 1024): ImageBitmap? {
        if (!hasJpegHeader(jpeg)) {
            // Puesta a cero por el motor: se suelta también su bitmap.
            cache.remove(itemId)
            return null
        }
        cache.get(itemId)?.let { return it }
        if (synchronized(failed) { itemId in failed }) return null
        val image = read(jpeg, sidePx)
        if (image == null) {
            // Si fue por la puesta a cero, el elemento pasará a "descartada" y no se volverá a pedir.
            synchronized(failed) { failed += itemId }
            return null
        }
        cache.put(itemId, image)
        return image
    }

    /**
     * La miniatura de [itemId] si ya está en caché y el array vivo [jpeg] sigue intacto; nunca decodifica
     * (se puede llamar al componer, para no parpadear al volver a una foto ya vista).
     */
    fun cached(itemId: String, jpeg: ByteArray): ImageBitmap? = if (hasJpegHeader(jpeg)) cache.get(itemId) else null

    /** Imagen completa (lado mayor ≥ [sidePx] tras submuestrear), sin caché; `null` si no se puede mostrar. */
    fun full(jpeg: ByteArray, sidePx: Int): ImageBitmap? = read(jpeg, sidePx)

    /** Suelta los bitmaps (y los fallos recordados) de los elementos que no estén en [ids]. */
    fun retain(ids: Set<String>) {
        cache.snapshot().keys.filterNot { it in ids }.forEach { cache.remove(it) }
        synchronized(failed) { failed.retainAll(ids) }
    }

    /** Suelta todo. */
    fun clear() {
        cache.evictAll()
        synchronized(failed) { failed.clear() }
    }

    /** Ids con bitmap en caché (pruebas). */
    internal fun cachedIds(): Set<String> = cache.snapshot().keys

    /** Bytes de píxeles en caché (pruebas). */
    internal fun cachedBytes(): Int = cache.size()

    private fun read(jpeg: ByteArray, sidePx: Int): ImageBitmap? {
        val bitmap = withIntactCopy(jpeg) { copy ->
            try {
                decode(copy, sidePx)
            } catch (_: RuntimeException) {
                null
            } catch (_: OutOfMemoryError) {
                null
            }
        } ?: return null
        // Validación del resultado: dimensiones reales y el array vivo aún intacto (si el motor lo puso a
        // cero mientras se decodificaba, la foto ya está descartada y no se muestra).
        if (bitmap.width <= 0 || bitmap.height <= 0 || !hasJpegHeader(jpeg)) return null
        return bitmap.asImageBitmap()
    }
}
