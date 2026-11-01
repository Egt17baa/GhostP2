package org.ghostp2.app.photo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Decodifica un JPEG submuestreado: `inSampleSize` (potencia de 2) sube mientras el lado mayor siga
 * siendo ≥ [sidePx]. El bitmap es mutable (para poder borrar sus píxeles al soltarlo). `null` si no es
 * una imagen decodificable; un `OutOfMemoryError` se propaga (es pasajero: quien llama decide).
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
        BitmapFactory.decodeByteArray(
            bytes,
            0,
            bytes.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inMutable = true
            },
        )
    } catch (_: RuntimeException) {
        null
    }
}

/**
 * Hilos para decodificar fotos (miniaturas y visor): como mucho 2 a la vez, para acotar la memoria de
 * las decodificaciones en curso.
 */
internal val PhotoDecodeDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(2)

/** Borra los píxeles de un bitmap que se suelta (sin reciclarlo: Compose podría estar aún pintándolo). */
internal fun ImageBitmap.erase() {
    val bitmap = asAndroidBitmap()
    if (!bitmap.isRecycled && bitmap.isMutable) bitmap.eraseColor(0)
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
 * (`Bitmap.byteCount`). Solo se recuerdan como fallidas las fotos que el decodificador rechaza con los
 * bytes intactos (no se reintentan en cada recomposición); una falta de memoria o una puesta a cero no
 * las marca. [full] no usa caché (visor). Es seguro llamarla desde cualquier hilo; conviene hacerlo en
 * [PhotoDecodeDispatcher].
 *
 * Un `null` significa que la foto no se puede mostrar: el motor la puso a cero ("Foto descartada para
 * liberar memoria"), no es una imagen válida o no cabe ahora en memoria. Nunca se devuelve una imagen
 * decodificada de bytes que se estaban poniendo a cero: se comprueba la cabecera tras copiar y otra vez
 * tras decodificar. Los bitmaps que sueltan [retain], [clear] o una puesta a cero se borran
 * (`eraseColor(0)`); los que expulsa la LRU por tamaño, no (pueden seguir en pantalla).
 */
class PhotoBitmaps(
    maxBytes: Int = 32 * 1024 * 1024,
    private val decode: (ByteArray, Int) -> Bitmap? = ::decodeSampled,
) {
    private val cache = object : LruCache<String, ImageBitmap>(maxBytes) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.asAndroidBitmap().byteCount
    }

    // Protegen `failed` y `retained`, y hacen atómicos el `put` y `retain`/`clear` frente a la caché.
    private val lock = Any()

    // Ids que el decodificador rechazó con los bytes intactos.
    private val failed = HashSet<String>()

    // Último conjunto de [retain] (`null` ⇒ aún no se llamó: se admite cualquier id).
    private var retained: Set<String>? = null

    /** Resultado de una lectura: imagen, rechazo del decodificador (bytes intactos) o fallo pasajero. */
    private sealed interface Read {
        class Image(val image: ImageBitmap) : Read

        data object Rejected : Read

        /** Bytes puestos a cero, o sin memoria ahora: no se marca como fallida. */
        data object Unavailable : Read
    }

    /**
     * Miniatura del elemento [itemId] (lado mayor ≥ [sidePx] tras submuestrear) o `null` si no se puede
     * mostrar. Con la foto ya en caché solo se comprueba que el array vivo no se haya puesto a cero. Solo
     * se guarda en caché si [itemId] sigue en el último [retain] y la cabecera sigue intacta.
     */
    fun thumbnail(itemId: String, jpeg: ByteArray, sidePx: Int = 1024): ImageBitmap? {
        if (!hasJpegHeader(jpeg)) {
            // Puesta a cero por el motor: se suelta (y se borra) también su bitmap.
            synchronized(lock) { cache.remove(itemId) }?.erase()
            return null
        }
        cache.get(itemId)?.let { return it }
        if (synchronized(lock) { itemId in failed }) return null
        return when (val result = read(jpeg, sidePx)) {
            is Read.Image -> {
                synchronized(lock) {
                    if (retained.let { it == null || itemId in it } && hasJpegHeader(jpeg)) cache.put(itemId, result.image)
                }
                result.image
            }
            Read.Rejected -> {
                synchronized(lock) { failed += itemId }
                null
            }
            Read.Unavailable -> null
        }
    }

    /**
     * La miniatura de [itemId] si ya está en caché y el array vivo [jpeg] sigue intacto; nunca decodifica
     * (se puede llamar al componer, para no parpadear al volver a una foto ya vista).
     */
    fun cached(itemId: String, jpeg: ByteArray): ImageBitmap? = if (hasJpegHeader(jpeg)) cache.get(itemId) else null

    /** Imagen completa (lado mayor ≥ [sidePx] tras submuestrear), sin caché; `null` si no se puede mostrar. */
    fun full(jpeg: ByteArray, sidePx: Int): ImageBitmap? = (read(jpeg, sidePx) as? Read.Image)?.image

    /** Suelta (y borra) los bitmaps y los fallos recordados de los elementos que no estén en [ids]. */
    fun retain(ids: Set<String>) {
        val dropped = synchronized(lock) {
            retained = ids.toSet()
            failed.retainAll(ids)
            cache.snapshot().keys.filterNot { it in ids }.mapNotNull { cache.remove(it) }
        }
        dropped.forEach { it.erase() }
    }

    /** Suelta (y borra) todo; después no se guarda nada más en caché. */
    fun clear() {
        val dropped = synchronized(lock) {
            retained = emptySet()
            failed.clear()
            cache.snapshot().values.also { cache.evictAll() }
        }
        dropped.forEach { it.erase() }
    }

    /** Ids con bitmap en caché (pruebas). */
    internal fun cachedIds(): Set<String> = cache.snapshot().keys

    /** Bytes de píxeles en caché (pruebas). */
    internal fun cachedBytes(): Int = cache.size()

    private fun read(jpeg: ByteArray, sidePx: Int): Read {
        val bitmap = try {
            withIntactCopy(jpeg) { copy ->
                try {
                    decode(copy, sidePx) ?: Unit
                } catch (_: RuntimeException) {
                    Unit
                }
            }
        } catch (_: OutOfMemoryError) {
            return Read.Unavailable
        }
        // Validación del resultado: el array vivo aún intacto (si el motor lo puso a cero antes o mientras
        // se decodificaba, la foto ya está descartada y no se muestra) y dimensiones reales.
        if (!hasJpegHeader(jpeg)) {
            (bitmap as? Bitmap)?.asImageBitmap()?.erase()
            return Read.Unavailable
        }
        if (bitmap !is Bitmap) return Read.Rejected
        if (bitmap.width <= 0 || bitmap.height <= 0) return Read.Rejected
        return Read.Image(bitmap.asImageBitmap())
    }
}
