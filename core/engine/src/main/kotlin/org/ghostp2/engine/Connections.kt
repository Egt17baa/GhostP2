package org.ghostp2.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.ghostp2.core.crypto.KeyPair25519
import org.ghostp2.core.crypto.RatchetChannel
import org.ghostp2.core.protocol.CloseReason
import org.ghostp2.core.protocol.ContactKeys
import org.ghostp2.core.protocol.FrameStream
import org.ghostp2.core.protocol.HandshakeFailedException
import org.ghostp2.core.protocol.PeerSession
import org.ghostp2.core.protocol.SessionEvent
import org.ghostp2.core.protocol.SessionHandshake
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/** Presencia de un contacto vista por el motor. */
enum class Presence { OFFLINE, CONNECTING, ONLINE, IDENTITY_CHANGED }

/** Avisos del motor para la app (notificaciones). */
sealed interface Notice {
    /** Llegó un mensaje del contacto (Task 6). */
    data class MessageArrived(val contactId: String) : Notice

    /** El contacto deshizo el emparejamiento (Task 7). */
    data class ContactUnpairedYou(val contactId: String, val name: String) : Notice

    /** La autenticación con el contacto falló al llamarle: hay que volver a emparejar. */
    data class IdentityChanged(val contactId: String) : Notice
}

/**
 * Presencia y conexiones automáticas de una ejecución del motor (se crea al arrancar y se descarta
 * con [shutdown]).
 *
 * **Bucle por contacto.** Cada contacto `ACTIVE` tiene un bucle que, mientras no haya sesión, llama a
 * su .onion (`tor.connect` + [SessionHandshake.initiate]). Tras un fallo espera `backoff` con ±20 %
 * de variación (`random`) y lo dobla hasta `backoffMaxMillis`; [onNetworkChanged] corta la espera,
 * vuelve a `backoffMinMillis` y reintenta ya. Cuando una sesión se cierra (por cualquier motivo) el
 * contacto pasa a `OFFLINE` y el bucle sigue con la espera mínima. `AUTH` al iniciar ⇒ el contacto
 * pasa a `IDENTITY_CHANGED` (se guarda), se avisa con [Notice.IdentityChanged] y el bucle termina.
 * Los bucles siguen al flujo `contacts.contacts`: un contacto nuevo (o vuelto a `ACTIVE`) arranca el
 * suyo; uno borrado lo para y cierra su sesión.
 *
 * **Entrantes.** Cada conexión del listener hace [SessionHandshake.respond] con los contactos `ACTIVE`
 * como candidatos; cualquier fallo (llamante desconocido, conexión, plazo…) cierra en silencio.
 *
 * **Desempate (PROTOCOLO §10.5).** Por contacto puede haber dos conexiones físicas autenticadas: la
 * iniciada por mí y la iniciada por él. Se conserva siempre la iniciada por el lado de `identityKey`
 * pública menor (orden de bytes sin signo), la «preferida». La decisión se toma ANTES de crear la
 * [PeerSession] y con el cerrojo del contacto tomado; la perdedora se cierra sin `Bye`
 * (`stream.close()` + `channel.destroy()`). Reglas (yo = este motor):
 * - Conexión preferida (la inicié yo y soy el menor, o la inició él y él es el menor): se envuelve
 *   siempre; si ya había otra sesión se cierra **sin Bye** cancelando su scope (el otro lado hace lo
 *   mismo con esa conexión, porque también prefiere esta).
 * - Soy el menor y entra una conexión suya (no preferida): si ya tengo sesión preferida se descarta;
 *   si tengo una suya anterior, está obsoleta (él solo llama sin sesión) y se sustituye; si mi propia
 *   llamada está en pleno handshake, se espera a su resultado (si sale bien gana la mía y esta se
 *   descarta; si falla, se envuelve esta); si mi llamada aún está abriendo el socket, se abandona.
 * - Soy el mayor y termina mi llamada (no preferida): si ya tengo sesión (la suya) se descarta; si hay
 *   handshakes entrantes en curso, se espera una vez a que terminen (puede ser su llamada preferida
 *   cruzándose con la mía); después se envuelve.
 * Ningún camino envía `Bye` al resolver duplicados. Puede quedar un cambio transitorio (sin `Bye`)
 * si el handshake entrante preferido aún no ha llegado al listener cuando termina mi llamada no
 * preferida: el otro lado descarta esa conexión, aquí se cierra con `IO` y la preferida la sustituye.
 * Limitación: si solo el lado mayor pierde la red y el menor aún cree viva su sesión preferida, el
 * menor rechaza las llamadas del mayor hasta que esa sesión caduque (inactividad, 60 s) y llame él.
 *
 * **Sesiones (regla R7).** Cada [PeerSession] usa un scope propio `SupervisorJob` hijo del motor sobre
 * `Dispatchers.Default`; su único recolector de larga vida se lanza antes de `start()` (arranque
 * `UNDISPATCHED` solo para que la suscripción exista en el acto; después corre en `Default`).
 *
 * [shutdown] para bucles y handshakes, cierra las sesiones con `Bye` y deja la presencia **vacía**.
 */
internal class Connections(
    private val tor: TorTransport,
    private val contacts: ContactRepository,
    private val config: EngineConfig,
    private val random: java.util.Random,
    engineScope: CoroutineScope,
    private val identity: KeyPair25519,
    private val presence: MutableStateFlow<Map<String, Presence>>,
    private val notices: MutableSharedFlow<Notice>,
    private val sessionEvents: MutableSharedFlow<Pair<String, SessionEvent>>,
) {
    private val engineJob: Job = engineScope.coroutineContext[Job] ?: error("El scope del motor necesita un Job")

    /** Bucles, observador de contactos y handshakes entrantes. */
    private val loopsJob = SupervisorJob(engineJob)
    private val loopsScope = CoroutineScope(engineScope.coroutineContext + loopsJob + Dispatchers.Default)

    /** Sesiones (cada una con su propio `SupervisorJob` hijo de este) y sus recolectores. */
    private val sessionsJob = SupervisorJob(engineJob)
    private val sessionsScope = CoroutineScope(engineScope.coroutineContext + sessionsJob + Dispatchers.Default)

    /** Contactos con bucle, por id; solo los crea/borra el observador de contactos. */
    private val links = MutableStateFlow<Map<String, Link>>(emptyMap())

    /** Se incrementa en [onNetworkChanged]; los bucles que esperan lo observan. */
    private val networkEpoch = MutableStateFlow(0L)

    /** Handshakes entrantes que todavía podrían ser la llamada preferida de un contacto. */
    private val incomingInFlight = MutableStateFlow(0)

    @Volatile private var closed = false

    private var observer: Job? = null

    /** Sesión envuelta: `preferred` = iniciada por el lado de identidad menor. */
    private class Active(val peer: PeerSession, val job: Job, val initiatedByMe: Boolean, val preferred: Boolean) {
        /** `start()` ya volvió: solo entonces se expone la sesión fuera de esta clase. */
        @Volatile var started = false
    }

    /** Llamada saliente en curso. `handshake` = ya se envió (o se va a enviar) msg1. */
    private class Outgoing {
        @Volatile var handshake = false
        val abandoned = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()

        /** Abandona la llamada si todavía está abriendo el socket (nada ha llegado al otro lado). */
        fun abandon() {
            abandoned.complete(Unit)
        }
    }

    /** Estado por contacto; `session`, `outgoing` y `removed` solo se modifican con `mutex` tomado. */
    private class Link(val id: String, @Volatile var contact: Contact) {
        val mutex = Mutex()
        val session = MutableStateFlow<Active?>(null)
        var outgoing: Outgoing? = null
        var removed = false
        var loop: Job? = null
    }

    private sealed interface Outcome {
        data object Connected : Outcome
        data object Busy : Outcome
        data object IdentityChanged : Outcome
        data object Failed : Outcome
    }

    /** Empieza a seguir la lista de contactos (y con ella los bucles). */
    fun start() {
        observer = loopsScope.launch { contacts.contacts.collect { reconcile(it) } }
    }

    fun onNetworkChanged() {
        networkEpoch.update { it + 1 }
    }

    /** Sesión del contacto, solo si ya se arrancó (`start()` terminado). */
    fun sessionFor(contactId: String): PeerSession? = startedSession(contactId)?.peer

    fun sessionInitiatedByMe(contactId: String): Boolean? = startedSession(contactId)?.initiatedByMe

    private fun startedSession(contactId: String): Active? =
        links.value[contactId]?.session?.value?.takeIf { it.started }

    /** Conexión entrante del listener (síncrono: lanza el handshake y vuelve enseguida). */
    @OptIn(DelicateCoroutinesApi::class) // ATOMIC: el finally que cierra el socket debe ejecutarse siempre
    fun accept(socket: Socket) {
        if (closed) {
            runCatching { socket.close() }
            return
        }
        incomingInFlight.update { it + 1 }
        // ATOMIC: el cuerpo (y su finally, que cierra el socket) corre aunque se cancele antes de empezar.
        loopsScope.launch(start = CoroutineStart.ATOMIC) {
            var counted = true
            val uncount = { if (counted) { counted = false; incomingInFlight.update { it - 1 } } }
            try {
                handleIncoming(socket, uncount)
            } catch (e: CancellationException) {
                // Si ya hay sesión, el socket es suyo y install no lanza; si no, nadie más lo cerrará.
                runCatching { socket.close() }
                throw e
            } catch (e: Exception) {
                runCatching { socket.close() }
            } finally {
                uncount()
            }
        }
    }

    private suspend fun handleIncoming(socket: Socket, uncount: () -> Unit) {
        val active = contacts.all().filter { it.status == ContactStatus.ACTIVE }
        val candidates = active.map { ContactKeys(it.peerIdentity, it.pairSecret) }
        val byKeys = candidates.zip(active).toMap(java.util.IdentityHashMap())
        val stream = try {
            withContext(Dispatchers.IO) { FrameStream(socket.getInputStream(), socket.getOutputStream(), socket) }
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
        // Cualquier fallo del handshake (UNKNOWN_CALLER, CONNECTION, TIMEOUT…) cierra el flujo y sale.
        val (keys, channel) = SessionHandshake.respond(stream, identity, candidates)
        val contact = byKeys.getValue(keys)
        // El observador puede ir un instante por detrás de un emparejamiento recién guardado.
        val link = links.value[contact.id] ?: withTimeoutOrNull(LINK_WAIT_MILLIS) {
            links.first { it.containsKey(contact.id) }[contact.id]
        }
        if (link == null) {
            discard(stream, channel)
            return
        }
        // Una entrante no preferida (soy el menor) nunca es la llamada preferida que espera otro.
        if (iAmLower(link.contact)) uncount()
        install(link, stream, channel, initiatedByMe = false)
    }

    // --- Contactos y bucles ---

    private suspend fun reconcile(list: List<Contact>) {
        val byId = list.associateBy { it.id }
        val current = links.value
        for ((id, link) in current) {
            if (id !in byId) removeLink(link)
        }
        val next = current.filterKeys { it in byId }.toMutableMap()
        for (contact in list) {
            val link = next.getOrPut(contact.id) { Link(contact.id, contact) }
            link.mutex.withLock {
                link.contact = contact
                // `exitIfInactive` pone `loop = null` con este mismo cerrojo; `!isActive` recupera además
                // un bucle que terminó por una excepción inesperada.
                if (contact.status == ContactStatus.ACTIVE && link.loop?.isActive != true && !closed) {
                    link.loop = loopsScope.launch { runLoop(link) }
                }
                refresh(link)
            }
        }
        links.value = next
    }

    private suspend fun removeLink(link: Link) {
        val active = link.mutex.withLock {
            link.removed = true
            link.loop?.cancel()
            link.outgoing?.abandon()
            presence.update { it - link.id }
            link.session.value
        }
        // Task 7 envía antes Unpair; aquí basta con cerrar (con Bye).
        active?.let { a -> sessionsScope.launch { a.peer.close() } }
    }

    private suspend fun runLoop(link: Link) {
        val backoff = Backoff(config.backoffMinMillis, config.backoffMaxMillis, random)
        var wait = 0L
        var epoch = networkEpoch.value
        while (currentCoroutineContext().isActive) {
            if (link.session.value != null) {
                link.session.first { it == null }
                // Sesión cerrada (cualquier motivo). Gancho Task 7: PEER_UNPAIRED tendrá su propio camino.
                backoff.reset()
                wait = backoff.jitter(config.backoffMinMillis)
            }
            if (wait > 0) {
                val woke = withTimeoutOrNull(wait) { networkEpoch.first { it != epoch } } != null
                if (woke) backoff.reset()
            }
            epoch = networkEpoch.value
            if (exitIfInactive(link)) return
            when (attempt(link)) {
                Outcome.Connected -> {
                    backoff.reset()
                    wait = 0
                }
                Outcome.Busy -> wait = if (link.session.value != null) 0 else backoff.jitter(backoff.current)
                Outcome.IdentityChanged -> {
                    markIdentityChanged(link)
                    // Si se marcó, sale (salvo que se haya vuelto a emparejar justo ahora); si no, sigue.
                    if (exitIfInactive(link)) return
                    wait = backoff.jitter(backoff.current)
                }
                Outcome.Failed -> wait = backoff.next()
            }
        }
    }

    /**
     * Decide con `link.mutex` tomado si el bucle termina (contacto no `ACTIVE`) y, en ese caso, deja
     * `link.loop = null` en el mismo paso: así [reconcile] (que mira `loop` con el mismo cerrojo) nunca
     * ve un bucle «vivo» que ya ha decidido salir, y relanza otro si el contacto vuelve a `ACTIVE`.
     */
    private suspend fun exitIfInactive(link: Link): Boolean = link.mutex.withLock {
        if (link.contact.status == ContactStatus.ACTIVE) return@withLock false
        if (link.loop === currentCoroutineContext()[Job]) link.loop = null
        true
    }

    /** Un intento de llamada al contacto. */
    @OptIn(ExperimentalCoroutinesApi::class) // Deferred.getCompleted
    private suspend fun attempt(link: Link): Outcome {
        val contact = link.contact
        val ticket = link.mutex.withLock {
            if (closed || link.removed || link.session.value != null || link.outgoing != null) return Outcome.Busy
            Outgoing().also {
                link.outgoing = it
                refresh(link)
            }
        }
        try {
            // El connect no se cancela al abandonar: cancelarlo podría perder un socket ya conectado (el
            // otro lado se quedaría esperando msg1). Se deja de esperar y se cierra el socket al llegar.
            val connect = loopsScope.async {
                tor.connect(contact.peerOnion, config.virtualPort, config.connectTimeoutMillis)
            }
            var taken = false
            val socket = try {
                val connected = try {
                    select<Boolean> {
                        connect.onAwait { true }
                        ticket.abandoned.onAwait { false }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return Outcome.Failed
                }
                if (!connected) return Outcome.Busy
                connect.getCompleted().also { taken = true }
            } finally {
                // Abandonada o cancelada antes de tomar el socket: cerrarlo cuando llegue.
                if (!taken) connect.invokeOnCompletion { cause -> if (cause == null) runCatching { connect.getCompleted().close() } }
            }
            val proceed = link.mutex.withLock {
                val ok = !closed && !link.removed && !ticket.abandoned.isCompleted && link.session.value == null
                if (ok) ticket.handshake = true
                ok
            }
            if (!proceed) {
                runCatching { socket.close() }
                return Outcome.Busy
            }
            val stream = try {
                withContext(Dispatchers.IO) { FrameStream(socket.getInputStream(), socket.getOutputStream(), socket) }
            } catch (e: CancellationException) {
                runCatching { socket.close() }
                throw e
            } catch (e: Exception) {
                runCatching { socket.close() }
                return Outcome.Failed
            }
            val channel = try {
                SessionHandshake.initiate(stream, identity, ContactKeys(contact.peerIdentity, contact.pairSecret))
            } catch (e: HandshakeFailedException) {
                return if (e.reason == HandshakeFailedException.Reason.AUTH) Outcome.IdentityChanged else Outcome.Failed
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return Outcome.Failed // ProtocolException u otro: el handshake ya cerró el flujo
            }
            return if (install(link, stream, channel, initiatedByMe = true)) Outcome.Connected else Outcome.Busy
        } finally {
            withContext(NonCancellable) {
                link.mutex.withLock {
                    if (link.outgoing === ticket) link.outgoing = null
                    refresh(link)
                }
                ticket.done.complete(Unit)
            }
        }
    }

    /**
     * `AUTH` al llamar: el contacto pasa a `IDENTITY_CHANGED` (guardado) y se avisa. Si mientras tanto
     * se volvió a emparejar (otra identidad o pairSecret guardados), no se marca: el bucle nuevo sigue.
     */
    private suspend fun markIdentityChanged(link: Link): Boolean {
        val used = link.contact
        val marked = withContext(NonCancellable) {
            val stored = contacts.all().firstOrNull { it.id == link.id } ?: return@withContext false
            if (stored.status != ContactStatus.ACTIVE ||
                !stored.peerIdentity.contentEquals(used.peerIdentity) ||
                !stored.pairSecret.contentEquals(used.pairSecret)
            ) {
                return@withContext false
            }
            val changed = stored.copy(status = ContactStatus.IDENTITY_CHANGED)
            // Primero se guarda; la presencia solo muestra IDENTITY_CHANGED una vez persistido.
            contacts.upsert(changed)
            link.mutex.withLock {
                link.contact = changed
                refresh(link)
            }
            true
        }
        if (marked) notices.emit(Notice.IdentityChanged(link.id))
        return marked
    }

    // --- Desempate e instalación ---

    private sealed interface Decision {
        data object Discard : Decision
        class Wrap(val replace: Active?) : Decision
        class WaitOutgoing(val ticket: Outgoing) : Decision
        data object WaitIncoming : Decision
    }

    /**
     * Decide (con el cerrojo del contacto) si una conexión autenticada se convierte en la sesión del
     * contacto; si no, la cierra sin `Bye`. Devuelve true si se instaló. Ver la KDoc de la clase.
     */
    private suspend fun install(link: Link, stream: FrameStream, channel: RatchetChannel, initiatedByMe: Boolean): Boolean {
        var owned = true // el flujo y el canal siguen siendo míos (no hay PeerSession)
        var waitedIncoming = false
        try {
            while (true) {
                var created: Active? = null
                val decision = link.mutex.withLock {
                    val lower = iAmLower(link.contact)
                    val preferred = initiatedByMe == lower
                    val existing = link.session.value
                    val outgoing = link.outgoing
                    val d = when {
                        closed || link.removed || link.contact.status != ContactStatus.ACTIVE -> Decision.Discard
                        preferred -> Decision.Wrap(existing)
                        lower -> when {
                            existing != null -> if (existing.preferred) Decision.Discard else Decision.Wrap(existing)
                            outgoing != null && outgoing.handshake -> Decision.WaitOutgoing(outgoing)
                            else -> Decision.Wrap(null)
                        }
                        else -> when {
                            existing != null -> Decision.Discard
                            !waitedIncoming && incomingInFlight.value > 0 -> Decision.WaitIncoming
                            else -> Decision.Wrap(null)
                        }
                    }
                    if (d is Decision.Wrap) {
                        created = wrap(link, stream, channel, initiatedByMe, preferred, d.replace)
                        owned = false
                    }
                    d
                }
                when (decision) {
                    is Decision.Wrap -> {
                        // start() espera al recolector (ya suscrito) y lanza el lector; siempre se completa.
                        val active = created!!
                        try {
                            withContext(NonCancellable) { active.peer.start() }
                        } catch (e: Throwable) {
                            // No debería pasar; si pasa, el hueco del contacto no se queda ocupado.
                            active.job.cancel()
                            discard(stream, channel)
                            withContext(NonCancellable) {
                                link.mutex.withLock {
                                    if (link.session.value === active) {
                                        link.session.value = null
                                        refresh(link)
                                    }
                                }
                            }
                            throw e
                        }
                        active.started = true
                        return true
                    }
                    Decision.Discard -> {
                        discard(stream, channel)
                        owned = false
                        return false
                    }
                    is Decision.WaitOutgoing -> decision.ticket.done.await()
                    Decision.WaitIncoming -> {
                        waitedIncoming = true
                        withTimeoutOrNull(INCOMING_WAIT_MILLIS) {
                            combine(incomingInFlight, link.session) { n, s -> n == 0 || s != null }
                                .first { it }
                        }
                    }
                }
            }
        } finally {
            if (owned) discard(stream, channel)
        }
    }

    /** Crea la sesión con su scope y su recolector y la pone como la del contacto. Con `link.mutex` tomado. */
    private fun wrap(
        link: Link,
        stream: FrameStream,
        channel: RatchetChannel,
        initiatedByMe: Boolean,
        preferred: Boolean,
        replace: Active?,
    ): Active {
        val job = SupervisorJob(sessionsJob)
        val peer = PeerSession(stream, channel, CoroutineScope(sessionsScope.coroutineContext + job + Dispatchers.Default))
        val active = Active(peer, job, initiatedByMe, preferred)
        // Único recolector de larga vida, suscrito ya (UNDISPATCHED) antes de start().
        sessionsScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                peer.events.transformWhile { emit(it); it !is SessionEvent.Closed }.collect { event ->
                    sessionEvents.emit(link.id to event)
                    if (event is SessionEvent.Closed) onSessionClosed(link, active, event.reason)
                }
            } finally {
                job.cancel()
            }
        }
        // Duplicado perdedor ya envuelto: cancelar su scope lo cierra sin Bye (Closed(LOCAL) aquí).
        replace?.job?.cancel()
        // Una llamada propia que aún abre el socket ya no hace falta.
        link.outgoing?.takeIf { !it.handshake }?.abandon()
        link.session.value = active
        refresh(link)
        return active
    }

    /**
     * Sesión cerrada: el contacto queda sin sesión (`OFFLINE`) y su bucle sigue con la espera mínima.
     * Gancho Task 7: `reason == PEER_UNPAIRED` deberá borrar el contacto y avisar; por ahora se trata
     * como cualquier otro motivo.
     */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun onSessionClosed(link: Link, active: Active, reason: CloseReason) {
        link.mutex.withLock {
            if (link.session.value === active) {
                link.session.value = null
                refresh(link)
            }
        }
    }

    /** Cierra una conexión perdedora sin `Bye` y destruye su canal (PROTOCOLO §7, §10.5). */
    private fun discard(stream: FrameStream, channel: RatchetChannel) {
        runCatching { channel.destroy() }
        stream.close()
    }

    /** Recalcula la presencia del contacto. Con `link.mutex` tomado. */
    private fun refresh(link: Link) {
        if (link.removed || closed) return
        val p = when {
            link.session.value != null -> Presence.ONLINE
            link.contact.status == ContactStatus.IDENTITY_CHANGED -> Presence.IDENTITY_CHANGED
            link.outgoing != null -> Presence.CONNECTING
            else -> Presence.OFFLINE
        }
        presence.update { it + (link.id to p) }
    }

    private fun iAmLower(contact: Contact): Boolean = compareUnsigned(identity.publicKey, contact.peerIdentity) < 0

    /**
     * Detiene todo: observador, bucles y handshakes; cierra las sesiones con `Bye` (en paralelo, cada
     * una espera como mucho 2 s) y deja la presencia vacía.
     */
    suspend fun shutdown() {
        closed = true
        loopsJob.cancel()
        loopsJob.join()
        val sessions = links.value.values.mapNotNull { link ->
            link.mutex.withLock {
                link.removed = true
                link.session.value
            }
        }
        sessions.map { a -> sessionsScope.launch { a.peer.close() } }.joinAll()
        sessionsJob.cancelAndJoin()
        links.value = emptyMap()
        presence.value = emptyMap()
    }

    private companion object {

        /** Espera máxima a que el observador cree el contacto recién emparejado. */
        const val LINK_WAIT_MILLIS = 2_000L

        /** Espera máxima a los handshakes entrantes antes de envolver mi llamada no preferida. */
        const val INCOMING_WAIT_MILLIS = 30_000L
    }
}

/** Compara claves como enteros sin signo, byte a byte (la más corta va antes si es prefijo). */
internal fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    for (i in 0 until minOf(a.size, b.size)) {
        val c = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
        if (c != 0) return c
    }
    return a.size - b.size
}

/**
 * Espera creciente de reintentos: [next] devuelve la espera actual con ±20 % de variación y la dobla
 * (hasta `maxMillis`); [reset] vuelve a `minMillis`.
 */
internal class Backoff(private val minMillis: Long, private val maxMillis: Long, private val random: java.util.Random) {
    /** Espera nominal (sin variación) del próximo [next]. */
    var current: Long = minMillis
        private set

    fun reset() {
        current = minMillis
    }

    fun next(): Long = jitter(current).also { current = (current * 2).coerceAtMost(maxMillis) }

    /** `millis` con ±20 % de variación aleatoria (al menos 1 ms). */
    fun jitter(millis: Long): Long =
        (millis * (1.0 + JITTER * (2 * random.nextDouble() - 1))).toLong().coerceAtLeast(1)

    private companion object {
        const val JITTER = 0.2
    }
}
