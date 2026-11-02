package org.ghostp2.engine

import kotlinx.coroutines.flow.StateFlow

/** Estado del proceso Tor. */
sealed interface TorState {
    data object Off : TorState
    data class Starting(val percent: Int) : TorState
    data object Ready : TorState
    /**
     * Fallo de Tor. [stuck]: un Tor anterior no se detuvo y sigue sin responder; no se arrancará otro
     * hasta reiniciar el proceso de la app (el motor lo muestra como [EngineErrorKind.TOR_STUCK]).
     * [bridge]: la ejecución iba por puente (R30) y falló (el motor lo muestra como
     * [EngineErrorKind.BRIDGE_FAILED], salvo con [stuck]); nunca se reintenta sin puente.
     */
    data class Error(val message: String, val stuck: Boolean = false, val bridge: Boolean = false) : TorState
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

/**
 * Transporte sobre Tor: servicios ocultos v3 con autorización de cliente y conexiones salientes.
 *
 * Ciclo de vida: `Off → start() → Starting(p) → Ready`, o `Error(mensaje)` si Tor no arranca o su
 * proceso muere; `stop()` vuelve a `Off` y se puede volver a llamar a [start]. Al detenerse, Tor
 * olvida los servicios publicados **y las autorizaciones de cliente** registradas con [addClientAuth]
 * (nunca se guardan en disco): quien lo use debe volver a registrarlas tras cada [start].
 */
interface TorTransport {
    val state: StateFlow<TorState>

    /**
     * La ejecución actual (desde el último [start] hasta el [stop]) va por puente (R30). El motor lo usa para
     * dar [EngineErrorKind.BRIDGE_FAILED] también cuando el arranque agota su plazo. Sin puentes, `false`.
     */
    val bridged: Boolean get() = false

    /**
     * Arranca Tor en segundo plano. Contrato (regla R13): al volver, [state] es `Starting`, `Ready` o
     * `Error`, **nunca** `Off` (se fija `Starting(0)` antes de volver). El arranque sigue después; el
     * llamante espera `Ready`/`Error` en [state]. Idempotente mientras está en marcha; desde `Error`
     * vuelve a intentarlo.
     */
    suspend fun start()

    /** Detiene Tor (idempotente); al volver, [state] es `Off`. Retira servicios y autorizaciones. */
    suspend fun stop()

    /**
     * Publica un servicio oculto `virtualPort` → `127.0.0.1:localPort`. Con `privateKey == null` se genera
     * una dirección nueva; con clave se republica la misma. `clientAuthPublicKeys` vacía (sin autorización)
     * solo se usa, con `privateKey == null`, para el servicio temporal de invitación: con `privateKey` y
     * sin claves de autorización lanza [java.io.IOException] sin publicar nada.
     *
     * Requiere `Ready`: mientras Tor arranca, espera a `Ready`; si está `Off` o en `Error` (o lo pasa a
     * estar mientras espera), lanza [java.io.IOException]. Nunca publica durante el arranque. Una clave
     * mal formada (de servicio o de autorización) también es [java.io.IOException].
     *
     * La `privateKey` del [PublishedService] devuelto es **siempre un array nuevo**: nunca el array de
     * entrada, y la implementación no guarda ninguna referencia a él. El llamante es su único dueño y
     * puede rellenarlo con ceros cuando quiera.
     */
    suspend fun publishService(
        privateKey: ByteArray?,
        clientAuthPublicKeys: List<ByteArray>,
        virtualPort: Int,
        localPort: Int,
    ): PublishedService

    /**
     * Retira el servicio, sin esperar nunca. Con Tor `Off`/`Error`, o antes del primer `Ready` de esta
     * ejecución, no hace nada (no hay servicios: se publican a partir de `Ready`). Tras ese `Ready` actúa
     * aunque el estado haya vuelto a `Starting` (p. ej. red desactivada), porque Tor los conserva.
     */
    suspend fun unpublishService(onion: String)

    /**
     * Registra, solo en memoria de Tor, la clave x25519 privada de autorización de cliente para conectar a
     * `onion`. Como [publishService], espera a `Ready` si Tor está arrancando y lanza
     * [java.io.IOException] con Tor `Off`/`Error` o con una clave o dirección mal formadas. Se pierde
     * al detener Tor (ver [stop]).
     */
    suspend fun addClientAuth(onion: String, privateKey: ByteArray)

    /**
     * Quita la autorización de cliente de `onion`; mismas reglas que [unpublishService] (nunca espera;
     * no hace nada con Tor `Off`/`Error` ni antes del primer `Ready` de esta ejecución).
     */
    suspend fun removeClientAuth(onion: String)

    /**
     * Conecta a `onion` (con o sin ".onion") por el puerto SOCKS de Tor, sin resolver nombres localmente;
     * lanza [java.io.IOException] si no lo consigue en `timeoutMillis` o si Tor no está `Ready` (no espera
     * al arranque). Si se cancela la corrutina, el socket se cierra.
     */
    suspend fun connect(onion: String, port: Int, timeoutMillis: Long): java.net.Socket

    /**
     * Borra todo el estado de Tor en disco (caché, guardas, cookie de control…), deteniendo Tor antes si
     * está en marcha. Para "Borrar todo". Al volver, [state] es `Off`; un [start] posterior empieza de cero.
     * Lanza [java.io.IOException] si no se pudo borrar.
     */
    suspend fun wipeState()

    /**
     * Borra el estado de Tor en disco igual que [wipeState] (también el de los transportes de los puentes).
     * Para el cambio del ajuste «Puentes» (R30): el arranque siguiente empieza de cero con el ajuste nuevo.
     */
    suspend fun forgetBridgeState() = wipeState()
}
