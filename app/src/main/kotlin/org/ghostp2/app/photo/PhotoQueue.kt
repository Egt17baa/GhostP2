package org.ghostp2.app.photo

import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Cola de fotos salientes, en memoria. Un solo trabajador: codifica una foto, la entrega al motor con
 * [send] y espera con [awaitFinished] a que termine su envío antes de codificar la siguiente (una sola
 * foto en vuelo). Entre esperando y en curso caben como mucho [capacity] fotos.
 *
 * [send] devuelve el id del elemento del historial o `null` si no se pudo enviar (motor no `Online` ⇒
 * [PhotoException.Reason.NOT_ACTIVE]). Tras [send] los bytes codificados se rellenan con ceros: el motor
 * guarda su propia copia.
 */
class PhotoQueue(
    private val scope: CoroutineScope,
    private val encode: suspend (Uri) -> ByteArray,
    private val send: (contactId: String, jpeg: ByteArray) -> String?,
    private val awaitFinished: suspend (contactId: String, itemId: String) -> Unit,
    private val capacity: Int = 3,
) {
    private class Item(val contactId: String, val uri: Uri)

    // Protegido por `lock`.
    private val lock = Any()
    private val waiting = ArrayDeque<Item>()
    private var inFlight: Item? = null
    private var worker: Job? = null
    private var generation = 0L

    private val mutablePreparing = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Fotos por id de contacto que esperan o se están codificando (aún no entregadas al motor). */
    val preparing: StateFlow<Map<String, Int>> = mutablePreparing.asStateFlow()

    private val mutableErrors = MutableSharedFlow<Pair<String, PhotoException.Reason>>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Fotos que no se enviaron: (id de contacto, motivo). Sin repetición para suscriptores nuevos. */
    val errors: SharedFlow<Pair<String, PhotoException.Reason>> = mutableErrors.asSharedFlow()

    /** Añade una foto para [contactId]; `false` (sin añadir nada) si ya hay [capacity] fotos. */
    fun enqueue(contactId: String, uri: Uri): Boolean = synchronized(lock) {
        if (waiting.size + (if (inFlight != null) 1 else 0) >= capacity) return false
        waiting.addLast(Item(contactId, uri))
        adjustPreparing(contactId, +1)
        if (worker == null) {
            val gen = generation
            worker = scope.launch { work(gen) }
        }
        true
    }

    /** Descarta las fotos que esperan y cancela la que está en curso (al detener el motor). */
    fun clear() {
        val job = synchronized(lock) {
            generation++
            waiting.clear()
            inFlight = null
            mutablePreparing.value = emptyMap()
            worker.also { worker = null }
        }
        job?.cancel()
    }

    private suspend fun work(gen: Long) {
        while (true) {
            val item = synchronized(lock) {
                if (gen != generation) return
                val next = waiting.removeFirstOrNull()
                if (next == null) {
                    worker = null
                    return
                }
                inFlight = next
                next
            }
            try {
                process(item, gen)
            } finally {
                synchronized(lock) { if (gen == generation) inFlight = null }
            }
        }
    }

    private suspend fun process(item: Item, gen: Long) {
        val jpeg = try {
            encode(item.uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: PhotoException) {
            fail(item, gen, e.reason)
            return
        } catch (_: Exception) {
            fail(item, gen, PhotoException.Reason.UNREADABLE)
            return
        }
        val itemId = try {
            synchronized(lock) {
                // Un clear() durante la codificación: no se envía nada.
                if (gen != generation) return
                adjustPreparing(item.contactId, -1)
            }
            send(item.contactId, jpeg)
        } catch (_: Exception) {
            // Inesperado (el motor rechaza la foto): se trata como no enviada.
            null
        } finally {
            jpeg.fill(0)
        }
        if (itemId == null) {
            report(item, gen, PhotoException.Reason.NOT_ACTIVE)
            return
        }
        try {
            awaitFinished(item.contactId, itemId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // No se pudo seguir el envío: se pasa a la siguiente foto.
        }
    }

    private fun fail(item: Item, gen: Long, reason: PhotoException.Reason) {
        synchronized(lock) {
            if (gen != generation) return
            adjustPreparing(item.contactId, -1)
        }
        report(item, gen, reason)
    }

    private fun report(item: Item, gen: Long, reason: PhotoException.Reason) {
        if (synchronized(lock) { gen == generation }) mutableErrors.tryEmit(item.contactId to reason)
    }

    // Con `lock` tomado.
    private fun adjustPreparing(contactId: String, delta: Int) {
        val current = mutablePreparing.value
        val count = (current[contactId] ?: 0) + delta
        mutablePreparing.value = if (count > 0) current + (contactId to count) else current - contactId
    }
}
