package org.ghostp2.engine

import kotlinx.coroutines.flow.StateFlow

/** Estado del proceso Tor. */
sealed interface TorState {
    data object Off : TorState
    data class Starting(val percent: Int) : TorState
    data object Ready : TorState
    data class Error(val message: String) : TorState
}

/**
 * Servicio oculto publicado. `onion` sin ".onion" y en minúsculas; `privateKey` es la clave
 * ED25519-V3 de Tor (64 bytes, opaca) que permite volver a publicar la misma dirección.
 * Igualdad por contenido; `toString` no muestra la clave.
 */
class PublishedService(val onion: String, val privateKey: ByteArray) {
    override fun equals(other: Any?): Boolean =
        other is PublishedService && onion == other.onion && privateKey.contentEquals(other.privateKey)

    override fun hashCode(): Int = 31 * onion.hashCode() + privateKey.contentHashCode()

    override fun toString(): String = "PublishedService(onion=$onion, clave privada oculta)"
}

/** Transporte sobre Tor: servicios ocultos v3 con autorización de cliente y conexiones salientes. */
interface TorTransport {
    val state: StateFlow<TorState>

    suspend fun start()

    suspend fun stop()

    /**
     * Publica un servicio oculto `virtualPort` → `127.0.0.1:localPort`. Con `privateKey == null` se genera
     * una dirección nueva; con clave se republica la misma. `clientAuthPublicKeys` vacía (sin autorización)
     * solo se usa, con `privateKey == null`, para el servicio temporal de invitación.
     */
    suspend fun publishService(
        privateKey: ByteArray?,
        clientAuthPublicKeys: List<ByteArray>,
        virtualPort: Int,
        localPort: Int,
    ): PublishedService

    suspend fun unpublishService(onion: String)

    /** Registra la clave x25519 privada de autorización de cliente para conectar a `onion`. */
    suspend fun addClientAuth(onion: String, privateKey: ByteArray)

    suspend fun removeClientAuth(onion: String)

    /** Conecta a `onion` (con o sin ".onion"); lanza [java.io.IOException] si no lo consigue. */
    suspend fun connect(onion: String, port: Int, timeoutMillis: Long): java.net.Socket
}
