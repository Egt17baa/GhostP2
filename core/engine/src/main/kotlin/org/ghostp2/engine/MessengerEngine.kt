package org.ghostp2.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.ghostp2.core.crypto.Invitation
import org.ghostp2.core.crypto.KeyPair25519
import org.ghostp2.core.crypto.Primitives
import org.ghostp2.core.protocol.FrameStream
import org.ghostp2.core.protocol.HandshakeFailedException
import org.ghostp2.core.protocol.Pairing
import org.ghostp2.core.protocol.PairingOffer
import org.ghostp2.core.protocol.PairingResult
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Estado visible del motor. */
sealed interface EngineStatus {
    data object Stopped : EngineStatus
    data class StartingTor(val percent: Int) : EngineStatus
    data object Online : EngineStatus
    data class Error(val message: String) : EngineStatus
}

/**
 * Motor de mensajería P2P sobre Tor. En esta fase: arranque/parada, identidad local, escucha local,
 * publicación de la .onion permanente (ver [OnionPublisher]) e invitaciones/emparejamiento
 * ([createInvitation], [acceptInvitation]).
 *
 * Todas las corrutinas del motor cuelgan de un `SupervisorJob` hijo de `scope` (regla R7: el scope
 * inyectado debe ser `SupervisorJob() + Dispatchers.Default`); [stop] las cancela y espera, de modo que
 * no queda ninguna viva tras detener el motor.
 *
 * Transiciones: `Stopped → StartingTor(p) → Online`, o `→ Error(mensaje)` si Tor falla (en ese caso se
 * liberan los recursos y se puede volver a llamar a [start]). Tor falla si informa `Error`, si vuelve a
 * `Off` durante el arranque o si no está listo en `EngineConfig.torBootstrapTimeoutMillis`. [start] y
 * [stop] están serializados y son idempotentes; [stop] cancela un [start] en curso (que termina en
 * `Stopped` sin lanzar).
 */
class MessengerEngine(
    private val tor: TorTransport,
    private val identities: IdentityRepository,
    private val contacts: ContactRepository,
    private val scope: CoroutineScope,
    private val config: EngineConfig = EngineConfig(),
    @Suppress("unused") private val random: java.util.Random = java.security.SecureRandom(),
) {
    private val mutableStatus = MutableStateFlow<EngineStatus>(EngineStatus.Stopped)
    val status: StateFlow<EngineStatus> = mutableStatus.asStateFlow()

    private val mutableMyOnion = MutableStateFlow<String?>(null)

    /** .onion permanente publicada ahora mismo (sin ".onion"); `null` si no hay contactos o el motor está parado. */
    val myOnion: StateFlow<String?> = mutableMyOnion.asStateFlow()

    private val lifecycle = Mutex()

    // Estado de una ejecución; solo se modifica con `lifecycle` tomado.
    private var running = false
    private var engineJob: CompletableJob? = null

    // Escritos por el arranque (otro hilo) y leídos por stop(): volátiles.
    @Volatile private var bootJob: Job? = null
    private val stopRequests = AtomicLong()
    @Volatile private var listener: Listener? = null
    @Volatile private var publisher: OnionPublisher? = null

    @Volatile
    private var identity: Identity? = null

    /** Scope de la ejecución en curso (hijo de `engineJob`); `null` con el motor parado. */
    @Volatile private var workScope: CoroutineScope? = null

    /** Invitación activa (lado invitador); crear otra cancela la anterior. */
    private val activeInvitation = AtomicReference<Job?>(null)

    /**
     * Claves públicas de autorización de emparejamientos en curso que todavía no son contacto (regla
     * R10), por hex. Se publican junto a las de los contactos; solo se modifica con `publication`.
     */
    private val pendingClientAuthKeys = ConcurrentHashMap<String, ByteArray>()

    /** Serializa `publisher.sync` con la actualización de [identity] y de [myOnion]. */
    private val publication = Mutex()

    /** Puerto local del listener mientras el motor está en marcha (para pruebas). */
    internal val localPort: Int? get() = listener?.port

    suspend fun start() {
        // Un stop() posterior a este punto debe cancelar este arranque aunque llegue antes que bootJob.
        val stopsAtEntry = stopRequests.get()
        lifecycle.withLock {
            if (running) return
            val job = SupervisorJob(scope.coroutineContext[Job])
            val engineScope = CoroutineScope(scope.coroutineContext + job)
            engineJob = job
            workScope = engineScope
            running = true
            mutableStatus.value = EngineStatus.StartingTor(0)
            // El arranque corre en un hijo del motor para que stop() pueda cancelarlo sin el Mutex.
            val boot = engineScope.async { bootstrap(engineScope) }
            bootJob = boot
            if (stopRequests.get() != stopsAtEntry) boot.cancel()
            try {
                boot.await()
                mutableStatus.value = EngineStatus.Online
            } catch (e: CancellationException) {
                // Arranque cancelado por stop() (o el llamante fue cancelado).
                withContext(NonCancellable) { releaseResources() }
                mutableStatus.value = EngineStatus.Stopped
                if (!currentCoroutineContext().isActive) throw e
            } catch (e: Exception) {
                withContext(NonCancellable) { releaseResources() }
                mutableStatus.value = EngineStatus.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /**
     * Detiene el motor; idempotente. Si hay un [start] en curso (p. ej. Tor atascado arrancando), lo
     * cancela **antes** de tomar el Mutex, así que nunca se queda esperando a Tor.
     */
    suspend fun stop() {
        stopRequests.incrementAndGet()
        bootJob?.cancel()
        lifecycle.withLock {
            if (running) releaseResources()
            mutableStatus.value = EngineStatus.Stopped
        }
    }

    /** Arranque completo: identidad, Tor, listener y publicación. Lanza [TorStartException] si Tor falla. */
    private suspend fun bootstrap(engineScope: CoroutineScope) {
        val id = identities.load() ?: createIdentity()
        identity = id
        startTor(engineScope)
        val l = Listener(engineScope, ::onIncoming)
        listener = l
        val p = OnionPublisher(tor, identities, config.virtualPort, l.port)
        publisher = p
        identity = p.sync(id, contacts.all())
        mutableMyOnion.value = p.publishedOnion
    }

    /** Crea la identidad local (par X25519 nuevo, todavía sin .onion) y la guarda. */
    private suspend fun createIdentity(): Identity {
        val pair = Primitives.generateX25519()
        return Identity(pair.privateKey, pair.publicKey, onionPrivateKey = null, onion = null)
            .also { identities.save(it) }
    }

    /**
     * Arranca Tor y espera `Ready`, reflejando el progreso en [status]. Lanza [TorStartException] si Tor
     * informa `Error`, si vuelve a `Off` durante el arranque o si no termina en `torBootstrapTimeoutMillis`.
     */
    private suspend fun startTor(engineScope: CoroutineScope) {
        val progress = engineScope.launch {
            tor.state.collect { s ->
                if (s is TorState.Starting) mutableStatus.value = EngineStatus.StartingTor(s.percent)
            }
        }
        try {
            tor.start()
            val final = withTimeoutOrNull(config.torBootstrapTimeoutMillis) {
                tor.state.first { it is TorState.Ready || it is TorState.Error || it is TorState.Off }
            }
            when (final) {
                null -> throw TorStartException("Tor no terminó de arrancar")
                is TorState.Error -> throw TorStartException(final.message)
                TorState.Off -> throw TorStartException("Tor se detuvo durante el arranque")
                else -> Unit
            }
        } finally {
            // Esperar al colector para que no pise un estado posterior (Online/Error).
            withContext(NonCancellable) { progress.cancelAndJoin() }
        }
    }

    /**
     * Crea una invitación (lado invitador): `ServerSocket` propio en `127.0.0.1:0` detrás de un
     * servicio oculto temporal **sin** autorización de cliente, con un secreto nuevo de 16 bytes.
     * El estado pasa por `Preparing → Waiting(texto)` y termina en `Paired` (un único emparejamiento
     * con éxito), `Expired` (a los `invitationTtlMillis`), `Cancelled` (`cancel()`, nueva invitación o
     * parada del motor) o `Failed`. En los tres primeros se retira el servicio temporal y se cierra su
     * socket. Los intentos fallidos no consumen la invitación. Crear otra cancela la anterior.
     * Con el motor no `Online` devuelve un handle ya en `Failed("GhostP2 no está activo")`.
     */
    fun createInvitation(): InvitationHandle {
        val state = MutableStateFlow<InvitationState>(InvitationState.Preparing)
        val engineScope = workScope
        if (status.value != EngineStatus.Online || engineScope == null) {
            state.value = InvitationState.Failed(InvitationMessages.NOT_ACTIVE)
            return InvitationHandle(state.asStateFlow()) {}
        }
        val job = engineScope.launch(start = CoroutineStart.LAZY) { runInvitation(state) }
        // Si se cancela antes de empezar, el cuerpo no llega a ejecutarse: el estado final se fija aquí.
        job.invokeOnCompletion { state.update { if (it.isFinal) it else InvitationState.Cancelled } }
        activeInvitation.getAndSet(job)?.cancel()
        job.start()
        return InvitationHandle(state.asStateFlow()) {
            job.cancel()
            activeInvitation.compareAndSet(job, null)
        }
    }

    private suspend fun runInvitation(state: MutableStateFlow<InvitationState>) {
        val secret = Primitives.randomBytes(Invitation.SECRET_SIZE)
        // Par de autorización de cliente para quien empareje: la privada va en mi oferta.
        val clientAuth = Primitives.generateX25519()
        var server: ServerSocket? = null
        var temporaryOnion: String? = null
        var paired: InvitationState.Paired? = null
        var outcome: InvitationState = InvitationState.Cancelled
        try {
            val socket = withContext(Dispatchers.IO) { ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1")) }
            server = socket
            val service = tor.publishService(null, emptyList(), config.virtualPort, socket.localPort)
            temporaryOnion = service.onion
            Primitives.wipe(service.privateKey) // la dirección temporal no se vuelve a publicar
            val myOnion = reservePublication(clientAuth.publicKey)
            val offer = PairingOffer(myOnion, clientAuth.privateKey)
            val pairing = pairingIdentity()
            state.value = InvitationState.Waiting(
                Invitation(service.onion, secret).encode(),
                System.currentTimeMillis() + config.invitationTtlMillis,
            )
            val result = serveInvitation(
                socket,
                config.invitationTtlMillis,
                attempt = { stream -> Pairing.asInviter(stream, pairing, secret, offer) },
                complete = { r ->
                    val id = finishPairing(r, clientAuth.publicKey)
                    InvitationState.Paired(id, r.safetyNumber).also { paired = it }
                },
            )
            outcome = result ?: InvitationState.Expired
        } catch (e: CancellationException) {
            outcome = InvitationState.Cancelled
            throw e
        } catch (e: Exception) {
            // Antes de mostrar el texto: no se pudo crear; después: falló al guardar el contacto.
            val message = if (state.value is InvitationState.Waiting) InvitationMessages.GENERIC else InvitationMessages.CANNOT_CREATE
            outcome = InvitationState.Failed(message)
        } finally {
            withContext(NonCancellable) {
                // Si el contacto llegó a guardarse (aunque luego nos cancelaran), el resultado es Paired.
                paired?.let { outcome = it }
                runCatching { server?.close() }
                temporaryOnion?.let { runCatching { tor.unpublishService(it) } }
                if (paired == null) runCatching { releasePublication(clientAuth.publicKey) }
                Primitives.wipe(secret, clientAuth.privateKey)
                state.update { if (it.isFinal) it else outcome }
            }
        }
    }

    /**
     * Acepta una invitación (lado invitado): conecta a su .onion temporal y empareja como iniciador.
     * Si aún no tengo .onion permanente publicada, se publica antes de enviar la oferta, ya con la clave
     * del nuevo contacto (regla R10); si falla, se retira de nuevo. Nunca lanza (salvo cancelación).
     */
    suspend fun acceptInvitation(text: String): AcceptResult {
        val engineScope = workScope
        if (status.value != EngineStatus.Online || engineScope == null) {
            return AcceptResult.Failed(InvitationMessages.NOT_ACTIVE)
        }
        val invitation = try {
            Invitation.parse(text)
        } catch (e: IllegalArgumentException) {
            return AcceptResult.Failed(e.message ?: "Invitación no válida")
        }
        // Regla R7: el trabajo corre en el scope del motor; parar el motor lo cancela.
        val work = engineScope.async { runInvitee(invitation) }
        return try {
            work.await()
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                work.cancel()
                throw e
            }
            // El motor se detuvo durante el emparejamiento (runInvitee ya borró los secretos).
            AcceptResult.Failed(InvitationMessages.NOT_ACTIVE)
        }
    }

    private suspend fun runInvitee(invitation: Invitation): AcceptResult {
        val clientAuth = Primitives.generateX25519()
        var paired = false
        try {
            val myOnion = reservePublication(clientAuth.publicKey)
            val socket = tor.connect(invitation.onion, config.virtualPort, config.connectTimeoutMillis)
            val result = try {
                val stream = withContext(Dispatchers.IO) {
                    FrameStream(socket.getInputStream(), socket.getOutputStream(), socket)
                }
                Pairing.asInvitee(stream, pairingIdentity(), invitation.secret, PairingOffer(myOnion, clientAuth.privateKey))
            } finally {
                runCatching { socket.close() }
            }
            val id = withContext(NonCancellable) { finishPairing(result, clientAuth.publicKey) }
            paired = true
            return AcceptResult.Paired(id, result.safetyNumber)
        } catch (e: CancellationException) {
            throw e
        } catch (e: HandshakeFailedException) {
            return AcceptResult.Failed(
                when (e.reason) {
                    HandshakeFailedException.Reason.CONNECTION, HandshakeFailedException.Reason.TIMEOUT ->
                        InvitationMessages.CANNOT_CONNECT
                    HandshakeFailedException.Reason.AUTH -> InvitationMessages.MISMATCH
                    else -> InvitationMessages.GENERIC
                },
            )
        } catch (e: IOException) {
            return AcceptResult.Failed(InvitationMessages.CANNOT_CONNECT)
        } catch (e: Exception) {
            return AcceptResult.Failed(InvitationMessages.GENERIC)
        } finally {
            withContext(NonCancellable) {
                if (!paired) runCatching { releasePublication(clientAuth.publicKey) }
                Primitives.wipe(clientAuth.privateKey, invitation.secret)
            }
        }
    }

    /** Identidad Noise del emparejamiento (mismas matrices que la identidad: no se borran). */
    private fun pairingIdentity(): KeyPair25519 {
        val id = identity ?: throw IllegalStateException(InvitationMessages.NOT_ACTIVE)
        return KeyPair25519(id.identityPrivate, id.identityPublic)
    }

    /**
     * Regla R10: devuelve mi .onion permanente para la oferta. Si no está publicada (cero contactos),
     * la publica ya con `peerClientAuthPublic` como clave pendiente; nunca sin autorización de cliente.
     */
    private suspend fun reservePublication(peerClientAuthPublic: ByteArray): String = publication.withLock {
        val p = publisher ?: throw IllegalStateException(InvitationMessages.NOT_ACTIVE)
        if (p.publishedOnion == null) {
            pendingClientAuthKeys[peerClientAuthPublic.toHex()] = peerClientAuthPublic.copyOf()
            syncLocked(p)
        }
        p.publishedOnion ?: throw IllegalStateException("No se pudo publicar la .onion permanente")
    }

    /** Retira la clave pendiente (emparejamiento fallido) y re-sincroniza; sin contactos ⇒ se retira la .onion. */
    private suspend fun releasePublication(peerClientAuthPublic: ByteArray) {
        publication.withLock {
            if (pendingClientAuthKeys.remove(peerClientAuthPublic.toHex()) != null) publisher?.let { syncLocked(it) }
        }
    }

    /** Publica según contactos guardados + claves pendientes. Llamar con `publication` tomado. */
    private suspend fun syncLocked(p: OnionPublisher) {
        val id = identity ?: throw IllegalStateException(InvitationMessages.NOT_ACTIVE)
        identity = p.sync(id, contacts.all(), pendingClientAuthKeys.values.toList())
        mutableMyOnion.value = p.publishedOnion
    }

    /**
     * Guarda el contacto emparejado (reemplaza al de la misma identidad), registra la autorización
     * para su .onion y re-publica la mía con su clave. Devuelve el id del contacto. Los arrays de
     * `result` pasan a ser datos guardados: no se borran.
     */
    private suspend fun finishPairing(result: PairingResult, myClientAuthPublic: ByteArray): String {
        val id = contactIdFor(result.peerIdentity)
        val previous = contacts.all().firstOrNull { it.id == id }
        val contact = Contact(
            id = id,
            name = "Contacto nuevo",
            peerIdentity = result.peerIdentity,
            peerOnion = result.peerOnion,
            clientAuthPrivateForPeerOnion = result.clientAuthPrivateForPeerOnion,
            myClientAuthPublic = myClientAuthPublic.copyOf(),
            pairSecret = result.pairSecret,
            verified = false,
            status = ContactStatus.ACTIVE,
        )
        contacts.upsert(contact)
        if (previous != null && previous.peerOnion != result.peerOnion) {
            runCatching { tor.removeClientAuth(previous.peerOnion) }
        }
        tor.addClientAuth(result.peerOnion, result.clientAuthPrivateForPeerOnion)
        publication.withLock {
            pendingClientAuthKeys.remove(myClientAuthPublic.toHex())
            publisher?.let { syncLocked(it) }
        }
        return id
    }

    /**
     * Conexión entrante en el listener. En esta fase todavía no hay protocolo de sesión: se cierra.
     * Tareas posteriores harán aquí el handshake de invitación/contacto.
     */
    private fun onIncoming(socket: Socket) {
        runCatching { socket.close() }
    }

    /** Cierra sesiones, invitaciones y listener, retira servicios, para Tor y borra el estado en memoria. */
    private suspend fun releaseResources() {
        workScope = null
        // Primero esperar al arranque (ya cancelado o terminado) para que no cree nada después.
        bootJob?.cancelAndJoin()
        bootJob = null
        listener?.closeAndJoin()
        listener = null
        // Antes de retirar la .onion: las invitaciones y emparejamientos en curso pueden re-sincronizarla.
        engineJob?.cancelAndJoin()
        engineJob = null
        activeInvitation.set(null)
        runCatching { publisher?.unpublish() }
        publisher = null
        pendingClientAuthKeys.clear()
        runCatching { tor.stop() }
        // Historial en memoria: todavía no existe (tareas posteriores lo borran aquí).
        mutableMyOnion.value = null
        identity = null
        running = false
    }
}

private const val BACKLOG = 50

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Fallo al arrancar Tor; el mensaje es el que se muestra en [EngineStatus.Error]. */
internal class TorStartException(message: String) : Exception(message)
