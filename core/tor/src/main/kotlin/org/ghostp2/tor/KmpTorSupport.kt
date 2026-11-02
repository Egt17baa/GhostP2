package org.ghostp2.tor

import io.matthewnelson.kmp.tor.runtime.core.config.TorConfig
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.config.TorSetting
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
import org.ghostp2.engine.bridges.BridgeSetup
import org.ghostp2.engine.bridges.PtKind
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
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
 * Puentes (R30) sobre [applyGhostConfig]: `UseBridges 1`, una `Bridge <línea>` por puente (en el orden de [setup])
 * y `ClientTransportPlugin <transporte> socks5 127.0.0.1:<puerto>` por tipo, con el puerto del SOCKS local de
 * IPtProxy en [ports]. Con `UseBridges 1` Tor solo usa esos puentes como primer salto: nunca conecta directo.
 * Ver [bridgeSettings].
 */
internal fun TorConfig.BuilderScope.applyBridgeConfig(setup: BridgeSetup, ports: Map<PtKind, Int>) {
    putAll(bridgeSettings(setup, ports))
}

/**
 * Los ajustes de [applyBridgeConfig]. Falla cerrado con [IllegalArgumentException] si no hay puentes, si falta el
 * puerto de algún tipo o si un puerto no está entre 1 y 65535 (Tor nunca recibe una configuración de puentes a
 * medias).
 */
internal fun bridgeSettings(setup: BridgeSetup, ports: Map<PtKind, Int>): List<TorSetting> {
    require(setup.lines.isNotEmpty()) { "Sin puentes" }
    val plugins = setup.kinds.map { kind ->
        val port = requireNotNull(ports[kind]) { "Sin puerto para ${kind.torName}" }
        require(port in 1..65_535) { "Puerto no válido para ${kind.torName}" }
        torSetting(TorOption.ClientTransportPlugin, "${kind.torName} socks5 127.0.0.1:$port")
    }
    return listOf(torSetting(TorOption.UseBridges, "1")) +
        setup.lines.map { torSetting(TorOption.Bridge, it.line) } +
        plugins
}

/**
 * `TorSetting` de una opción sin constructor en kmp-tor. kmp-tor 2.6.0 conoce `UseBridges`, `Bridge` y
 * `ClientTransportPlugin` (`TorOption`) pero no ofrece ninguna forma pública de darles valor (su issue #626,
 * abierto: «There currently exists no way to configure bridges»), y su runtime no lee ningún torrc. Se usan, por
 * reflexión, sus mismas funciones internas `TorOption.toLineItem` (comprueba que el argumento no está vacío y es de
 * una sola línea) y `LineItem.toSetting`; kmp-tor manda luego la línea `opción argumento` a Tor en `LOADCONF`, como
 * las demás. Los nombres JVM son los de `runtime-core-jvm` 2.6.0 (fijada en `libs.versions.toml`); las reglas de R8
 * de `consumer-rules.pro` los conservan y `BridgeConfigTest` falla si cambian. Cualquier fallo es
 * [IllegalArgumentException] o [IllegalStateException] (y [KmpTorTransport] trata cualquier otro `Throwable` igual):
 * el arranque por puente falla cerrado.
 *
 * No se puede llamar a estas funciones desde un archivo Java (lo que haría que un cambio de nombre rompiera la
 * compilación): kmp-tor las marca `@JvmSynthetic` (`ACC_SYNTHETIC` en el bytecode, igual que los constructores
 * públicos de `TorSetting` y `LineItem`, y sus variantes `$default`), y javac no resuelve miembros sintéticos
 * («cannot find symbol»). Comprobado con javac contra `runtime-core-jvm` 2.6.0.
 */
private fun torSetting(option: TorOption, argument: String): TorSetting {
    val internals = KmpTorInternals.instance ?: throw IllegalStateException("kmp-tor sin soporte para ${option.name}")
    return try {
        val item = internals.toLineItem.invoke(internals.lineItemCompanion, option, argument, emptySet<String>())
        internals.toSetting.invoke(TorSetting.Companion, item, emptyMap<String, Any>()) as TorSetting
    } catch (e: InvocationTargetException) {
        throw (e.cause as? IllegalArgumentException) ?: IllegalStateException("kmp-tor rechazó ${option.name}")
    } catch (e: ReflectiveOperationException) {
        throw IllegalStateException("kmp-tor sin soporte para ${option.name}")
    }
}

/** Funciones internas de kmp-tor 2.6.0 que usa [torSetting]; se buscan una vez, la primera vez que hace falta. */
private class KmpTorInternals(val lineItemCompanion: Any, val toLineItem: Method, val toSetting: Method) {
    companion object {
        private const val SUFFIX = "\$io_matthewnelson_kmp_tor_runtime_core_jvm"

        /** `null` si esta versión de kmp-tor no las tiene (nunca con la 2.6.0 fijada). */
        val instance: KmpTorInternals? by lazy {
            try {
                val companion = TorSetting.LineItem::class.java.getField("Companion").get(null)
                    ?: return@lazy null
                KmpTorInternals(
                    companion,
                    companion.javaClass.getMethod(
                        "toLineItem$SUFFIX", TorOption::class.java, String::class.java, Set::class.java,
                    ),
                    TorSetting.Companion::class.java.getMethod(
                        "toSetting$SUFFIX", TorSetting.LineItem::class.java, Map::class.java,
                    ),
                )
            } catch (_: ReflectiveOperationException) {
                null
            }
        }
    }
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
