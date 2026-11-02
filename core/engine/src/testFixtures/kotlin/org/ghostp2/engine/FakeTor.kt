package org.ghostp2.engine

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.digests.SHA3Digest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.ghostp2.core.crypto.Base32
import org.ghostp2.core.crypto.OnionAddress
import org.ghostp2.core.crypto.Primitives
import org.ghostp2.engine.bridges.BridgeSetup
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Servicio publicado en la red falsa (para inspección en pruebas). */
class FakeService(
    val onion: String,
    val virtualPort: Int,
    val localPort: Int,
    /** Claves x25519 públicas autorizadas; vacía ⇒ accesible para cualquiera (invitación). */
    val clientAuthPublicKeys: List<ByteArray>,
    internal val owner: FakeTor,
) {
    val requiresClientAuth: Boolean get() = clientAuthPublicKeys.isNotEmpty()

    override fun toString(): String =
        "FakeService(onion=$onion, virtualPort=$virtualPort, localPort=$localPort, auth=${clientAuthPublicKeys.size})"
}

/**
 * Red Tor simulada compartida por varias instancias de [FakeTor]. Las conexiones son sockets TCP
 * reales a `127.0.0.1:<puerto local>` del servicio publicado.
 *
 * - [setOnline] `(false)`: rechaza nuevas conexiones con `IOException` **y cierra todas las conexiones
 *   existentes** abiertas a través de cualquier [FakeTor] de esta red (simula una caída de red).
 * - [failNextConnects] `(n)`: los `n` próximos `connect` fallan con `IOException`.
 */
class FakeTorNetwork {
    private val services = MutableStateFlow<Map<String, FakeService>>(emptyMap())

    /** Servicios publicados actualmente, por .onion (sin sufijo, minúsculas). */
    val published: StateFlow<Map<String, FakeService>> = services.asStateFlow()

    @Volatile
    var online: Boolean = true
        private set

    private val pendingFailures = AtomicInteger(0)
    private val openSockets: MutableSet<Socket> = ConcurrentHashMap.newKeySet()

    fun setOnline(value: Boolean) {
        online = value
        if (!value) {
            openSockets.toList().forEach { runCatching { it.close() } }
            openSockets.clear()
        }
    }

    @Volatile private var connectGate: CountDownLatch? = null
    private val gatedConnects = AtomicInteger(0)

    /**
     * Los `n` próximos `connect` con la red disponible (de cualquier [FakeTor]) esperan a que hayan llegado los `n` y luego
     * siguen a la vez (como mucho 10 s). Sirve para forzar llamadas simultáneas.
     */
    fun gateNextConnects(n: Int) {
        require(n > 0)
        gatedConnects.set(n)
        connectGate = CountDownLatch(n)
    }

    /** Plazas de la puerta de [gateNextConnects] que aún no se han usado (0 = todas pasaron). */
    fun gateOpenSlots(): Int = gatedConnects.get().coerceAtLeast(0)

    /** Si hay una puerta activa y quedan plazas, se suma a ella y espera a los demás. */
    internal suspend fun passGate() {
        val gate = connectGate ?: return
        if (gatedConnects.getAndDecrement() <= 0) return
        gate.countDown()
        withContext(Dispatchers.IO) { gate.await(10, TimeUnit.SECONDS) }
    }

    fun failNextConnects(n: Int) {
        require(n >= 0) { "n no puede ser negativo" }
        pendingFailures.set(n)
    }

    /** Registra el servicio; si la .onion ya está publicada (por cualquier instancia) lanza `IOException`. */
    internal fun register(service: FakeService) {
        while (true) {
            val current = services.value
            if (current.containsKey(service.onion)) throw IOException("ya publicado")
            if (services.compareAndSet(current, current + (service.onion to service))) return
        }
    }

    /** Retira la .onion solo si la publicó `owner`; si no, no hace nada (como Tor con otro proceso). */
    internal fun unregister(onion: String, owner: FakeTor) {
        services.update { map -> if (map[onion]?.owner === owner) map - onion else map }
    }

    internal fun unregisterAll(owner: FakeTor) {
        services.update { map -> map.filterValues { it.owner !== owner } }
    }

    /** Consume un fallo programado, si lo hay. */
    internal fun consumeFailure(): Boolean {
        while (true) {
            val n = pendingFailures.get()
            if (n <= 0) return false
            if (pendingFailures.compareAndSet(n, n - 1)) return true
        }
    }

    internal fun track(socket: Socket) {
        openSockets.removeIf { it.isClosed }
        openSockets += socket
        // Si la red cayó durante la conexión, no dejar el socket vivo.
        if (!online) {
            openSockets -= socket
            socket.close()
            throw IOException("Red sin conexión")
        }
    }
}

/**
 * Implementación falsa de [TorTransport] sobre [FakeTorNetwork]. Modela la autorización de cliente v3:
 * un servicio con claves autorizadas solo acepta a quien haya llamado a [addClientAuth] con una clave
 * privada x25519 cuya pública esté en la lista. Las .onion son direcciones v3 válidas derivadas de una
 * clave ed25519; la `privateKey` publicada son 64 bytes = semilla ed25519(32) || pública(32). Ese formato
 * no es el del transporte real (`KmpTorTransport` usa la clave expandida de Tor, también de 64 bytes): el
 * motor la trata como opaca y solo la guarda y la devuelve tal cual, así que la diferencia no le afecta.
 * `publishService`, `addClientAuth` y `connect` exigen estado [TorState.Ready] (`IOException` si no), como
 * el contrato de [TorTransport]; `stop` retira los servicios publicados por esta instancia.
 * Publicar una .onion que ya está publicada lanza `IOException("ya publicado")`, y `unpublishService`
 * solo retira servicios publicados por esta misma instancia.
 *
 * Con [forgetsClientAuthOnStop] `= true`, `stop` (y `wipeState`) olvidan las autorizaciones de cliente,
 * como un Tor real (que solo las guarda en memoria); por defecto se conservan entre reinicios.
 *
 * Puentes (R30), como `KmpTorTransport`: con [bridges], cada `start` lee el ajuste (lo guarda en
 * [bridgeSetups]) y [bridged] dice si la ejecución va por puente; si la lectura lanza, `Error` sin puente.
 * [startFailure] hace terminar cada `start` en ese `Error`, [holdStarts] lo deja en `Starting(0)` hasta
 * [finishStart], y [calls] guarda el orden de `start`, `stop`, `wipeState` y `forgetBridgeState`. Con [stopGate] o
 * [forgetGate], `stop` o `forgetBridgeState` (ya anotados en [calls]) esperan a que se completen, como un Tor lento.
 */
class FakeTor(
    private val network: FakeTorNetwork,
    private val forgetsClientAuthOnStop: Boolean = false,
) : TorTransport {
    private val mutableState = MutableStateFlow<TorState>(TorState.Off)
    override val state: StateFlow<TorState> = mutableState.asStateFlow()

    private val clientAuth = ConcurrentHashMap<String, ByteArray>()

    /** Llamadas a [connect] hechas con esta instancia (con éxito o no), para pruebas de reintentos. */
    val connectAttempts = AtomicInteger(0)

    /** Llamadas a [wipeState]. */
    val wipeStateCalls = AtomicInteger(0)

    /** Llamadas a [forgetBridgeState]. */
    val forgetBridgeStateCalls = AtomicInteger(0)

    /** Orden de las llamadas: "start", "stop", "wipe" y "forget". */
    val calls = CopyOnWriteArrayList<String>()

    /** Ajuste de puentes que lee cada [start]; `null` = no se lee (como antes de R30). */
    @Volatile var bridges: (suspend () -> BridgeSetup?)? = null

    /** Lo que devolvió [bridges] en cada [start], en orden (`null` = sin puentes). */
    val bridgeSetups = CopyOnWriteArrayList<BridgeSetup?>()

    /** Si no es nulo, cada [start] termina en este `Error` en vez de `Ready`. */
    @Volatile var startFailure: TorState.Error? = null

    /** Con `true`, [start] deja el estado en `Starting(0)`; [finishStart] lo pasa a `Ready`. */
    @Volatile var holdStarts = false

    /** Si no es nulo, cada [stop] espera a que se complete (tras anotarse en [calls]). */
    @Volatile var stopGate: CompletableDeferred<Unit>? = null

    /** Si no es nulo, cada [forgetBridgeState] espera a que se complete (tras anotarse en [calls]). */
    @Volatile var forgetGate: CompletableDeferred<Unit>? = null

    @Volatile private var bridgedRun = false

    override val bridged: Boolean get() = bridgedRun

    /** Termina un arranque retenido por [holdStarts]. */
    fun finishStart() {
        if (mutableState.value is TorState.Starting) mutableState.value = TorState.Ready
    }

    /** .onion (sin sufijo) con autorización de cliente registrada ahora mismo. */
    fun clientAuthOnions(): Set<String> = clientAuth.keys.toSet()

    override suspend fun start() {
        calls += "start"
        mutableState.value = TorState.Starting(0)
        val supplier = bridges
        if (supplier != null) {
            val setup = try {
                supplier()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = TorState.Error("No se pudo leer el ajuste de puentes")
                return
            }
            bridgeSetups += setup
            bridgedRun = setup != null
        }
        startFailure?.let {
            mutableState.value = it
            return
        }
        if (!holdStarts) mutableState.value = TorState.Ready
    }

    override suspend fun stop() {
        calls += "stop"
        stopGate?.await()
        bridgedRun = false
        network.unregisterAll(this)
        if (forgetsClientAuthOnStop) clientAuth.clear()
        mutableState.value = TorState.Off
    }

    override suspend fun wipeState() {
        calls += "wipe"
        wipeStateCalls.incrementAndGet()
        forget()
    }

    override suspend fun forgetBridgeState() {
        calls += "forget"
        forgetBridgeStateCalls.incrementAndGet()
        forgetGate?.await()
        forget()
    }

    private fun forget() {
        bridgedRun = false
        network.unregisterAll(this)
        clientAuth.clear()
        mutableState.value = TorState.Off
    }

    override suspend fun publishService(
        privateKey: ByteArray?,
        clientAuthPublicKeys: List<ByteArray>,
        virtualPort: Int,
        localPort: Int,
    ): PublishedService {
        if (privateKey != null && clientAuthPublicKeys.isEmpty()) {
            throw IOException("La .onion permanente necesita autorización de cliente")
        }
        if (mutableState.value != TorState.Ready) throw IOException("Tor no está listo")
        val seed = if (privateKey == null) {
            Primitives.randomBytes(32)
        } else {
            require(privateKey.size == 64) { "La clave del servicio debe tener 64 bytes" }
            privateKey.copyOfRange(0, 32)
        }
        val publicKey = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
        val onion = onionFromPublicKey(publicKey)
        network.register(
            FakeService(onion, virtualPort, localPort, clientAuthPublicKeys.map { it.copyOf() }, this),
        )
        return PublishedService(onion, seed + publicKey)
    }

    override suspend fun unpublishService(onion: String) {
        network.unregister(OnionAddress.normalize(onion), this)
    }

    override suspend fun addClientAuth(onion: String, privateKey: ByteArray) {
        if (mutableState.value != TorState.Ready) throw IOException("Tor no está listo")
        require(privateKey.size == 32) { "La clave de autorización debe tener 32 bytes" }
        clientAuth[OnionAddress.normalize(onion)] = privateKey.copyOf()
    }

    override suspend fun removeClientAuth(onion: String) {
        clientAuth.remove(OnionAddress.normalize(onion))
    }

    override suspend fun connect(onion: String, port: Int, timeoutMillis: Long): Socket {
        connectAttempts.incrementAndGet()
        val target = OnionAddress.normalize(onion)
        if (mutableState.value != TorState.Ready) throw IOException("Tor no está listo")
        if (!network.online) throw IOException("Red sin conexión")
        network.passGate()
        if (network.consumeFailure()) throw IOException("Fallo de conexión simulado")
        val service = network.published.value[target] ?: throw IOException("Servicio oculto no disponible")
        if (service.virtualPort != port) throw IOException("Puerto virtual incorrecto")
        if (service.requiresClientAuth) {
            val priv = clientAuth[target] ?: throw IOException("Falta autorización de cliente")
            val pub = Primitives.x25519PublicFromPrivate(priv).publicKey
            if (service.clientAuthPublicKeys.none { it.contentEquals(pub) }) {
                throw IOException("Autorización de cliente rechazada")
            }
        }
        return withContext(Dispatchers.IO) {
            val socket = Socket()
            try {
                socket.connect(
                    InetSocketAddress(InetAddress.getLoopbackAddress(), service.localPort),
                    timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
                )
                network.track(socket)
            } catch (e: IOException) {
                runCatching { socket.close() }
                throw e
            }
            socket
        }
    }

    companion object {
        private val CHECKSUM_PREFIX = ".onion checksum".toByteArray(Charsets.US_ASCII)

        /** base32(pubkey(32) || checksum(2) || 0x03), checksum = SHA3-256(".onion checksum" || pubkey || 0x03)[0..2]. */
        fun onionFromPublicKey(publicKey: ByteArray): String {
            require(publicKey.size == 32)
            val version = byteArrayOf(3)
            val digest = SHA3Digest(256)
            digest.update(CHECKSUM_PREFIX, 0, CHECKSUM_PREFIX.size)
            digest.update(publicKey, 0, 32)
            digest.update(version, 0, 1)
            val h = ByteArray(32)
            digest.doFinal(h, 0)
            return Base32.encode(publicKey + h.copyOfRange(0, 2) + version)
        }
    }
}
