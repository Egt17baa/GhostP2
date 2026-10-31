package org.ghostp2.engine

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
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
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

    fun failNextConnects(n: Int) {
        require(n >= 0) { "n no puede ser negativo" }
        pendingFailures.set(n)
    }

    internal fun register(service: FakeService) {
        services.update { it + (service.onion to service) }
    }

    internal fun unregister(onion: String) {
        services.update { it - onion }
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
 * clave ed25519; la `privateKey` publicada son 64 bytes = semilla ed25519(32) || pública(32).
 * `connect` exige estado [TorState.Ready]; `stop` retira los servicios publicados por esta instancia.
 */
class FakeTor(private val network: FakeTorNetwork) : TorTransport {
    private val mutableState = MutableStateFlow<TorState>(TorState.Off)
    override val state: StateFlow<TorState> = mutableState.asStateFlow()

    private val clientAuth = ConcurrentHashMap<String, ByteArray>()

    override suspend fun start() {
        mutableState.value = TorState.Starting(0)
        mutableState.value = TorState.Ready
    }

    override suspend fun stop() {
        network.unregisterAll(this)
        mutableState.value = TorState.Off
    }

    override suspend fun publishService(
        privateKey: ByteArray?,
        clientAuthPublicKeys: List<ByteArray>,
        virtualPort: Int,
        localPort: Int,
    ): PublishedService {
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
        network.unregister(OnionAddress.normalize(onion))
    }

    override suspend fun addClientAuth(onion: String, privateKey: ByteArray) {
        require(privateKey.size == 32) { "La clave de autorización debe tener 32 bytes" }
        clientAuth[OnionAddress.normalize(onion)] = privateKey.copyOf()
    }

    override suspend fun removeClientAuth(onion: String) {
        clientAuth.remove(OnionAddress.normalize(onion))
    }

    override suspend fun connect(onion: String, port: Int, timeoutMillis: Long): Socket {
        val target = OnionAddress.normalize(onion)
        if (mutableState.value != TorState.Ready) throw IOException("Tor no está listo")
        if (!network.online) throw IOException("Red sin conexión")
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
