package org.ghostp2.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.ghostp2.core.crypto.Primitives
import org.ghostp2.core.protocol.FailReason
import org.ghostp2.core.protocol.SessionEvent

/** Sentido de un mensaje: recibido (`IN`) o enviado por mí (`OUT`). */
enum class Direction { IN, OUT }

/** Contenido de un mensaje. Los `toString` no muestran contenido. */
sealed interface ChatContent {
    data class Text(val text: String) : ChatContent {
        override fun toString(): String = "Text(${text.length} caracteres)"
    }

    /**
     * Foto JPEG. [jpeg] es el array VIVO del historial del motor (no una copia): se rellena con ceros
     * en cuanto el elemento sale del historial (límite de 500, [MessengerEngine.clearConversation] o
     * [MessengerEngine.stop]). Quien lo lea (la interfaz) no debe modificarlo y, si necesita los bytes
     * más allá de mostrarlos en ese momento, debe copiarlos.
     */
    class Photo(val jpeg: ByteArray) : ChatContent {
        override fun equals(other: Any?): Boolean = other is Photo && jpeg.contentEquals(other.jpeg)

        override fun hashCode(): Int = jpeg.contentHashCode()

        override fun toString(): String = "Photo(${jpeg.size} bytes)"
    }
}

/**
 * Estado de entrega. Los salientes empiezan en [Sending] y terminan exactamente una vez en
 * [Delivered] o [Failed]; nunca retroceden. Los entrantes están siempre en [Received].
 */
sealed interface DeliveryStatus {
    data object Received : DeliveryStatus

    /**
     * Enviándose. Fotos: `totalBytes` = tamaño y `sentBytes` crece con cada trozo escrito. Textos:
     * siempre `Sending(0, 0)` (no informan de progreso), así que `totalBytes` puede ser 0: no dividir
     * por él sin comprobarlo.
     */
    data class Sending(val sentBytes: Int, val totalBytes: Int) : DeliveryStatus
    data object Delivered : DeliveryStatus
    data class Failed(val reason: FailReason) : DeliveryStatus
}

data class ChatItem(
    val id: String,
    val direction: Direction,
    val content: ChatContent,
    val status: DeliveryStatus,
    val timestampMillis: Long,
)

/** Máximo de elementos por conversación; al superarlo se descartan los más antiguos. */
internal const val MAX_ITEMS_PER_CONVERSATION = 500

/**
 * Historial en memoria del motor, por id de contacto (nunca se escribe en disco).
 *
 * **Un cerrojo para todo.** Insertar un saliente, aplicar un evento, borrar y publicar el mapa en
 * [conversations] se hacen con `lock` tomado; cada cambio publica un mapa nuevo (inmutable), así
 * que no hay actualizaciones perdidas con envíos y recepciones simultáneos.
 *
 * **Id antes que el evento.** El envío a `PeerSession` (que no suspende y devuelve el id antes de
 * emitir nada de él) se hace DENTRO del cerrojo y el elemento se inserta en el mismo paso. El
 * recolector de eventos corre en otro hilo; si recibe un evento de ese id mientras la llamada aún
 * no ha vuelto, espera al cerrojo y lo aplica después de la inserción. Así no hace falta guardar
 * eventos de ids desconocidos. Sin interbloqueo: dentro del cerrojo `PeerSession` solo usa su propio
 * cerrojo y `tryEmit` sobre un búfer ilimitado, y la escritura en el socket ocurre en `Dispatchers.IO`.
 *
 * **Envíos rechazados por una sesión que se cierra.** Si la sesión se estaba cerrando, `PeerSession`
 * devuelve el id y emite `Failed(NOT_CONNECTED)` más tarde, quizá después de `Closed`, cuando su
 * recolector ya terminó: ese evento se perdería. Los envíos aceptados reciben siempre su `Delivered`
 * o `Failed` ANTES de `Closed`, así que al procesar `Closed` de una sesión todo lo que salió por ella y
 * sigue en `Sending` es un rechazo: pasa a `Failed(NOT_CONNECTED)`. Si la sesión ya no está abierta al
 * enviar, ni se la llama: fallo inmediato con un id propio.
 *
 * **Fotos.** El array de una foto saliente es una copia propia del motor, compartida entre el
 * historial y `PeerSession` (que lo lee mientras envía). Al quitar una foto del historial se rellena
 * con ceros, salvo si su envío sigue en curso: entonces se borra al llegar su `Delivered`/`Failed`.
 * [clearAll] (al parar el motor, con las sesiones ya cerradas) los borra todos.
 *
 * Entre [clearAll] y el siguiente [open] no se guarda nada: los envíos devuelven un id sin llamar a
 * la sesión y las fotos (salientes o entrantes) se borran en el acto. Igual con un contacto que no
 * existe (`known` falso, evaluado con el cerrojo tomado): nunca se crea una conversación fantasma.
 */
internal class Conversations(
    private val clock: () -> Long,
    private val maxItems: Int = MAX_ITEMS_PER_CONVERSATION,
) {
    /** Por dónde sale un envío: la sesión (solo su identidad), si sigue abierta y cómo enviar. */
    class Outbound(val session: Any, val isOpen: () -> Boolean, val send: () -> String)

    private val lock = Any()
    private val state = MutableStateFlow<Map<String, List<ChatItem>>>(emptyMap())
    val conversations: StateFlow<Map<String, List<ChatItem>>> = state.asStateFlow()

    // Todo lo de abajo solo con `lock` tomado.
    private var accepting = false

    /** Envíos sin `Delivered`/`Failed` todavía, por id: la sesión por la que salieron. */
    private val sending = HashMap<String, Any>()

    /** Fotos cuyo envío sigue en curso (`PeerSession` lee el array), por id. */
    private val inFlightPhotos = HashMap<String, ByteArray>()

    /** Fotos en curso ya quitadas del historial: se borran al terminar su envío. */
    private val wipeWhenDone = HashSet<String>()

    /** Empieza a guardar (al arrancar el motor). */
    fun open() = synchronized(lock) { accepting = true }

    /**
     * Añade un saliente y devuelve su id. Con [outbound] abierto, el id es el de `send()` (llamado con
     * el cerrojo tomado) y el estado `Sending`; sin sesión (o cerrada), un id nuevo con
     * `Failed(NOT_CONNECTED)`: nunca se encola. Si `send()` lanza, no se guarda nada y se relanza.
     * Si no se está guardando o [known] (evaluado con el cerrojo tomado) es falso: id nuevo, nada
     * guardado, ni se llama a la sesión, y la foto se rellena con ceros.
     */
    fun addOutgoing(
        contactId: String,
        content: ChatContent,
        known: () -> Boolean,
        outbound: Outbound?,
    ): String = synchronized(lock) {
        if (!accepting || !known()) {
            wipe(content)
            return newId()
        }
        val item = if (outbound != null && outbound.isOpen()) {
            val id = outbound.send()
            sending[id] = outbound.session
            if (content is ChatContent.Photo) inFlightPhotos[id] = content.jpeg
            val total = (content as? ChatContent.Photo)?.jpeg?.size ?: 0
            ChatItem(id, Direction.OUT, content, DeliveryStatus.Sending(0, total), clock())
        } else {
            ChatItem(newId(), Direction.OUT, content, DeliveryStatus.Failed(FailReason.NOT_CONNECTED), clock())
        }
        append(contactId, item)
        item.id
    }

    /** Aplica un evento de [session]; devuelve true si llegó un mensaje (para `Notice.MessageArrived`). */
    fun onEvent(contactId: String, session: Any, event: SessionEvent): Boolean = synchronized(lock) {
        when (event) {
            is SessionEvent.TextReceived -> return receive(contactId, event.id, ChatContent.Text(event.text))
            is SessionEvent.PhotoReceived -> return receive(contactId, event.id, ChatContent.Photo(event.jpeg))
            is SessionEvent.PhotoProgress -> if (sending[event.id] === session) {
                updateStatus(contactId, event.id) { current ->
                    if (current is DeliveryStatus.Sending && event.sentBytes > current.sentBytes) {
                        DeliveryStatus.Sending(event.sentBytes, event.totalBytes)
                    } else {
                        null
                    }
                }
            }
            is SessionEvent.Delivered -> if (sending[event.id] === session) finish(contactId, event.id, DeliveryStatus.Delivered)
            is SessionEvent.Failed -> if (sending[event.id] === session) finish(contactId, event.id, DeliveryStatus.Failed(event.reason))
            is SessionEvent.Closed -> {
                val rejected = sending.filterValues { it === session }.keys.toList()
                rejected.forEach { finish(contactId, it, DeliveryStatus.Failed(FailReason.NOT_CONNECTED)) }
            }
            SessionEvent.PeerUnpaired -> Unit
        }
        false
    }

    /** Borra la conversación del contacto (fotos con ceros); el resto no cambia. */
    fun clear(contactId: String) = synchronized(lock) {
        val removed = state.value[contactId] ?: return@synchronized
        removed.forEach(::release)
        state.value = state.value - contactId
    }

    /**
     * Borra todo y deja de guardar hasta el próximo [open]. Llamar con las sesiones ya cerradas:
     * borra también las fotos cuyo envío figuraba en curso.
     */
    fun clearAll() = synchronized(lock) {
        accepting = false
        state.value.values.forEach { list -> list.forEach { wipe(it.content) } }
        inFlightPhotos.values.forEach { Primitives.wipe(it) }
        inFlightPhotos.clear()
        wipeWhenDone.clear()
        sending.clear()
        state.value = emptyMap()
    }

    private fun receive(contactId: String, id: String, content: ChatContent): Boolean {
        if (!accepting) {
            wipe(content)
            return false
        }
        append(contactId, ChatItem(id, Direction.IN, content, DeliveryStatus.Received, clock()))
        return true
    }

    /** Fin de un envío: deja de estar en curso (borra la foto si ya no está en el historial) y fija el estado. */
    private fun finish(contactId: String, id: String, status: DeliveryStatus) {
        sending.remove(id)
        val photo = inFlightPhotos.remove(id)
        if (wipeWhenDone.remove(id)) photo?.let { Primitives.wipe(it) }
        updateStatus(contactId, id) { current -> if (current is DeliveryStatus.Sending) status else null }
    }

    /** Cambia el estado del saliente `id` si `next` devuelve uno (null = no cambia). */
    private fun updateStatus(contactId: String, id: String, next: (DeliveryStatus) -> DeliveryStatus?) {
        val list = state.value[contactId] ?: return
        val index = list.indexOfFirst { it.id == id && it.direction == Direction.OUT }
        if (index < 0) return
        val status = next(list[index].status) ?: return
        val updated = list.toMutableList().also { it[index] = it[index].copy(status = status) }
        state.value = state.value + (contactId to updated)
    }

    private fun append(contactId: String, item: ChatItem) {
        val list = state.value[contactId].orEmpty() + item
        val excess = list.size - maxItems
        val kept = if (excess > 0) {
            list.subList(0, excess).forEach(::release)
            list.drop(excess)
        } else {
            list
        }
        state.value = state.value + (contactId to kept)
    }

    /** Un elemento sale del historial: su foto se borra ya o, si aún se está enviando, al terminar. */
    private fun release(item: ChatItem) {
        val content = item.content as? ChatContent.Photo ?: return
        if (inFlightPhotos[item.id] === content.jpeg) wipeWhenDone += item.id else Primitives.wipe(content.jpeg)
    }

    private fun wipe(content: ChatContent) {
        if (content is ChatContent.Photo) Primitives.wipe(content.jpeg)
    }

    private fun newId(): String = Primitives.randomBytes(ID_BYTES).joinToString("") { "%02x".format(it) }

    private companion object {
        /** Como los ids de `PeerSession`: 12 bytes aleatorios en hex. */
        const val ID_BYTES = 12
    }
}
