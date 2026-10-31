package org.ghostp2.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ghostp2.core.crypto.Primitives
import java.net.Socket

/** Estado visible del motor. */
sealed interface EngineStatus {
    data object Stopped : EngineStatus
    data class StartingTor(val percent: Int) : EngineStatus
    data object Online : EngineStatus
    data class Error(val message: String) : EngineStatus
}

/**
 * Motor de mensajería P2P sobre Tor. En esta fase: arranque/parada, identidad local, escucha local y
 * publicación de la .onion permanente (ver [OnionPublisher]).
 *
 * Todas las corrutinas del motor cuelgan de un `SupervisorJob` hijo de `scope` (regla R7: el scope
 * inyectado debe ser `SupervisorJob() + Dispatchers.Default`); [stop] las cancela y espera, de modo que
 * no queda ninguna viva tras detener el motor.
 *
 * Transiciones: `Stopped → StartingTor(p) → Online`, o `→ Error(mensaje)` si Tor falla (en ese caso se
 * liberan los recursos y se puede volver a llamar a [start]). [start] y [stop] están serializados y son
 * idempotentes. Nota: [stop] espera a que termine un [start] en curso.
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
    private var listener: Listener? = null
    private var publisher: OnionPublisher? = null

    @Volatile
    private var identity: Identity? = null

    /** Puerto local del listener mientras el motor está en marcha (para pruebas). */
    internal val localPort: Int? get() = listener?.port

    suspend fun start() {
        lifecycle.withLock {
            if (running) return
            val job = SupervisorJob(scope.coroutineContext[Job])
            val engineScope = CoroutineScope(scope.coroutineContext + job)
            engineJob = job
            running = true
            try {
                val id = identities.load() ?: createIdentity()
                identity = id
                mutableStatus.value = EngineStatus.StartingTor(0)
                val torError = startTor(engineScope)
                if (torError != null) {
                    releaseResources()
                    mutableStatus.value = EngineStatus.Error(torError)
                    return
                }
                val l = Listener(engineScope, ::onIncoming)
                listener = l
                val p = OnionPublisher(tor, identities, config.virtualPort, l.port)
                publisher = p
                identity = p.sync(id, contacts.all())
                mutableMyOnion.value = p.publishedOnion
                mutableStatus.value = EngineStatus.Online
            } catch (e: CancellationException) {
                withContext(NonCancellable) { releaseResources() }
                mutableStatus.value = EngineStatus.Stopped
                throw e
            } catch (e: Exception) {
                releaseResources()
                mutableStatus.value = EngineStatus.Error(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun stop() {
        lifecycle.withLock {
            if (running) releaseResources()
            mutableStatus.value = EngineStatus.Stopped
        }
    }

    /** Crea la identidad local (par X25519 nuevo, todavía sin .onion) y la guarda. */
    private suspend fun createIdentity(): Identity {
        val pair = Primitives.generateX25519()
        return Identity(pair.privateKey, pair.publicKey, onionPrivateKey = null, onion = null)
            .also { identities.save(it) }
    }

    /**
     * Arranca Tor y espera `Ready` o `Error`, reflejando el progreso en [status].
     * Devuelve el mensaje de error, o `null` si Tor quedó listo.
     */
    private suspend fun startTor(engineScope: CoroutineScope): String? {
        val progress = engineScope.launch {
            tor.state.collect { s ->
                if (s is TorState.Starting) mutableStatus.value = EngineStatus.StartingTor(s.percent)
            }
        }
        try {
            tor.start()
            val final = tor.state.first { it is TorState.Ready || it is TorState.Error }
            return (final as? TorState.Error)?.message
        } finally {
            // Esperar al colector para que no pise un estado posterior (Online/Error).
            progress.cancelAndJoin()
        }
    }

    /**
     * Conexión entrante en el listener. En esta fase todavía no hay protocolo de sesión: se cierra.
     * Tareas posteriores harán aquí el handshake de invitación/contacto.
     */
    private fun onIncoming(socket: Socket) {
        runCatching { socket.close() }
    }

    /** Cierra sesiones y listener, retira servicios, para Tor y borra el estado en memoria. */
    private suspend fun releaseResources() {
        runCatching { publisher?.unpublish() }
        publisher = null
        listener?.closeAndJoin()
        listener = null
        engineJob?.cancelAndJoin()
        engineJob = null
        runCatching { tor.stop() }
        // Historial en memoria: todavía no existe (tareas posteriores lo borran aquí).
        mutableMyOnion.value = null
        identity = null
        running = false
    }
}
