package org.ghostp2.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import org.ghostp2.core.crypto.SafetyNumber
import org.ghostp2.core.protocol.FrameStream
import org.ghostp2.core.protocol.HandshakeFailedException
import org.ghostp2.core.protocol.Pairing
import org.ghostp2.core.protocol.PairingOffer
import org.ghostp2.core.protocol.PairingResult
import org.ghostp2.core.protocol.PeerSession
import org.ghostp2.core.protocol.SessionEvent
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Origen de un [EngineStatus.Error]: el almacenamiento cifrado o cualquier otra cosa (Tor). */
enum class EngineErrorKind { TOR, STORAGE }

/** Estado visible del motor. */
sealed interface EngineStatus {
    data object Stopped : EngineStatus
    data class StartingTor(val percent: Int) : EngineStatus
    data object Online : EngineStatus

    /** Fallo al arrancar o con el motor en marcha; [kind] es `STORAGE` solo si falló el almacenamiento. */
    data class Error(val message: String, val kind: EngineErrorKind = EngineErrorKind.TOR) : EngineStatus
}

/** Resultado de [MessengerEngine.deleteContact]: si el contacto recibió el aviso `Unpair`. */
enum class UnpairDelivery { DELIVERED, NOT_DELIVERED }

/** Longitud máxima del nombre de un contacto, en caracteres Unicode (puntos de código), tras recortar espacios. */
const val MAX_CONTACT_NAME_CHARS = 64

/**
 * Motor de mensajería P2P sobre Tor. En esta fase: arranque/parada, identidad local, escucha local,
 * publicación de la .onion permanente (ver [OnionPublisher]), invitaciones/emparejamiento
 * ([createInvitation], [acceptInvitation]) y presencia con conexión automática a los contactos
 * ([presence], [onNetworkChanged], [notices]; ver [Connections]) y conversaciones en memoria
 * ([conversations], [sendText], [sendPhoto], [clearConversation]; ver [Conversations]), y gestión de
 * contactos: [deleteContact] (con aviso `Unpair`), aviso recibido ([Notice.ContactUnpairedYou]),
 * [renameContact], [setVerified] y [wipeAll]. Si Tor falla al actualizar la publicación tras un
 * borrado o un emparejamiento deshecho, se reintenta ([Notice.PublicationFailed]).
 *
 * Todas las corrutinas del motor cuelgan de un `SupervisorJob` hijo de `scope` (regla R7: el scope
 * inyectado debe ser `SupervisorJob() + Dispatchers.Default`); [stop] las cancela y espera, de modo que
 * no queda ninguna viva tras detener el motor.
 *
 * Transiciones: `Stopped → StartingTor(p) → Online`, o `→ Error(mensaje)` si Tor falla (en ese caso se
 * liberan los recursos y se puede volver a llamar a [start]). Si al arrancar falla el almacenamiento
 * (leer contactos o identidad, o guardar la identidad), el estado es `Error(STORAGE_ERROR_MESSAGE,
 * EngineErrorKind.STORAGE)` (Tor no llega a arrancar si el fallo es anterior a él); cualquier otro
 * fallo es de `kind = TOR`. Tor falla si informa `Error`, si vuelve a `Off` durante el arranque o si
 * no está listo en `EngineConfig.torBootstrapTimeoutMillis`. Con el motor `Online`, si Tor pasa a
 * `Error`/`Off` se libera todo y se pasa a `Error` (regla R18). [start] y [stop] están serializados y
 * son idempotentes; [stop] cancela un [start] en curso (que termina en `Stopped` sin lanzar).
 */
class MessengerEngine(
    private val tor: TorTransport,
    private val identities: IdentityRepository,
    private val contacts: ContactRepository,
    private val scope: CoroutineScope,
    private val config: EngineConfig = EngineConfig(),
    private val random: java.util.Random = java.security.SecureRandom(),
    /** Reloj de pared para las marcas de tiempo de los mensajes (inyectable en pruebas). */
    clock: () -> Long = System::currentTimeMillis,
    /** Borrados de contacto pendientes, para reanudarlos al arrancar (ver [deleteContact]). */
    private val deletions: DeletionJournal = NoDeletionJournal,
) {
    private val mutableStatus = MutableStateFlow<EngineStatus>(EngineStatus.Stopped)
    val status: StateFlow<EngineStatus> = mutableStatus.asStateFlow()

    private val mutableMyOnion = MutableStateFlow<String?>(null)

    /** .onion permanente publicada ahora mismo (sin ".onion"); `null` si no hay contactos o el motor está parado. */
    val myOnion: StateFlow<String?> = mutableMyOnion.asStateFlow()

    private val mutablePresence = MutableStateFlow<Map<String, Presence>>(emptyMap())

    /**
     * Presencia por id de contacto mientras el motor está en marcha (ver [Connections]). Con el motor
     * parado el mapa está **vacío** (no se conserva un `OFFLINE` por contacto).
     * `ONLINE` solo una vez terminado el `start()` de la sesión: entonces `sessionFor` ya la devuelve.
     */
    val presence: StateFlow<Map<String, Presence>> = mutablePresence.asStateFlow()

    private val mutableNotices = MutableSharedFlow<Notice>(extraBufferCapacity = NOTICE_BUFFER)

    /**
     * Avisos para la app (sin reproducción). Búfer de [NOTICE_BUFFER] avisos por suscriptor; si un
     * suscriptor se atasca y lo llena, quien emite **espera** (no se descarta ninguno). Sin suscriptores
     * los avisos se pierden: la app debe recolectar desde que arranca el motor.
     */
    val notices: SharedFlow<Notice> = mutableNotices.asSharedFlow()

    /**
     * Todos los eventos de todas las sesiones, con el id del contacto (para pruebas y Tasks 6–7).
     * Los bytes de `PhotoReceived` son los mismos que guarda el historial y pueden estar ya rellenados
     * con ceros (foto descartada, borrada o motor parado): no hay que fiarse de ellos aquí.
     */
    private val mutableSessionEvents =
        MutableSharedFlow<Pair<String, SessionEvent>>(extraBufferCapacity = SESSION_EVENT_BUFFER)
    internal val sessionEvents: SharedFlow<Pair<String, SessionEvent>> = mutableSessionEvents.asSharedFlow()

    @Volatile private var connections: Connections? = null

    /**
     * Historial y, bajo su mismo cerrojo, los ids de contactos conocidos (instantáneas de
     * `contacts.contacts` más altas y bajas locales con marcas; ver [Conversations]). Sin contactos
     * conocidos con el motor parado. Existe ya mientras Tor arranca, a diferencia de los `links` de
     * [Connections].
     */
    private val chats = Conversations(clock, maxPhotoBytes = config.maxPhotoBytesInMemory)

    /**
     * Serializa TODAS las escrituras del motor en `contacts` (emparejar, deshacer, borrar, renombrar,
     * verificar, marcar `IDENTITY_CHANGED`): una lectura-modificación-escritura nunca resucita un
     * contacto borrado ni pisa un emparejamiento nuevo.
     */
    private val contactWrites = Mutex()

    /**
     * Borrados en curso por id (local o aviso recibido): las llamadas simultáneas comparten el resultado.
     * Solo se modifica con su propio monitor, en el mismo paso que se publica [deleting].
     */
    private val inFlightDeletions = HashMap<String, CompletableDeferred<UnpairDelivery>>()

    private val mutableDeleting = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Ids de contactos que se están borrando ahora mismo ([deleteContact] en curso, p. ej. esperando a
     * entregar `Unpair`, o un aviso `Unpair` recibido que se está aplicando). Este conjunto es solo de
     * memoria; los borrados locales constan además en el [DeletionJournal] y se reanudan al arrancar.
     */
    val deleting: StateFlow<Set<String>> = mutableDeleting.asStateFlow()

    /** Registra `mine` como el borrado en curso de `id`; si ya había uno, lo devuelve (y no registra nada). */
    private fun claimDeletion(id: String, mine: CompletableDeferred<UnpairDelivery>): CompletableDeferred<UnpairDelivery>? =
        synchronized(inFlightDeletions) {
            inFlightDeletions[id]?.let { return it }
            inFlightDeletions[id] = mine
            mutableDeleting.value = inFlightDeletions.keys.toSet()
            null
        }

    private fun releaseDeletion(id: String, mine: CompletableDeferred<UnpairDelivery>) {
        synchronized(inFlightDeletions) {
            if (inFlightDeletions[id] === mine) inFlightDeletions.remove(id)
            mutableDeleting.value = inFlightDeletions.keys.toSet()
        }
    }

    /**
     * Conversaciones por id de contacto, en orden de llegada (como mucho
     * [MAX_ITEMS_PER_CONVERSATION] por contacto; se descartan los más antiguos). Solo en memoria:
     * sobreviven a cortes de conexión y se borran con [clearConversation] o al detener el motor
     * (con el motor parado el mapa está vacío y no se guarda nada). Solo hay conversaciones de
     * contactos guardados. Las fotos ([ChatContent.Photo.jpeg]) son los arrays vivos del historial y
     * se rellenan con ceros al salir de él: la interfaz debe copiar los bytes que quiera conservar.
     * Entre todas las conversaciones las fotos ocupan como mucho `EngineConfig.maxPhotoBytesInMemory`
     * bytes: al superarlo, las más antiguas pasan a [ChatContent.PhotoDiscarded] (bytes con ceros).
     */
    val conversations: StateFlow<Map<String, List<ChatItem>>> = chats.conversations

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

    /** .onion temporal de la invitación activa, para rechazar aceptar la propia. */
    @Volatile private var activeInvitationOnion: String? = null

    /**
     * Claves públicas de autorización de emparejamientos en curso que todavía no son contacto (regla
     * R10), por hex. Se publican junto a las de los contactos; solo se modifica con `publication`.
     */
    private val pendingClientAuthKeys = ConcurrentHashMap<String, ByteArray>()

    /** Serializa `publisher.sync` con la actualización de [identity] y de [myOnion]. */
    private val publication = Mutex()

    /**
     * Publicación pendiente de re-sincronizar (falló un `sync` tras un borrado o un emparejamiento
     * deshecho). [republishJob] la reintenta en el scope del motor; ver [scheduleRepublish].
     */
    private val publicationDirty = java.util.concurrent.atomic.AtomicBoolean(false)
    private val republishJob = AtomicReference<Job?>(null)

    /** Se incrementa en [onNetworkChanged]: despierta la espera de [republishLoop]. */
    private val republishWake = MutableStateFlow(0L)

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
                watchTor(job, engineScope)
            } catch (e: CancellationException) {
                // Arranque cancelado por stop() (o el llamante fue cancelado). Manda la cancelación
                // original: un fallo secundario al liberar se adjunta como suprimido.
                try {
                    withContext(NonCancellable) { releaseResources() }
                } catch (secondary: Exception) {
                    e.addSuppressed(secondary)
                } finally {
                    mutableStatus.value = EngineStatus.Stopped
                }
                if (!currentCoroutineContext().isActive) throw e
            } catch (e: Exception) {
                // Un fallo de arranque nunca hace lanzar a start(): se informa en Error con la causa
                // primaria y un fallo secundario al liberar queda como suprimido de ella.
                try {
                    withContext(NonCancellable) { releaseResources() }
                } catch (secondary: Exception) {
                    e.addSuppressed(secondary)
                } finally {
                    mutableStatus.value = if (e is StorageStartException) {
                        EngineStatus.Error(STORAGE_ERROR_MESSAGE, EngineErrorKind.STORAGE)
                    } else {
                        EngineStatus.Error(e.message ?: e.javaClass.simpleName)
                    }
                }
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
            try {
                // Como en start(): un llamante cancelado no deja Tor ni `running` a medio liberar.
                if (running) withContext(NonCancellable) { releaseResources() }
            } finally {
                mutableStatus.value = EngineStatus.Stopped
            }
        }
    }

    /**
     * Arranque completo: identidad, Tor, listener, publicación y reanudación de los borrados pendientes.
     * Lanza [TorStartException] si Tor falla y [StorageStartException] si falla una llamada a los
     * repositorios (también el guardado de la identidad que hace [OnionPublisher] durante el arranque).
     */
    private suspend fun bootstrap(engineScope: CoroutineScope) {
        // Historial activo desde el principio: un envío mientras Tor arranca queda como NOT_CONNECTED.
        chats.open()
        chats.applySnapshot(storage { contacts.all() }.map { it.id })
        engineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            contacts.contacts.collect { list -> chats.applySnapshot(list.map { it.id }) }
        }
        val id = storage { identities.load() } ?: storage { createIdentity() }
        identity = id
        startTor(engineScope)
        registerClientAuth()
        val c = Connections(
            tor, contacts, config, random, engineScope, KeyPair25519(id.identityPrivate, id.identityPublic),
            mutablePresence, mutableNotices, mutableSessionEvents,
            onSessionEvent = { contactId, session, event ->
                if (chats.onEvent(contactId, session, event)) mutableNotices.emit(Notice.MessageArrived(contactId))
            },
            contactWrites = contactWrites,
            onPeerUnpaired = ::onPeerUnpaired,
        )
        connections = c
        val l = Listener(engineScope, ::onIncoming)
        listener = l
        val guarded = StartupGuardedIdentities(identities)
        val p = OnionPublisher(tor, guarded, config.virtualPort, l.port)
        publisher = p
        identity = p.sync(id, storage { contacts.all() })
        // Pasado el arranque, los fallos al guardar vuelven a llegar tal cual a quien llame a `sync`.
        guarded.starting = false
        mutableMyOnion.value = p.publishedOnion
        c.start()
        resumePendingDeletions(engineScope)
    }

    /**
     * Reanuda los borrados que quedaron a medias en una ejecución anterior (ver [deleteContact]): cada id
     * del diario que sigue siendo un contacto guardado se borra en el scope del motor (con aviso
     * `Unpair`, como un borrado normal); los demás se quitan del diario. Si no se puede leer el diario,
     * no se reanuda ninguno.
     */
    private suspend fun resumePendingDeletions(engineScope: CoroutineScope) {
        val pending = try {
            deletions.pending()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptySet()
        }
        if (pending.isEmpty()) return
        val stored = storage { contacts.all() }.map { it.id }.toSet()
        for (contactId in pending) {
            if (contactId in stored) {
                // Sin dejar escapar fallos: una excepción no capturada en el scope del motor cerraría la app.
                engineScope.launch(start = CoroutineStart.UNDISPATCHED) { bestEffort { deleteContact(contactId) } }
            } else {
                bestEffort { deletions.remove(contactId) }
            }
        }
    }

    /** Ejecuta una llamada a los repositorios durante el arranque: un fallo (no cancelación) pasa a [StorageStartException]. */
    private suspend inline fun <T> storage(block: () -> T): T =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw StorageStartException(e)
        }

    /**
     * Repositorio de identidad que recibe [OnionPublisher]: mientras [starting], sus fallos pasan a
     * [StorageStartException] (el `sync` del arranque también llama a Tor, cuyos fallos no son del
     * almacenamiento). Después se comporta igual que `inner`.
     */
    private class StartupGuardedIdentities(private val inner: IdentityRepository) : IdentityRepository {
        @Volatile var starting = true

        override suspend fun load(): Identity? = guard { inner.load() }

        override suspend fun save(identity: Identity) = guard { inner.save(identity) }

        override suspend fun wipe() = guard { inner.wipe() }

        private suspend inline fun <T> guard(block: () -> T): T =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (starting) throw StorageStartException(e) else throw e
            }
    }

    /**
     * Tor guarda las autorizaciones de cliente solo en memoria: tras cada arranque (con Tor ya `Ready` y
     * antes de lanzar los bucles de conexión) se registran de nuevo las de todos los contactos `ACTIVE`,
     * en paralelo y como mucho durante `EngineConfig.clientAuthRegistrationTimeoutMillis` en total. Un
     * fallo no impide el arranque: los contactos que no quedaron registrados (error o tiempo agotado) se
     * avisan con [Notice.ClientAuthFailed] y no podrán recibir llamadas nuestras hasta el siguiente
     * arranque (ellos sí pueden llamarnos).
     */
    private suspend fun registerClientAuth() {
        val active = storage { contacts.all() }.filter { it.status == ContactStatus.ACTIVE }
        if (active.isEmpty()) return
        val pending: MutableSet<String> = ConcurrentHashMap.newKeySet<String>().apply { addAll(active.map { it.id }) }
        withTimeoutOrNull(config.clientAuthRegistrationTimeoutMillis) {
            coroutineScope {
                active.forEach { contact ->
                    launch {
                        try {
                            tor.addClientAuth(contact.peerOnion, contact.clientAuthPrivateForPeerOnion)
                            pending.remove(contact.id)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Queda en `pending`: se avisa abajo.
                        }
                    }
                }
            }
        }
        if (pending.isNotEmpty()) mutableNotices.emit(Notice.ClientAuthFailed(pending.sorted()))
    }

    /**
     * Regla R18: mientras el motor está `Online`, si Tor pasa a `Error` (p. ej. su proceso murió) o a `Off`
     * sin que lo pidiéramos, el motor libera la ejecución como en [stop] (sesiones cerradas, presencia
     * vacía: nadie `ONLINE`, Tor detenido) y queda en [EngineStatus.Error]; la app puede volver a llamar
     * a [start]. Se elige esto (y no reiniciar solo) por ser lo más simple y previsible: la app (Task 10)
     * decide cuándo reintentar. Un cambio de red no pasa por aquí: lo comunica la app con
     * [onNetworkChanged].
     */
    private fun watchTor(job: Job, engineScope: CoroutineScope) {
        engineScope.launch {
            val lost = tor.state.first { it is TorState.Error || it == TorState.Off }
            val message = (lost as? TorState.Error)?.message ?: TOR_STOPPED
            // En el scope externo: liberar cancela y espera a engineJob, y este colector cuelga de él.
            scope.launch { onTorLost(job, message) }
        }
    }

    private suspend fun onTorLost(job: Job, message: String) {
        lifecycle.withLock {
            if (!running || engineJob !== job) return@withLock // ya parado o es otra ejecución
            try {
                withContext(NonCancellable) { releaseResources() }
            } catch (_: Exception) {
                // Mejor esfuerzo: el estado final es Error igualmente.
            } finally {
                mutableStatus.value = EngineStatus.Error(message)
            }
        }
    }

    /** Corta las esperas de reintento de todos los contactos, vuelve a la espera mínima y reintenta ya. */
    fun onNetworkChanged() {
        connections?.onNetworkChanged()
        republishWake.update { it + 1 }
    }

    /**
     * Envía un texto (máx. [PeerSession.MAX_TEXT_CHARS] caracteres Unicode) y devuelve su id. No
     * suspende. El elemento queda en [conversations] al volver: `Sending` si hay sesión (después
     * `Delivered` o `Failed`), o ya `Failed(NOT_CONNECTED)` si no la hay; nunca se encola ni se reintenta.
     * Con el motor parado o si [contactId] no es un contacto guardado (o se está borrando, ver
     * [deleteContact]), devuelve un id y no guarda nada (regla R15).
     * @throws IllegalArgumentException si el texto es demasiado largo (como `PeerSession`; no se guarda nada).
     */
    fun sendText(contactId: String, text: String): String {
        require(text.codePointCount(0, text.length) <= PeerSession.MAX_TEXT_CHARS) {
            "El texto supera ${PeerSession.MAX_TEXT_CHARS} caracteres"
        }
        val peer = sessionFor(contactId)
        return chats.addOutgoing(contactId, ChatContent.Text(text), peer?.let { p -> outbound(p) { p.sendText(text) } })
    }

    /**
     * Envía una foto (1..[PeerSession.MAX_PHOTO_BYTES] bytes) y devuelve su id; como [sendText], con
     * progreso `Sending(enviados, total)`. Se copia [jpeg] al entrar: el array del llamante no se
     * envía, no se guarda ni se borra (puede reutilizarlo enseguida). La copia es la del historial.
     * @throws IllegalArgumentException si la foto está vacía o es demasiado grande (no se guarda nada).
     */
    fun sendPhoto(contactId: String, jpeg: ByteArray): String {
        require(jpeg.isNotEmpty()) { "La foto está vacía" }
        require(jpeg.size <= PeerSession.MAX_PHOTO_BYTES) { "La foto supera ${PeerSession.MAX_PHOTO_BYTES} bytes" }
        val copy = jpeg.copyOf()
        val peer = sessionFor(contactId)
        try {
            return chats.addOutgoing(contactId, ChatContent.Photo(copy), peer?.let { p -> outbound(p) { p.sendPhoto(copy) } })
        } catch (e: Exception) {
            Primitives.wipe(copy)
            throw e
        }
    }

    private fun outbound(peer: PeerSession, send: () -> String) =
        Conversations.Outbound(peer, { peer.isOpen.value }, send)

    /** Borra la conversación con el contacto (fotos rellenadas con ceros); las demás no cambian. */
    fun clearConversation(contactId: String) {
        chats.clear(contactId)
    }

    /**
     * Número de seguridad con el contacto (30 dígitos en 6 grupos de 5), el mismo que dio `Paired` al
     * emparejar: solo depende de mi identidad pública y de la suya. Funciona con el motor parado (lee la
     * identidad del repositorio). Las claves privadas de la identidad leída (una copia: el repositorio
     * devuelve copias nuevas en cada lectura) se rellenan con ceros al terminar.
     * @throws NoSuchElementException si [contactId] no es un contacto guardado.
     */
    suspend fun safetyNumber(contactId: String): String {
        val contact = contacts.all().firstOrNull { it.id == contactId }
            ?: throw NoSuchElementException("Contacto desconocido")
        val mine = identities.load() ?: throw IllegalStateException("No hay identidad local")
        try {
            return SafetyNumber.compute(mine.identityPublic, contact.peerIdentity)
        } finally {
            Primitives.wipe(mine.identityPrivate)
            mine.onionPrivateKey?.let { Primitives.wipe(it) }
        }
    }

    // --- Contactos: borrar, aviso recibido, renombrar, verificar y borrar todo (Task 7) ---

    /**
     * Borra el contacto avisándole (spec §6.7 bis):
     * 1. Lo olvida en el acto ([Conversations.forget]): su conversación se borra y desde aquí ningún
     *    envío ni recepción para ese id guarda nada. Su bucle de reconexión se para y no vuelve.
     * 2. Entrega `Unpair` por la sesión abierta o, sin ella, intenta conectar (y acepta sus llamadas)
     *    solo para entregarlo, durante como mucho `EngineConfig.unpairDeliveryWindowMillis`.
     * 3. Borrado local, siempre y en este orden: `contacts.delete`, `tor.removeClientAuth(peerOnion)`
     *    y re-publicación de la .onion sin su clave (retirada si no quedan contactos ni emparejamientos
     *    en curso, ver [reservePublication]).
     *
     * Devuelve [UnpairDelivery.DELIVERED] solo si `PeerSession.sendUnpair()` devolvió true (la trama
     * `Unpair` se escribió en el flujo; el núcleo no tiene acuse para ella).
     *
     * El borrado local ocurre siempre una vez empezado: si el motor se detiene (o el llamante se
     * cancela) a mitad de la entrega se deja de intentar y se borra igualmente (el llamante cancelado
     * recibe su cancelación). Con el motor parado no hay entrega posible: `NOT_DELIVERED` y borrado.
     * Llamadas simultáneas con el mismo id comparten el resultado del único borrado real; un id que no
     * es un contacto guardado (o ya borrado) devuelve `NOT_DELIVERED` sin hacer nada. Si el propio
     * repositorio falla al borrar, se quitan igualmente la autorización y la publicación y después se
     * relanza su excepción (en esta ejecución el contacto sigue olvidado). Si mientras se intentaba
     * entregar se volvió a emparejar con el mismo contacto (mismo id), el emparejamiento nuevo se
     * conserva intacto y el borrado antiguo devuelve `NOT_DELIVERED` (ver [removeLocally]).
     *
     * Diario: tras leer el contacto se apunta su id en el [DeletionJournal] (si falla, el borrado sigue
     * solo en memoria) y se quita al terminar el borrado local. Si el proceso muere a mitad, el siguiente
     * [start] reanuda el borrado.
     */
    suspend fun deleteContact(contactId: String): UnpairDelivery {
        val mine = CompletableDeferred<UnpairDelivery>()
        claimDeletion(contactId, mine)?.let { return it.await() }
        try {
            return runDelete(contactId).also { mine.complete(it) }
        } catch (e: CancellationException) {
            mine.complete(UnpairDelivery.NOT_DELIVERED)
            throw e
        } catch (e: Exception) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            releaseDeletion(contactId, mine)
        }
    }

    private suspend fun runDelete(contactId: String): UnpairDelivery {
        // Lectura y olvido en un paso respecto a las escrituras: un emparejamiento nuevo del mismo id
        // (que va con `contactWrites`) o es el que se borra, o llega después y su addKnown lo revive.
        val contact = contactWrites.withLock {
            contacts.all().firstOrNull { it.id == contactId }?.also { chats.forget(contactId) }
        } ?: return UnpairDelivery.NOT_DELIVERED
        var delivered = false
        try {
            // Apuntado para reanudarlo si el proceso muere a mitad; si no se puede, sigue solo en memoria.
            bestEffort { deletions.add(contactId) }
            delivered = connections?.deliverUnpair(contactId, config.unpairDeliveryWindowMillis) == true
        } finally {
            withContext(NonCancellable) {
                removeLocally(contact)
                // También si se conservó un re-emparejamiento: este borrado ya terminó. Si removeLocally
                // lanzó (el repositorio no pudo borrar), la entrada se queda y se reanuda al arrancar.
                bestEffort { deletions.remove(contactId) }
            }
        }
        return if (delivered) UnpairDelivery.DELIVERED else UnpairDelivery.NOT_DELIVERED
    }

    /**
     * El contacto nos deshizo el emparejamiento (su sesión ya está cerrada, sin `Bye`, y su bucle
     * parado): mismo borrado local que [deleteContact] y después [Notice.ContactUnpairedYou] con el
     * nombre que tenía. Si ya se estaba borrando aquí, ese borrado lo termina (sin aviso). No lanza:
     * si el borrado local falla (p. ej. el repositorio no puede borrar), el contacto queda olvidado y sin
     * bucle en esta ejecución, pero sigue guardado y vuelve al reiniciar el motor (sin avisar otra vez).
     */
    private suspend fun onPeerUnpaired(contactId: String) {
        val mine = CompletableDeferred<UnpairDelivery>()
        if (claimDeletion(contactId, mine) != null) return
        try {
            val contact = contacts.all().firstOrNull { it.id == contactId } ?: return
            withContext(NonCancellable) { removeLocally(contact) }
            mutableNotices.emit(Notice.ContactUnpairedYou(contactId, contact.name))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fallo del repositorio: el contacto queda olvidado en esta ejecución; nada más que hacer.
        } finally {
            mine.complete(UnpairDelivery.NOT_DELIVERED)
            releaseDeletion(contactId, mine)
        }
    }

    /**
     * Borrado local de `contact` (el leído al empezar): olvidar (idempotente), baja en [Connections] y
     * `contacts.delete`, los tres con `contactWrites` tomado; después `removeClientAuth` y
     * re-publicación ([syncOrRetry]: si Tor falla se reintenta). Con `publication` tomado, la
     * re-publicación no ocurre si el motor ya retiró su .onion al detenerse. Llamar sin cancelación.
     *
     * **Re-emparejado entretanto.** El id deriva de la identidad, así que volver a emparejar con el
     * mismo contacto reutiliza el id. Si el contacto guardado ya no es `contact` (otro `pairSecret` u
     * otra .onion), el nuevo se respeta: no se olvida, ni se da de baja, ni se borra; solo se quita la
     * autorización de la .onion ANTIGUA si es distinta de la nueva, y se re-publica.
     *
     * Si `contacts.delete` falla se hacen igualmente `removeClientAuth` y la re-publicación y después
     * se relanza el fallo. Solo tras un borrado correcto se rellenan con ceros los secretos de `contact`
     * y los del enlace (copias en memoria del motor).
     */
    private suspend fun removeLocally(contact: Contact) {
        var deleteFailure: Exception? = null
        var replacement: Contact? = null
        var retired: Contact? = null
        contactWrites.withLock {
            val stored = runCatching { contacts.all() }.getOrNull()?.firstOrNull { it.id == contact.id }
            if (stored != null && !(stored.pairSecret.contentEquals(contact.pairSecret) && stored.peerOnion == contact.peerOnion)) {
                replacement = stored
                return@withLock
            }
            chats.forget(contact.id)
            retired = connections?.retire(contact.id)
            try {
                contacts.delete(contact.id)
            } catch (e: Exception) {
                deleteFailure = e
            }
        }
        if (replacement?.peerOnion != contact.peerOnion) runCatching { tor.removeClientAuth(contact.peerOnion) }
        syncOrRetry()
        deleteFailure?.let { throw it }
        if (replacement == null) {
            wipeSecrets(contact)
            retired?.let { wipeSecrets(it) }
        }
    }

    private fun wipeSecrets(contact: Contact) {
        Primitives.wipe(contact.pairSecret, contact.clientAuthPrivateForPeerOnion)
    }

    /**
     * Re-sincroniza la publicación; si falla, la marca pendiente y la reintenta en segundo plano
     * ([scheduleRepublish]). Sin `publisher` (motor parado) no hace nada: el próximo arranque publica.
     */
    private suspend fun syncOrRetry() {
        val ok = publication.withLock {
            val p = publisher ?: return@withLock true
            succeeded { syncLocked(p) }
        }
        if (!ok) scheduleRepublish()
    }

    /**
     * Marca la publicación pendiente y se asegura de que haya un único reintento en marcha en el scope
     * del motor (regla R7: [stop] lo cancela y espera con el resto). Sin motor en marcha no hace nada.
     */
    private fun scheduleRepublish() {
        publicationDirty.set(true)
        val engineScope = workScope ?: return
        while (true) {
            val current = republishJob.get()
            if (current?.isActive == true) return
            val job = engineScope.launch(start = CoroutineStart.LAZY) { republishLoop() }
            if (republishJob.compareAndSet(current, job)) {
                job.start()
                return
            }
            job.cancel()
        }
    }

    /**
     * Reintenta `sync` con la espera creciente de los contactos ([Backoff], `backoffMinMillis` →
     * `backoffMaxMillis`, o antes si cambia la red) hasta que salga bien y no quede nada pendiente.
     * Tras [REPUBLISH_FAILURES_BEFORE_NOTICE] fallos seguidos emite [Notice.PublicationFailed] (una vez
     * por racha). Al terminar vuelve a mirar la marca: un fallo nuevo justo al salir no se pierde.
     */
    private suspend fun republishLoop() {
        val backoff = Backoff(config.backoffMinMillis, config.backoffMaxMillis, random)
        var failures = 0
        try {
            while (publicationDirty.get()) {
                val seen = republishWake.value
                val woke = withTimeoutOrNull(backoff.next()) { republishWake.first { it != seen } } != null
                if (woke) backoff.reset()
                publicationDirty.set(false)
                val ok = publication.withLock {
                    val p = publisher ?: return@withLock true
                    succeeded { syncLocked(p) }
                }
                if (ok) {
                    failures = 0
                } else {
                    publicationDirty.set(true)
                    if (++failures == REPUBLISH_FAILURES_BEFORE_NOTICE) mutableNotices.emit(Notice.PublicationFailed)
                }
            }
        } finally {
            val self = currentCoroutineContext()[Job]
            republishJob.compareAndSet(self, null)
        }
        if (publicationDirty.get()) scheduleRepublish()
    }

    /**
     * Cambia el nombre del contacto (se guarda en el repositorio; también con el motor parado). Se
     * recortan los espacios de los extremos.
     * @throws IllegalArgumentException si queda vacío o supera [MAX_CONTACT_NAME_CHARS] caracteres.
     * @throws NoSuchElementException si [contactId] no es un contacto guardado.
     */
    suspend fun renameContact(contactId: String, name: String) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "El nombre no puede estar vacío" }
        require(trimmed.codePointCount(0, trimmed.length) <= MAX_CONTACT_NAME_CHARS) {
            "El nombre supera $MAX_CONTACT_NAME_CHARS caracteres"
        }
        updateContact(contactId) { it.copy(name = trimmed) }
    }

    /**
     * Marca el contacto como verificado (o no) y lo guarda (también con el motor parado).
     * @throws NoSuchElementException si [contactId] no es un contacto guardado.
     */
    suspend fun setVerified(contactId: String, verified: Boolean) {
        updateContact(contactId) { it.copy(verified = verified) }
    }

    private suspend fun updateContact(contactId: String, change: (Contact) -> Contact) {
        contactWrites.withLock {
            val current = contacts.all().firstOrNull { it.id == contactId }
                ?: throw NoSuchElementException("Contacto desconocido")
            contacts.upsert(change(current))
        }
    }

    /**
     * Borra todo (spec §6.7 bis): envía `Unpair` en paralelo a los contactos con sesión abierta
     * (mejor esfuerzo, como mucho [WIPE_UNPAIR_MILLIS] en total), detiene el motor como [stop] y, en
     * la misma sección crítica (ningún [start] se cuela en medio), quita las autorizaciones de cliente
     * de cada contacto, borra contactos e identidad y rellena con ceros el material de claves: el que
     * usaba la ejecución (identidad, enlaces de [Connections]) y las copias leídas para borrar. También
     * borra el estado de Tor en disco ([TorTransport.wipeState]). El siguiente [start] crea una identidad
     * nueva y, al publicarse, una .onion nueva.
     * Funciona con el motor parado y aunque Tor falle (sus errores se ignoran); termina siempre en
     * `Stopped`. Solo lanza si falla el borrado de los repositorios (los fallos al detener el motor
     * se adjuntan como suprimidos de ese error, o se ignoran si el borrado fue bien).
     * También vacía el [DeletionJournal] (mejor esfuerzo); lo que quede se descarta en el siguiente
     * [start] como entradas sin contacto.
     */
    suspend fun wipeAll() {
        connections?.unpairAll(WIPE_UNPAIR_MILLIS)
        stopRequests.incrementAndGet()
        bootJob?.cancel()
        lifecycle.withLock {
            withContext(NonCancellable) {
                // Material vivo de la ejecución (puede no ser el mismo array que devuelve el repositorio).
                val live = buildList {
                    identity?.let { id ->
                        add(id.identityPrivate)
                        id.onionPrivateKey?.let(::add)
                    }
                    connections?.let { addAll(it.liveSecrets()) }
                }
                var releaseFailure: Exception? = null
                try {
                    if (running) releaseResources()
                } catch (e: Exception) {
                    releaseFailure = e
                } finally {
                    mutableStatus.value = EngineStatus.Stopped
                }
                // Estado de Tor en disco (guardas, caché, cookie de control): mejor esfuerzo, como el resto de Tor.
                runCatching { tor.wipeState() }
                try {
                    wipeStorage()
                } catch (e: Exception) {
                    releaseFailure?.let { e.addSuppressed(it) }
                    throw e
                }
                live.forEach { Primitives.wipe(it) }
                clearJournal()
            }
        }
    }

    /** Vacía el diario de borrados pendientes; mejor esfuerzo (sin contactos, lo que quede es obsoleto). */
    private suspend fun clearJournal() {
        val pending = try {
            deletions.pending()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return
        }
        pending.forEach { bestEffort { deletions.remove(it) } }
    }

    /** Autorizaciones, contactos e identidad; con ceros solo si el borrado fue bien (si no, siguen en uso). */
    private suspend fun wipeStorage() {
        val stored = runCatching { contacts.all() }.getOrDefault(emptyList())
        val storedIdentity = runCatching { identities.load() }.getOrNull()
        stored.forEach { runCatching { tor.removeClientAuth(it.peerOnion) } }
        var failure: Exception? = null
        contactWrites.withLock {
            try {
                contacts.wipe()
            } catch (e: Exception) {
                failure = e
            }
        }
        try {
            identities.wipe()
        } catch (e: Exception) {
            failure?.addSuppressed(e) ?: run { failure = e }
        }
        failure?.let { throw it }
        stored.forEach { Primitives.wipe(it.pairSecret, it.clientAuthPrivateForPeerOnion) }
        storedIdentity?.let { id ->
            Primitives.wipe(id.identityPrivate)
            id.onionPrivateKey?.let { Primitives.wipe(it) }
        }
    }

    /** Sesión abierta con el contacto, si la hay (para las tareas 6–7). */
    internal fun sessionFor(contactId: String): PeerSession? = connections?.sessionFor(contactId)

    /** Si la sesión actual con el contacto la inicié yo (`null` sin sesión); para pruebas del desempate. */
    internal fun sessionInitiatedByMe(contactId: String): Boolean? = connections?.sessionInitiatedByMe(contactId)

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
     * parada del motor) o `Failed`. En todos se retira el servicio temporal y se cierra su socket.
     * Los intentos fallidos no consumen la invitación. Crear otra cancela la anterior.
     * Con el motor no `Online` devuelve un handle ya en `Failed("GhostP2 no está activo")`.
     *
     * Cada intento genera su propio par de autorización de cliente (la privada va en la oferta de ese
     * intento y se borra al terminarlo); solo la pública del **ganador** se registra en mi .onion, así
     * que quien pierda una carrera con el mismo secreto no obtiene una clave válida.
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

    /** Resultado de un intento del invitador: emparejamiento y la pública que generé para ese otro lado. */
    private class InviterAttempt(val result: PairingResult, val myClientAuthPublic: ByteArray)

    private suspend fun runInvitation(state: MutableStateFlow<InvitationState>) {
        val secret = Primitives.randomBytes(Invitation.SECRET_SIZE)
        // Clave de reserva (R10): mantiene publicada mi .onion sin contactos; su privada no existe para nadie.
        val placeholder = Primitives.generateX25519().also { Primitives.wipe(it.privateKey) }.publicKey
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
            activeInvitationOnion = service.onion
            val myOnion = reservePublication(placeholder)
            val pairing = pairingIdentity()
            state.value = InvitationState.Waiting(
                Invitation(service.onion, secret).encode(),
                System.currentTimeMillis() + config.invitationTtlMillis,
            )
            val result = serveInvitation(
                socket,
                config.invitationTtlMillis,
                MAX_CONCURRENT_ATTEMPTS,
                attempt = { stream -> inviterAttempt(stream, pairing, secret, myOnion) },
                discard = { Primitives.wipe(it.result.pairSecret, it.result.clientAuthPrivateForPeerOnion) },
                complete = { a ->
                    val id = finishPairing(a.result, a.myClientAuthPublic, releaseKey = placeholder)
                    InvitationState.Paired(id, a.result.safetyNumber).also { paired = it }
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
                temporaryOnion?.let { onion ->
                    if (activeInvitationOnion == onion) activeInvitationOnion = null
                    runCatching { tor.unpublishService(onion) }
                }
                if (paired == null) runCatching { releasePublication(placeholder) }
                Primitives.wipe(secret)
                state.update { if (it.isFinal) it else outcome }
            }
        }
    }

    /** Un intento del invitador con un par de autorización nuevo; la privada se borra al terminar. */
    private suspend fun inviterAttempt(
        stream: FrameStream,
        pairing: KeyPair25519,
        secret: ByteArray,
        myOnion: String,
    ): InviterAttempt {
        val clientAuth = Primitives.generateX25519()
        try {
            val result = Pairing.asInviter(stream, pairing, secret, PairingOffer(myOnion, clientAuth.privateKey))
            if (result.peerIdentity.contentEquals(pairing.publicKey)) {
                Primitives.wipe(result.pairSecret, result.clientAuthPrivateForPeerOnion)
                throw IllegalStateException(InvitationMessages.OWN_INVITATION)
            }
            return InviterAttempt(result, clientAuth.publicKey)
        } finally {
            Primitives.wipe(clientAuth.privateKey)
        }
    }

    /**
     * Acepta una invitación (lado invitado): conecta a su .onion temporal y empareja como iniciador.
     * Si no tengo contactos, mi .onion permanente se publica antes de enviar la oferta, ya con la clave
     * del nuevo contacto (regla R10); si falla, esa clave se retira. Nunca lanza (salvo cancelación).
     */
    @OptIn(DelicateCoroutinesApi::class) // ATOMIC: el finally de runInvitee debe borrar siempre el secreto
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
        if (invitation.onion == activeInvitationOnion) {
            Primitives.wipe(invitation.secret)
            return AcceptResult.Failed(InvitationMessages.OWN_INVITATION)
        }
        // Regla R7: el trabajo corre en el scope del motor; parar el motor lo cancela. ATOMIC: el
        // cuerpo (y su finally, que borra el secreto) se ejecuta aunque se cancele antes de empezar.
        val work = engineScope.async(start = CoroutineStart.ATOMIC) { runInvitee(invitation) }
        return try {
            work.await()
        } catch (e: CancellationException) {
            work.cancel()
            Primitives.wipe(invitation.secret) // por si el trabajo no llegó a su finally
            if (!currentCoroutineContext().isActive) throw e
            // El motor se detuvo durante el emparejamiento.
            AcceptResult.Failed(InvitationMessages.NOT_ACTIVE)
        }
    }

    private suspend fun runInvitee(invitation: Invitation): AcceptResult {
        val clientAuth = Primitives.generateX25519()
        var paired = false
        try {
            val myOnion = reservePublication(clientAuth.publicKey)
            val pairing = pairingIdentity()
            val socket = tor.connect(invitation.onion, config.virtualPort, config.connectTimeoutMillis)
            val result = try {
                val stream = withContext(Dispatchers.IO) {
                    FrameStream(socket.getInputStream(), socket.getOutputStream(), socket)
                }
                Pairing.asInvitee(stream, pairing, invitation.secret, PairingOffer(myOnion, clientAuth.privateKey))
            } finally {
                runCatching { socket.close() }
            }
            if (result.peerIdentity.contentEquals(pairing.publicKey)) {
                Primitives.wipe(result.pairSecret, result.clientAuthPrivateForPeerOnion)
                return AcceptResult.Failed(InvitationMessages.OWN_INVITATION)
            }
            // Un fallo al guardar (ya deshecho por finishPairing) no es un fallo de conexión.
            val id = try {
                withContext(NonCancellable) { finishPairing(result, clientAuth.publicKey, releaseKey = null) }
            } catch (e: Exception) {
                return AcceptResult.Failed(InvitationMessages.GENERIC)
            }
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
     * Regla R10: devuelve mi .onion permanente para la oferta. Cada emparejamiento en curso registra
     * siempre su propia clave pendiente (`key`), haya o no contactos: es su reserva de la publicación.
     * Las claves pendientes solo se publican cuando no queda ningún contacto ([syncLocked]), así que
     * con contactos no cambia nada (ni se vuelve a publicar), pero si se borra el último contacto
     * mientras un emparejamiento está en curso la .onion sigue publicada bajo él; y terminar uno nunca
     * la retira bajo otro. Nunca se publica sin autorización de cliente.
     */
    private suspend fun reservePublication(key: ByteArray): String = publication.withLock {
        val p = publisher ?: throw IllegalStateException(InvitationMessages.NOT_ACTIVE)
        pendingClientAuthKeys[key.toHex()] = key.copyOf()
        syncLocked(p)
        p.publishedOnion ?: throw IllegalStateException("No se pudo publicar la .onion permanente")
    }

    /** Retira una clave pendiente (emparejamiento terminado sin éxito) y re-sincroniza si estaba. */
    private suspend fun releasePublication(key: ByteArray) {
        val removed = publication.withLock { pendingClientAuthKeys.remove(key.toHex()) != null }
        if (removed) syncOrRetry()
    }

    /**
     * Publica según los contactos guardados o, si no queda ninguno, según las claves pendientes de los
     * emparejamientos en curso ([reservePublication]). Llamar con `publication` tomado.
     */
    private suspend fun syncLocked(p: OnionPublisher) {
        val id = identity ?: throw IllegalStateException(InvitationMessages.NOT_ACTIVE)
        val saved = contacts.all()
        val reserved = if (saved.isEmpty()) pendingClientAuthKeys.values.toList() else emptyList()
        try {
            identity = p.sync(id, saved, reserved)
        } finally {
            // También si Tor falla a medias (p. ej. retiró pero no volvió a publicar).
            mutableMyOnion.value = p.publishedOnion
        }
    }

    /**
     * Guarda el contacto emparejado (reemplaza al de la misma identidad), registra la autorización
     * para su .onion y re-publica la mía con su clave (retirando a la vez `releaseKey`, la clave de
     * reserva del invitador). Devuelve el id del contacto. Es atómico para el usuario: si algo falla
     * tras guardar, se deshace (ver [rollbackPairing]) y se relanza. El contacto anterior del mismo id
     * se lee con `contactWrites` tomado, en el mismo paso que el guardado.
     * Los arrays de `result` pasan al [Contact] que se guarda y no se rellenan con ceros aquí: el
     * repositorio guarda sus propias copias (el real, cifradas) y estos arrays quedan para el recolector
     * de basura (PROTOCOLO §15).
     * Al terminar bien se quita el id del [DeletionJournal] (mejor esfuerzo): una entrada antigua de un
     * borrado anterior no puede borrar este emparejamiento nuevo en el siguiente arranque.
     */
    private suspend fun finishPairing(result: PairingResult, myClientAuthPublic: ByteArray, releaseKey: ByteArray?): String {
        val id = contactIdFor(result.peerIdentity)
        var previous: Contact? = null
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
        try {
            // Conocido antes de guardarlo: un mensaje suyo que llegue enseguida ya se guarda.
            contactWrites.withLock {
                previous = contacts.all().firstOrNull { it.id == id }
                chats.addKnown(id)
                contacts.upsert(contact)
            }
            tor.addClientAuth(result.peerOnion, result.clientAuthPrivateForPeerOnion)
            publication.withLock {
                pendingClientAuthKeys.remove(myClientAuthPublic.toHex())
                releaseKey?.let { pendingClientAuthKeys.remove(it.toHex()) }
                publisher?.let { syncLocked(it) }
            }
        } catch (e: Exception) {
            withContext(NonCancellable) { rollbackPairing(contact, previous) }
            throw e
        }
        bestEffort { deletions.remove(id) }
        val old = previous
        if (old != null && old.peerOnion != result.peerOnion) {
            bestEffort { tor.removeClientAuth(old.peerOnion) }
        }
        return id
    }

    /**
     * Deshace un [finishPairing] a medias; cada paso es independiente (lo que falle se ignora). Con
     * `contactWrites` tomado se mira qué hay guardado con ese id:
     * - Sigue siendo `contact` (mismo `pairSecret` y .onion): se restaura `previous` (y su autorización
     *   en Tor) o, sin anterior, se olvida ([Conversations.forget]) y se borra.
     * - No hay nada (p. ej. el usuario lo borró mientras tanto): no se resucita `previous` ni se vuelve a
     *   registrar su autorización; solo se olvida.
     * - Es otro emparejamiento (más nuevo): se respeta intacto. Si comparte .onion con `contact`, se
     *   vuelve a registrar su autorización en vez de quitarla.
     */
    private suspend fun rollbackPairing(contact: Contact, previous: Contact?) {
        var restored = false
        var replacement: Contact? = null
        contactWrites.withLock {
            bestEffort {
                val stored = contacts.all().firstOrNull { it.id == contact.id }
                when {
                    stored == null -> chats.forget(contact.id)
                    stored.pairSecret.contentEquals(contact.pairSecret) && stored.peerOnion == contact.peerOnion -> {
                        if (previous != null) {
                            contacts.upsert(previous)
                            restored = true
                        } else {
                            chats.forget(contact.id)
                            contacts.delete(contact.id)
                        }
                    }
                    else -> replacement = stored
                }
            }
        }
        val other = replacement
        if (other != null && other.peerOnion == contact.peerOnion) {
            bestEffort { tor.addClientAuth(other.peerOnion, other.clientAuthPrivateForPeerOnion) }
        } else {
            bestEffort { tor.removeClientAuth(contact.peerOnion) }
        }
        if (restored && previous != null) {
            bestEffort { tor.addClientAuth(previous.peerOnion, previous.clientAuthPrivateForPeerOnion) }
        }
        publication.withLock { pendingClientAuthKeys.remove(contact.myClientAuthPublic.toHex()) }
        syncOrRetry()
    }

    /** Conexión entrante en el listener: handshake de sesión en otra corrutina ([Connections.accept]). */
    private fun onIncoming(socket: Socket) {
        val c = connections
        if (c == null) runCatching { socket.close() } else c.accept(socket)
    }

    /**
     * Cierra sesiones, invitaciones y listener, retira servicios, para Tor y borra el estado en memoria.
     * Si el cierre de las conexiones falla, se termina igualmente de liberar todo y después se relanza
     * ese fallo (con el motor ya parado y la presencia vacía). El historial se borra siempre, al final
     * (con sesiones y recolectores ya terminados, ningún envío lee ya las fotos), aunque algo lance.
     */
    private suspend fun releaseResources() {
        try {
            releaseRun()
        } finally {
            // Siempre, aunque algo de lo anterior falle: el historial no sobrevive a una parada.
            chats.clearAll()
        }
    }

    private suspend fun releaseRun() {
        workScope = null
        // Primero esperar al arranque (ya cancelado o terminado) para que no cree nada después.
        bootJob?.cancelAndJoin()
        bootJob = null
        listener?.closeAndJoin()
        listener = null
        // Bucles y handshakes parados; sesiones cerradas con Bye; presencia vacía.
        var shutdownFailure: Exception? = null
        try {
            connections?.shutdown()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            shutdownFailure = e
        } finally {
            connections = null
            mutablePresence.value = emptyMap()
        }
        // Antes de retirar la .onion: las invitaciones y emparejamientos en curso pueden re-sincronizarla.
        engineJob?.cancelAndJoin()
        engineJob = null
        activeInvitation.set(null)
        // Con `publication`: un borrado de contacto que termina ahora no vuelve a publicar después.
        publication.withLock {
            runCatching { publisher?.unpublish() }
            publisher?.wipeKeys()
            publisher = null
            pendingClientAuthKeys.clear()
        }
        // El reintento colgaba de engineJob (ya cancelado y esperado); el próximo arranque publica de cero.
        republishJob.set(null)
        publicationDirty.set(false)
        runCatching { tor.stop() }
        mutableMyOnion.value = null
        identity = null
        running = false
        shutdownFailure?.let { throw it }
    }
}

private const val BACKLOG = 50

/** Avisos en búfer por suscriptor de [MessengerEngine.notices]. */
private const val NOTICE_BUFFER = 256

/** Fallos seguidos al re-publicar antes de emitir [Notice.PublicationFailed]. */
private const val REPUBLISH_FAILURES_BEFORE_NOTICE = 3

/** Tiempo total máximo de [MessengerEngine.wipeAll] para enviar `Unpair` a las sesiones abiertas. */
private const val WIPE_UNPAIR_MILLIS = 2_500L

/** Eventos de sesión en búfer por suscriptor de `sessionEvents`. */
private const val SESSION_EVENT_BUFFER = 1_024

/** Máximo de intentos de emparejamiento simultáneos por invitación (los demás se cierran al aceptarlos). */
private const val MAX_CONCURRENT_ATTEMPTS = 32

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/** Mensaje de [EngineStatus.Error] si Tor pasa a `Off` con el motor `Online` sin que se pidiera. */
private const val TOR_STOPPED = "Tor se detuvo"

/** Ejecuta `block` ignorando sus fallos, salvo la cancelación (que se relanza). */
private inline fun bestEffort(block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        // Mejor esfuerzo: el fallo se ignora.
    }
}

/** `true` si `block` termina sin lanzar; la cancelación se relanza (a diferencia de `runCatching`). */
private inline fun succeeded(block: () -> Unit): Boolean =
    try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

/** Fallo al arrancar Tor; el mensaje es el que se muestra en [EngineStatus.Error]. */
internal class TorStartException(message: String) : Exception(message)

/** Fallo del almacenamiento durante el arranque; [MessengerEngine.start] lo muestra como `Error(…, STORAGE)`. */
internal class StorageStartException(cause: Throwable) : Exception("Fallo del almacenamiento al arrancar", cause)

/** Mensaje de [EngineStatus.Error] cuando falla el almacenamiento al arrancar. */
internal const val STORAGE_ERROR_MESSAGE = "No se pudieron leer o guardar tus datos cifrados"
