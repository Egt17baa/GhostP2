package org.ghostp2.core.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.ghostp2.core.crypto.Primitives
import org.ghostp2.core.crypto.RatchetChannel
import java.io.IOException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Lo que una [PeerSession] comunica a la app. Los `toString` no muestran contenido. */
sealed interface SessionEvent {
    data class TextReceived(val id: String, val text: String) : SessionEvent {
        override fun toString(): String = "TextReceived(id=$id, ${text.length} caracteres)"
    }

    class PhotoReceived(val id: String, val jpeg: ByteArray) : SessionEvent {
        override fun equals(other: Any?): Boolean =
            other is PhotoReceived && id == other.id && jpeg.contentEquals(other.jpeg)

        override fun hashCode(): Int = id.hashCode() * 31 + jpeg.contentHashCode()

        override fun toString(): String = "PhotoReceived(id=$id, ${jpeg.size} bytes)"
    }

    /** Progreso de envío de una foto propia: se emite tras escribir cada trozo. */
    data class PhotoProgress(val id: String, val sentBytes: Int, val totalBytes: Int) : SessionEvent

    /** El otro lado acusó recibo del mensaje [id]. */
    data class Delivered(val id: String) : SessionEvent

    /** El mensaje [id] no se entregó y NO se reintentará (no hay cola). */
    data class Failed(val id: String, val reason: FailReason) : SessionEvent

    /** El contacto deshizo el emparejamiento; le sigue `Closed(PEER_UNPAIRED)`. */
    data object PeerUnpaired : SessionEvent

    /** Último evento de la sesión (salvo `Failed(NOT_CONNECTED)` de envíos posteriores). */
    data class Closed(val reason: CloseReason) : SessionEvent
}

enum class FailReason { NOT_CONNECTED, CONNECTION_LOST, NO_ACK }

enum class CloseReason { PEER_BYE, PEER_UNPAIRED, TIMEOUT, SECURITY, IO, LOCAL }

/**
 * Temporizadores de la sesión. Los valores por defecto son los de la especificación; las pruebas
 * usan duraciones cortas en tiempo real. [linger]: cuánto se espera, tras enviar `Bye`/`Unpair`,
 * a que el otro lado cierre la conexión, para no cortarla antes de que lea esa última trama.
 * [ackTimeout] es también el tiempo máximo de UNA escritura de trama (vigilante de escritura).
 */
internal data class SessionTimings(
    val heartbeat: Duration = 20.seconds,
    val idleTimeout: Duration = 60.seconds,
    val ackTimeout: Duration = 30.seconds,
    val linger: Duration = 2.seconds,
)

/**
 * Sesión viva con un contacto sobre un canal ya establecido por [SessionHandshake].
 *
 * Construcción: [scope] debe ser un scope con `SupervisorJob` que viva MÁS que la sesión (p. ej.
 * el del servicio): el lector, los temporizadores y los envíos se lanzan en él, y el fallo de
 * uno no debe cancelar a los demás. Cancelar [scope] cierra la sesión con `LOCAL`. Si hay dos
 * conexiones simultáneas con el mismo contacto, la app elige la ganadora ANTES de crear la
 * `PeerSession` (§6.5: la iniciada por la `identityKey` menor) y, para la perdedora, cierra su
 * flujo y destruye su canal sin enviar `Bye` (un `Bye` se interpretaría como fin de la sesión).
 * Desde que se construye, la sesión es dueña de [stream] y [channel]: los cierra y destruye ella.
 *
 * Semántica de entrega: si la sesión no está abierta los mensajes se DESCARTAN (nunca se encolan):
 * `sendText`/`sendPhoto` devuelven el id y `Failed(id, NOT_CONNECTED)` se emite DESPUÉS de que
 * la llamada vuelva. Cada mensaje enviado termina exactamente en un `Delivered` o un `Failed`.
 * Una foto llega entera o no llega.
 *
 * Ids: [sendText] y [sendPhoto] NO suspenden: validan, generan el id, registran el envío, lo
 * lanzan en [scope] y devuelven el id enseguida. Ningún evento de ese id se emite mientras la
 * llamada está en curso (salvo si [scope] ya estaba cancelado, en cuyo caso el envío falla al
 * instante). Un recolector confinado en el mismo hilo que quien llama (p. ej. el hilo principal o
 * un dispatcher de un solo hilo) recibe siempre los eventos de un id después de que quien llama
 * lo haya guardado; con un recolector en otro hilo, guarde el id y procese los eventos bajo el
 * mismo cerrojo. Los envíos sucesivos desde un mismo hilo salen al cable en ese orden.
 *
 * Eventos: [events] es un SharedFlow sin reproducción y con búfer ilimitado, así que `tryEmit`
 * nunca falla ni se bloquea y un recolector lento no pierde nada. Los eventos emitidos cuando no
 * hay ningún recolector activo se PIERDEN, aunque el `Ack` correspondiente ya se haya enviado
 * (el otro lado verá "entregado"). Por eso [start] suspende hasta que haya al menos un suscriptor
 * (o hasta que la sesión se cierre). Contrato: un único recolector de larga vida propiedad de
 * quien posee la sesión (un repositorio o servicio, NO la interfaz, que va y viene), lanzado
 * antes de [start] y activo mientras dure la sesión. Tras `Closed` solo pueden llegar
 * `Failed(NOT_CONNECTED)` de envíos posteriores.
 * - El recolector NO debe usar `Dispatchers.Unconfined`: los eventos se emiten con un cerrojo
 *   interno tomado y un recolector no confinado se ejecutaría dentro de él.
 * - Como el búfer no tiene límite, si el recolector se atasca se acumulan eventos en memoria,
 *   incluidas fotos recibidas completas (hasta 3 MiB cada una).
 *
 * Cancelación: el trabajo de [sendText]/[sendPhoto] se ejecuta en [scope], no en la corrutina
 * de quien llama, así que nunca queda una foto a medias en el cable por salir de una pantalla.
 * Solo si se cancela [scope] se interrumpe el envío, y entonces la sesión se cierra.
 *
 * Concurrencia:
 * - Un único bucle de lectura (lanzado en [start]).
 * - Todo envío pasa por un [Mutex] (justo, FIFO): cifrar y escribir es atómico, así el orden de
 *   los contadores del ratchet coincide siempre con el orden en el cable.
 * - Vigilante de escritura: si una sola escritura de trama tarda más que el plazo de acuse
 *   (30 s), el otro lado no está leyendo y la sesión se cierra con `IO`.
 * - Los acuses los envía una corrutina aparte (cola de [ACK_QUEUE_CAPACITY]), no el lector, para
 *   que dos lados que escriben a la vez con los búferes llenos no se bloqueen mutuamente. Si la
 *   cola se llena (el otro lado envía sin leer nuestros acuses), la sesión se cierra con `SECURITY`.
 * - Una sola foto saliente a la vez (otro [Mutex]); por eso el receptor rechaza una segunda foto
 *   entrante simultánea.
 * - Los temporizadores (latido, inactividad, acuses) son corrutinas con `delay` en [scope]; el
 *   tiempo de la última trama recibida se mide con el reloj monótono del sistema.
 *
 * Cierre ([teardown]): cancela lector y temporizadores, emite `Failed(CONNECTION_LOST)` para cada
 * mensaje pendiente, descarta en silencio las fotos entrantes incompletas, destruye el canal,
 * cierra el flujo, pone [isOpen] a false y emite `Closed(motivo)` una sola vez. Gana el primer
 * motivo registrado. Si se cancela [scope], la sesión se cierra con `LOCAL`.
 *
 * Nunca se registran (log) contenidos de mensajes.
 */
class PeerSession internal constructor(
    private val stream: FrameStream,
    private val channel: RatchetChannel,
    private val scope: CoroutineScope,
    private val timings: SessionTimings,
) {
    constructor(stream: FrameStream, channel: RatchetChannel, scope: CoroutineScope) :
        this(stream, channel, scope, SessionTimings())

    private val _events = MutableSharedFlow<SessionEvent>(replay = 0, extraBufferCapacity = Int.MAX_VALUE)
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    private val _isOpen = MutableStateFlow(true)
    val isOpen: StateFlow<Boolean> = _isOpen.asStateFlow()

    private val sendMutex = Mutex()
    private val photoMutex = Mutex()

    /** Primer motivo de cierre registrado; distinto de null = la sesión se está cerrando. */
    private val closeReason = AtomicReference<CloseReason?>(null)

    /** Protege el estado de abajo y TODA emisión de eventos (así nada se emite tras `Closed`). */
    private val lock = Any()

    @Volatile
    private var closed = false
    private var started = false
    private val pending = HashMap<String, Pending>()
    private var incoming: IncomingPhoto? = null
    private val jobs = mutableListOf<Job>()
    private var readerJob: Job? = null

    private val acks = Channel<String>(ACK_QUEUE_CAPACITY)

    @Volatile
    private var lastReceived: TimeMark = TimeSource.Monotonic.markNow()

    /** Mensaje propio pendiente de acuse; [awaitingAck] es false mientras una foto aún se envía. */
    private class Pending(var awaitingAck: Boolean, var timer: Job? = null)

    private class IncomingPhoto(val id: String, val totalBytes: Int, val chunkCount: Int) {
        val buffer = ByteArray(totalBytes)
        var nextIndex = 0
    }

    /**
     * Espera a que haya al menos un suscriptor de [events] y arranca el lector, el latido, el
     * vigilante de inactividad y el emisor de acuses. Vuelve también (sin arrancar nada) si la
     * sesión se cierra mientras espera, así que nunca se queda colgada. Idempotente.
     *
     * Uso: lance un único recolector de larga vida ANTES de llamar a start(); los eventos
     * emitidos mientras no hay recolector activo se pierden aunque su `Ack` ya se haya enviado.
     */
    suspend fun start() {
        merge(_events.subscriptionCount.map { it > 0 }, _isOpen.map { !it }).first { it }
        val reader: Job
        synchronized(lock) {
            if (started || closed || closeReason.get() != null) return
            started = true
            lastReceived = TimeSource.Monotonic.markNow()
            reader = scope.launch { readLoop() }
            readerJob = reader
            jobs += reader
            jobs += scope.launch { heartbeatLoop() }
            jobs += scope.launch { idleWatchdog() }
            jobs += scope.launch { ackLoop() }
        }
        // Si el lector termina por cualquier causa (p. ej. se cancela el scope), la sesión se cierra
        reader.invokeOnCompletion { fail(CloseReason.LOCAL) }
    }

    /**
     * Envía un texto (máx. [MAX_TEXT_CHARS] caracteres Unicode). No suspende: devuelve el id
     * enseguida y el envío sigue en [scope] (ver la KDoc de la clase). El plazo de acuse (30 s)
     * empieza al registrar el envío, no al terminar de escribirlo.
     * @throws IllegalArgumentException si el texto es demasiado largo.
     */
    fun sendText(text: String): String {
        require(text.codePointCount(0, text.length) <= MAX_TEXT_CHARS) {
            "El texto supera $MAX_TEXT_CHARS caracteres"
        }
        val id = newId()
        if (register(id, awaitingAck = true)) launchSend { sendFrame(AppMessage.Text(id, text)) }
        return id
    }

    /**
     * Envía una foto (1..[MAX_PHOTO_BYTES] bytes) en trozos de [CHUNK_BYTES]: `PhotoStart`, los
     * trozos (con `PhotoProgress` tras cada uno) y `PhotoEnd`. No suspende: devuelve el id
     * enseguida y el envío sigue en [scope]. El plazo de acuse empieza tras `PhotoEnd`; mientras
     * tanto protege el vigilante de escritura. No modifique [jpeg] mientras se envía.
     * @throws IllegalArgumentException si la foto está vacía o es demasiado grande.
     */
    fun sendPhoto(jpeg: ByteArray): String {
        require(jpeg.isNotEmpty()) { "La foto está vacía" }
        require(jpeg.size <= MAX_PHOTO_BYTES) { "La foto supera $MAX_PHOTO_BYTES bytes" }
        val id = newId()
        if (register(id, awaitingAck = false)) launchSend { sendPhotoFrames(id, jpeg) }
        return id
    }

    private suspend fun sendPhotoFrames(id: String, jpeg: ByteArray) {
        val total = jpeg.size
        val count = chunkCountFor(total)
        photoMutex.withLock {
            if (!sendFrame(AppMessage.PhotoStart(id, total, count))) return
            for (index in 0 until count) {
                val from = index * CHUNK_BYTES
                val to = minOf(total, from + CHUNK_BYTES)
                val data = jpeg.copyOfRange(from, to)
                val ok = try {
                    sendFrame(AppMessage.PhotoChunk(id, index, data))
                } finally {
                    Primitives.wipe(data)
                }
                if (!ok) return
                synchronized(lock) {
                    if (!closed && pending.containsKey(id)) _events.tryEmit(SessionEvent.PhotoProgress(id, to, total))
                }
            }
            synchronized(lock) { pending[id]?.awaitingAck = true }
            if (!sendFrame(AppMessage.PhotoEnd(id))) return
        }
        armAckTimer(id)
    }

    /**
     * Lanza un envío ya registrado en [scope], sin esperarlo. Se arranca SIN despachar: se
     * ejecuta en el hilo de quien llama hasta su primera suspensión (como mucho, la escritura en
     * el socket), así toma su turno en los [Mutex] (justos) en el orden de las llamadas y dos
     * envíos seguidos salen en ese orden. Hasta esa suspensión no emite ningún evento. Si el envío
     * no termina normalmente (se canceló [scope]), la sesión se cierra y el id pendiente recibe
     * `Failed(CONNECTION_LOST)`.
     */
    private fun launchSend(block: suspend () -> Unit) {
        val job = scope.launch(start = CoroutineStart.UNDISPATCHED) { block() }
        job.invokeOnCompletion { cause -> if (cause != null) fail(CloseReason.LOCAL) }
    }

    /**
     * Avisa al contacto de que se deshace el emparejamiento y cierra la sesión (`LOCAL`).
     * Devuelve true solo si la trama `Unpair` se escribió en el flujo; false si la sesión ya se
     * estaba cerrando o estaba cerrada, o si la escritura falló (en ese caso la app debe
     * intentarlo en otra conexión, §6.7 bis).
     */
    suspend fun sendUnpair(): Boolean {
        if (!closeReason.compareAndSet(null, CloseReason.LOCAL)) return false
        _isOpen.value = false
        val sent = sayGoodbye(AppMessage.Unpair)
        teardown()
        return sent
    }

    /** Cierra la sesión enviando antes `Bye` si se puede. Idempotente. */
    suspend fun close(reason: CloseReason = CloseReason.LOCAL) {
        if (!closeReason.compareAndSet(null, reason)) return
        _isOpen.value = false
        sayGoodbye(AppMessage.Bye)
        teardown()
    }

    // --- Envío ---

    /**
     * Registra un envío pendiente (con su plazo de acuse ya en marcha si [awaitingAck]). Si la
     * sesión no está abierta devuelve false y emite `Failed(NOT_CONNECTED)` de forma asíncrona.
     */
    private fun register(id: String, awaitingAck: Boolean): Boolean {
        if (!scope.isActive) fail(CloseReason.LOCAL)
        val accepted = synchronized(lock) {
            if (closed || closeReason.get() != null) {
                false
            } else {
                pending[id] = Pending(awaitingAck)
                if (awaitingAck) armAckTimer(id)
                true
            }
        }
        if (!accepted) emitNotConnected(id)
        return accepted
    }

    /**
     * Emite `Failed(id, NOT_CONNECTED)` desde [scope], después de que el envío haya devuelto el id.
     * Si [scope] ya está cancelado (la corrutina no llega a ejecutarse) se emite al instante.
     */
    private fun emitNotConnected(id: String) {
        val event = SessionEvent.Failed(id, FailReason.NOT_CONNECTED)
        val job = scope.launch { _events.tryEmit(event) }
        job.invokeOnCompletion { cause -> if (cause != null) _events.tryEmit(event) }
    }

    /** Arranca el plazo de acuse, salvo que el acuse ya haya llegado o la sesión se haya cerrado. */
    private fun armAckTimer(id: String) = synchronized(lock) {
        val p = pending[id] ?: return
        if (closed || p.timer != null) return
        p.timer = scope.launch {
            delay(timings.ackTimeout)
            synchronized(lock) {
                if (!closed && pending.remove(id) != null) _events.tryEmit(SessionEvent.Failed(id, FailReason.NO_ACK))
            }
        }
    }

    /**
     * Cifra y escribe un mensaje de forma atómica. Devuelve false si no se envió (sesión cerrada o
     * cerrándose, salvo [force] para `Bye`/`Unpair`, o fallo de E/S, que cierra la sesión).
     * Vigilante de escritura: si la escritura tarda más que [SessionTimings.ackTimeout], el otro
     * lado no lee; se cierra la sesión con `IO` (cerrar el flujo desbloquea la escritura).
     */
    private suspend fun sendFrame(message: AppMessage, force: Boolean = false): Boolean {
        val plaintext = AppCodec.encode(message)
        try {
            sendMutex.withLock {
                if (closed || (!force && closeReason.get() != null)) return false
                val frame = channel.encrypt(plaintext)
                val written = withTimeoutOrNull(timings.ackTimeout) { stream.send(frame) }
                if (written == null) {
                    fail(CloseReason.IO)
                    return false
                }
            }
            return true
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            fail(CloseReason.IO)
            return false
        } catch (e: Exception) {
            // ChannelSecurityException (canal destruido o contador agotado) u otro fallo inesperado
            fail(CloseReason.SECURITY)
            return false
        } finally {
            Primitives.wipe(plaintext)
        }
    }

    /**
     * Envía `Bye`/`Unpair` y espera (como mucho [SessionTimings.linger]) a que el otro lado cierre.
     * Devuelve true si la trama se escribió.
     */
    private suspend fun sayGoodbye(message: AppMessage): Boolean {
        var sent = false
        try {
            withTimeoutOrNull(timings.linger) {
                sent = sendFrame(message, force = true)
                if (sent) {
                    // El lector sigue leyendo (y descartando) hasta el EOF del otro lado
                    synchronized(lock) { readerJob }?.join()
                }
            }
            return sent
        } catch (e: CancellationException) {
            teardown()
            throw e
        }
    }

    private suspend fun heartbeatLoop() {
        while (true) {
            delay(timings.heartbeat)
            if (!sendFrame(AppMessage.Ping)) return
        }
    }

    private suspend fun idleWatchdog() {
        while (true) {
            val remaining = timings.idleTimeout - lastReceived.elapsedNow()
            if (remaining <= Duration.ZERO) {
                fail(CloseReason.TIMEOUT)
                return
            }
            delay(remaining)
        }
    }

    private suspend fun ackLoop() {
        for (id in acks) {
            if (!sendFrame(AppMessage.Ack(id))) return
        }
    }

    // --- Recepción ---

    private suspend fun readLoop() {
        try {
            while (true) {
                val frame = stream.receive()
                lastReceived = TimeSource.Monotonic.markNow()
                val plaintext = channel.decrypt(frame)
                val message = try {
                    AppCodec.decode(plaintext)
                } finally {
                    Primitives.wipe(plaintext)
                }
                if (!handle(message)) return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // Incluye EOFException: el otro lado cerró o la conexión se cayó
            fail(CloseReason.IO)
        } catch (e: Exception) {
            // ChannelSecurityException, ProtocolException (trama, CBOR o foto incoherente) u otro
            fail(CloseReason.SECURITY)
        }
    }

    /** Procesa un mensaje recibido; devuelve false si la sesión termina. Lanza [ProtocolException]. */
    private fun handle(message: AppMessage): Boolean {
        // Cerrando: se descartan textos y fotos (no se mostrarán ni acusarán). Acuses, Bye y Unpair
        // se procesan siempre: un Unpair perdido dejaría a un lado emparejado y al otro no.
        if (closeReason.get() != null && (message is AppMessage.Text || message is AppMessage.PhotoStart ||
                message is AppMessage.PhotoChunk || message is AppMessage.PhotoEnd)
        ) {
            return true
        }
        when (message) {
            is AppMessage.Text -> {
                checkId(message.id)
                if (message.text.codePointCount(0, message.text.length) > MAX_TEXT_CHARS) {
                    throw ProtocolException("Texto recibido demasiado largo")
                }
                synchronized(lock) {
                    if (closed) return false
                    _events.tryEmit(SessionEvent.TextReceived(message.id, message.text))
                }
                queueAck(message.id)
            }
            is AppMessage.PhotoStart -> onPhotoStart(message)
            is AppMessage.PhotoChunk -> onPhotoChunk(message)
            is AppMessage.PhotoEnd -> onPhotoEnd(message)
            is AppMessage.Ack -> {
                checkId(message.id)
                synchronized(lock) {
                    // Desconocido, tardío (ya falló por NO_ACK) o de una foto aún enviándose: se ignora
                    val p = pending[message.id]
                    if (!closed && p != null && p.awaitingAck) {
                        pending.remove(message.id)
                        p.timer?.cancel()
                        _events.tryEmit(SessionEvent.Delivered(message.id))
                    }
                }
            }
            AppMessage.Ping -> Unit
            AppMessage.Bye -> {
                fail(CloseReason.PEER_BYE)
                return false
            }
            AppMessage.Unpair -> {
                // Aunque ya nos estemos cerrando (otro motivo registrado) se avisa del desemparejamiento
                closeReason.compareAndSet(null, CloseReason.PEER_UNPAIRED)
                synchronized(lock) { if (!closed) _events.tryEmit(SessionEvent.PeerUnpaired) }
                teardown()
                return false
            }
        }
        return true
    }

    private fun onPhotoStart(m: AppMessage.PhotoStart) {
        checkId(m.id)
        if (m.totalBytes !in 1..MAX_PHOTO_BYTES) throw ProtocolException("Tamaño de foto inválido")
        if (m.chunkCount != chunkCountFor(m.totalBytes)) throw ProtocolException("Número de trozos incoherente")
        synchronized(lock) {
            if (closed) return
            if (incoming != null) throw ProtocolException("Foto simultánea no permitida")
            incoming = IncomingPhoto(m.id, m.totalBytes, m.chunkCount)
        }
    }

    private fun onPhotoChunk(m: AppMessage.PhotoChunk) {
        try {
            synchronized(lock) {
                if (closed) return
                val photo = incoming?.takeIf { it.id == m.id } ?: throw ProtocolException("Trozo de foto desconocida")
                if (m.index < 0 || m.index >= photo.chunkCount) throw ProtocolException("Índice de trozo fuera de rango")
                if (m.index != photo.nextIndex) throw ProtocolException("Trozo fuera de orden")
                val offset = m.index * CHUNK_BYTES
                val expected = minOf(CHUNK_BYTES, photo.totalBytes - offset)
                if (m.data.size != expected) throw ProtocolException("Tamaño de trozo inválido")
                m.data.copyInto(photo.buffer, offset)
                photo.nextIndex++
            }
        } finally {
            Primitives.wipe(m.data)
        }
    }

    /** Solo con todos los trozos (que suman exactamente totalBytes) se muestra y se acusa la foto. */
    private fun onPhotoEnd(m: AppMessage.PhotoEnd) {
        synchronized(lock) {
            if (closed) return
            val photo = incoming?.takeIf { it.id == m.id } ?: throw ProtocolException("Fin de foto desconocida")
            if (photo.nextIndex != photo.chunkCount) throw ProtocolException("Foto incompleta")
            incoming = null
            _events.tryEmit(SessionEvent.PhotoReceived(photo.id, photo.buffer))
        }
        queueAck(m.id)
    }

    /**
     * Encola un acuse. Cola llena = el otro lado nos envía mensajes sin leer nuestros acuses:
     * se trata como violación del protocolo (`SECURITY`). Cola cerrada = la sesión se cierra.
     */
    private fun queueAck(id: String) {
        val result = acks.trySend(id)
        if (result.isFailure && !result.isClosed) throw ProtocolException("Cola de acuses llena")
    }

    // --- Cierre ---

    /** Registra [reason] si es el primer motivo y cierra la sesión. */
    private fun fail(reason: CloseReason) {
        closeReason.compareAndSet(null, reason)
        teardown()
    }

    private fun teardown() {
        closeReason.compareAndSet(null, CloseReason.LOCAL)
        val toCancel: List<Job>
        synchronized(lock) {
            if (closed) return
            closed = true
            for ((id, p) in pending) {
                p.timer?.cancel()
                _events.tryEmit(SessionEvent.Failed(id, FailReason.CONNECTION_LOST))
            }
            pending.clear()
            // Foto entrante incompleta: se descarta sin evento
            incoming?.let { Primitives.wipe(it.buffer) }
            incoming = null
            toCancel = jobs.toList()
            jobs.clear()
        }
        toCancel.forEach { it.cancel() }
        acks.close()
        channel.destroy()
        stream.close()
        _isOpen.value = false
        _events.tryEmit(SessionEvent.Closed(closeReason.get()!!))
    }

    companion object {
        const val MAX_TEXT_CHARS = 8_000
        const val MAX_PHOTO_BYTES = 3_145_728
        const val CHUNK_BYTES = 32_768

        /** Acuses pendientes de escribir como máximo; si se supera, la sesión se cierra (`SECURITY`). */
        const val ACK_QUEUE_CAPACITY = 256

        private const val ID_BYTES = 12
        private val ID_REGEX = Regex("[0-9a-f]{${ID_BYTES * 2}}")

        private fun chunkCountFor(totalBytes: Int): Int = (totalBytes + CHUNK_BYTES - 1) / CHUNK_BYTES

        private fun newId(): String = Primitives.randomBytes(ID_BYTES).joinToString("") { "%02x".format(it) }

        private fun checkId(id: String) {
            if (!ID_REGEX.matches(id)) throw ProtocolException("Identificador de mensaje inválido")
        }
    }
}
