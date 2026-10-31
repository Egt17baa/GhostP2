package org.ghostp2.tor

import io.matthewnelson.kmp.tor.runtime.core.config.TorConfig
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.core.ctrl.builder.BuilderScopeOnionAdd
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3
import io.matthewnelson.kmp.tor.runtime.core.key.X25519
import io.matthewnelson.kmp.tor.runtime.core.net.OnionAddress
import io.matthewnelson.kmp.tor.runtime.core.net.Port.Companion.toPort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ghostp2.engine.TorState
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import io.matthewnelson.kmp.tor.runtime.TorState as RuntimeState

// Piezas puras de KmpTorTransport (probadas en la JVM sin un Tor real).

/** Mensaje de [TorState.Error] cuando el proceso de Tor se detiene sin que se haya pedido. */
internal const val UNEXPECTED_STOP = "Tor se ha detenido"

/**
 * Configuración propia (kmp-tor añade después sus valores mínimos: `DataDirectory`/`CacheDirectory` en
 * el entorno, autenticación por cookie, `__OwningControllerProcess`, `DisableNetwork` al arrancar y el
 * puerto de control como socket Unix en el directorio de trabajo, o `auto` si no se puede):
 *
 * - SOCKS en un puerto elegido por Tor (`auto`, en 127.0.0.1), solo para tráfico .onion (sin DNS ni
 *   salidas a Internet) y sin autenticación SOCKS aunque el cliente Java la ofrezca.
 * - `DNSPort` y `HTTPTunnelPort` desactivados (ya lo están por defecto en Tor; se fija explícitamente).
 * - Ni `ClientOnionAuthDir` ni `Log`: las autorizaciones no se guardan en disco y Tor no escribe
 *   registros (su salida estándar va a `/dev/null`); `SafeLogging` conserva su valor por defecto (1).
 */
internal fun TorConfig.BuilderScope.applyGhostConfig() {
    TorOption.__SocksPort.configure {
        auto()
        flagsSocks {
            OnionTrafficOnly = true
            PreferSOCKSNoAuth = true
        }
    }
    TorOption.DNSPort.configure { disable() }
    TorOption.HTTPTunnelPort.configure { disable() }
}

/**
 * Traduce el estado del runtime al de [TorTransport]. `processSeen`: ya hubo un proceso (o un intento
 * de arranque) en esta ejecución; un `Off`/`Stopping` después de eso, sin [KmpTorTransport.stop], es
 * un fallo. Devuelve `null` si no hay que cambiar nada (el `Off` previo al arranque).
 * Ojo: tras `Ready` puede volver a `Starting(100)` (red desactivada) con los servicios aún publicados;
 * por eso [KmpTorTransport] recuerda si ya hubo `Ready` en la ejecución para poder retirarlos.
 */
internal fun mapRuntimeState(
    daemon: RuntimeState.Daemon,
    network: RuntimeState.Network,
    processSeen: Boolean,
): TorState? = when {
    daemon.isOff || daemon.isStopping -> if (processSeen) TorState.Error(UNEXPECTED_STOP) else null
    daemon.isStarting -> TorState.Starting(0)
    daemon.isBootstrapped && network.isEnabled -> TorState.Ready
    else -> TorState.Starting(daemon.bootstrap.toInt().coerceIn(0, 100))
}

/**
 * `ADD_ONION` efímero `virtualPort → 127.0.0.1:localPort` (Tor usa 127.0.0.1 cuando el destino es
 * solo un puerto). Sin `key`: Tor genera una dirección nueva y devuelve su clave. Con `key`: la misma
 * dirección, con `DiscardPK` (Tor no la devuelve: ya la tenemos) y destrucción del objeto de clave al
 * terminar la orden. `V3Auth` + una clave por contacto si `clientAuth` no está vacía.
 */
internal fun onionAddCommand(
    key: ED25519_V3.PrivateKey?,
    clientAuth: List<X25519.PublicKey>,
    virtualPort: Int,
    localPort: Int,
): TorCmd.Onion.Add {
    val virtual = virtualPort.toPort()
    val local = localPort.toPort()
    val authorized = clientAuth.toList()
    fun BuilderScopeOnionAdd.configure() {
        port(virtual = virtual) { target(port = local) }
        authorized.forEach { clientAuth(it) }
    }
    return if (key == null) {
        TorCmd.Onion.Add.new(ED25519_V3) { configure() }
    } else {
        TorCmd.Onion.Add.existing(key) { configure() }
    }
}

/** `ONION_CLIENT_AUTH_ADD` sin `Permanent` (solo en memoria de Tor); destruye la clave al terminar. */
internal fun clientAuthAddCommand(address: OnionAddress.V3, key: X25519.PrivateKey): TorCmd.OnionClientAuth.Add =
    TorCmd.OnionClientAuth.Add(address, key)

/** Destino SOCKS sin resolver `"<onion>.onion":port`: Tor recibe el nombre; nunca hay DNS local. */
internal fun socksTarget(onion: String, port: Int): InetSocketAddress {
    val address = try {
        TorKeys.onionAddress(onion)
    } catch (e: IllegalArgumentException) {
        throw IOException("Dirección .onion no válida")
    }
    return InetSocketAddress.createUnresolved("${TorKeys.onionString(address)}.onion", port)
}

/**
 * Conecta a `onion:port` a través del proxy SOCKS5 `proxy` (Tor). E/S bloqueante en `Dispatchers.IO`,
 * con `timeoutMillis` para todo el `connect` (TCP al proxy y negociación SOCKS). Si se cancela la
 * corrutina mientras bloquea, se cierra el socket (eso desbloquea el hilo). Cualquier fallo sale como
 * [IOException] con un mensaje sin la dirección; el socket nunca queda abierto si no se devuelve.
 */
internal suspend fun connectThroughSocks(
    proxy: InetSocketAddress,
    onion: String,
    port: Int,
    timeoutMillis: Long,
): Socket {
    val target = socksTarget(onion, port)
    val timeout = timeoutMillis.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    val socket = Socket(Proxy(Proxy.Type.SOCKS, proxy))
    try {
        coroutineScope {
            val finished = AtomicBoolean(false)
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (!finished.get()) runCatching { socket.close() }
                }
            }
            try {
                withContext(Dispatchers.IO) { socket.connect(target, timeout) }
            } finally {
                finished.set(true)
                closer.cancel()
            }
        }
        return socket
    } catch (e: CancellationException) {
        runCatching { socket.close() }
        throw e
    } catch (e: Exception) {
        runCatching { socket.close() }
        // Cerrar el socket al cancelar hace fallar connect(): eso sigue siendo una cancelación.
        currentCoroutineContext().ensureActive()
        throw IOException("No se pudo conectar al servicio oculto (${e.javaClass.simpleName})")
    }
}
