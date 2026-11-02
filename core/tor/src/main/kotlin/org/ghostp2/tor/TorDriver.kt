package org.ghostp2.tor

import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
import io.matthewnelson.kmp.tor.runtime.core.config.TorSetting
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.core.util.executeAsync
import io.matthewnelson.kmp.tor.runtime.core.util.toInetAddress
import java.io.File
import java.net.InetSocketAddress
import java.util.IdentityHashMap
import io.matthewnelson.kmp.tor.runtime.TorState as RuntimeState

/**
 * Lo mínimo de kmp-tor que usa [KmpTorTransport]. La implementación real ([KmpTorDriver]) es una capa
 * fina sobre `TorRuntime`; las pruebas usan un doble (kmp-tor no arranca en la JVM ni en Robolectric).
 */
internal interface TorDriver {
    /**
     * Arranca el proceso; vuelve tras conectar el control (el bootstrap sigue, ver `onState`). [bridges] se añade
     * a [applyGhostConfig] en este arranque (vacía = sin puentes, la configuración de siempre; ver
     * [bridgeSettings]).
     */
    suspend fun startDaemon(bridges: List<TorSetting>)

    /** Detiene el proceso (también interrumpe un arranque en curso). */
    suspend fun stopDaemon()

    suspend fun <T : Any> execute(cmd: TorCmd.Unprivileged<T>): T

    /** Primer puerto SOCKS abierto ahora mismo, o `null` (kmp-tor solo los informa con Tor listo). */
    fun socksAddress(): InetSocketAddress?
}

/**
 * Crea el `TorRuntime` en `torDir/work` y `torDir/cache` con [applyGhostConfig] y entrega los cambios de
 * estado a `onState`. Sin observadores de logs; el de `ERROR` es obligatorio en kmp-tor (sin él, una
 * `UncaughtException` tumba la app) y no registra nada.
 *
 * kmp-tor 2.6.0 guarda en memoria, para todo el proceso, el entorno por (`workDirectory`,
 * `cacheDirectory`) y el runtime por entorno: un segundo driver sobre el mismo `torDir` (p. ej. tras
 * [KmpTorTransport.wipeState]) recibe el mismo runtime. Los observadores se comparan por identidad, así
 * que se suscriben **una sola vez por runtime**; los drivers siguientes solo cambian el destino de los
 * eventos `STATE`.
 *
 * Los destinos son de **una sola instancia**: hay un único [StateSink] por runtime y solo el último
 * driver creado sobre un `torDir` recibe sus eventos `STATE` (el anterior deja de recibirlos). Encaja con
 * la regla de [KmpTorTransport] de una sola instancia por proceso; dos transportes vivos sobre el mismo
 * directorio se quitarían los eventos entre sí. El destino se fija antes de suscribirse, así que no se
 * pierde ningún evento emitido justo al suscribir.
 *
 * Por lo mismo, la lambda `config {}` del **primer** driver de un `torDir` es la que kmp-tor usa para siempre en
 * ese runtime (la vuelve a ejecutar en cada arranque). Por eso no captura nada del driver: lee los puentes de un
 * [BridgeHolder] compartido por `torDir`, que [startDaemon] fija antes de arrancar.
 */
internal class KmpTorDriver(torDir: File, onState: (RuntimeState) -> Unit) : TorDriver {
    private val runtime: TorRuntime
    private val bridgeHolder: BridgeHolder = synchronized(bridgeHolders) {
        bridgeHolders.getOrPut(torDir.absoluteFile) { BridgeHolder() }
    }

    init {
        val holder = bridgeHolder
        val environment = TorRuntime.Environment.Builder(
            workDirectory = File(torDir, "work"),
            cacheDirectory = File(torDir, "cache"),
            loader = ResourceLoaderTorExec::getOrCreate,
        ) {
            // Los observadores solo actualizan un StateFlow: no hace falta el hilo principal.
            defaultEventExecutor = OnEvent.Executor.Immediate
        }
        environment.debug = false
        runtime = TorRuntime.Builder(environment) {
            config { _ ->
                applyGhostConfig()
                putAll(holder.settings)
            }
        }
        synchronized(sinks) {
            val existing = sinks[runtime]
            if (existing != null) {
                existing.target = onState
            } else {
                // Destino fijado antes de suscribirse: un evento inmediato ya llega a onState.
                val sink = StateSink(onState)
                sinks[runtime] = sink
                runtime.subscribe(
                    RuntimeEvent.ERROR.observer(OBSERVER_TAG) { },
                    RuntimeEvent.STATE.observer(OBSERVER_TAG) { sink.target(it) },
                )
            }
        }
    }

    override suspend fun startDaemon(bridges: List<TorSetting>) {
        bridgeHolder.settings = bridges
        runtime.startDaemonAsync()
    }

    override suspend fun stopDaemon() {
        runtime.stopDaemonAsync()
    }

    override suspend fun <T : Any> execute(cmd: TorCmd.Unprivileged<T>): T = runtime.executeAsync(cmd)

    override fun socksAddress(): InetSocketAddress? =
        runtime.listeners().socks.firstOrNull()?.let { InetSocketAddress(it.address.toInetAddress(), it.port.value) }

    /** Destino actual de los eventos `STATE` de un runtime ya suscrito. */
    private class StateSink(@Volatile var target: (RuntimeState) -> Unit)

    /** Puentes del arranque actual de un `torDir` (vacía = sin puentes); la lee la lambda `config {}`. */
    private class BridgeHolder(@Volatile var settings: List<TorSetting> = emptyList())

    private companion object {
        const val OBSERVER_TAG = "ghostp2"

        /** Runtimes ya suscritos (kmp-tor no los libera nunca: tampoco hace falta liberarlos aquí). */
        val sinks = IdentityHashMap<TorRuntime, StateSink>()

        /** Un [BridgeHolder] por `torDir` (como el runtime de kmp-tor, vive lo que el proceso). */
        val bridgeHolders = HashMap<File, BridgeHolder>()
    }
}
