package org.ghostp2.tor

import io.matthewnelson.kmp.tor.resource.exec.tor.ResourceLoaderTorExec
import io.matthewnelson.kmp.tor.runtime.Action.Companion.startDaemonAsync
import io.matthewnelson.kmp.tor.runtime.Action.Companion.stopDaemonAsync
import io.matthewnelson.kmp.tor.runtime.RuntimeEvent
import io.matthewnelson.kmp.tor.runtime.TorRuntime
import io.matthewnelson.kmp.tor.runtime.core.OnEvent
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
    /** Arranca el proceso; vuelve tras conectar el control (el bootstrap sigue, ver `onState`). */
    suspend fun startDaemon()

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
 */
internal class KmpTorDriver(torDir: File, onState: (RuntimeState) -> Unit) : TorDriver {
    private val runtime: TorRuntime

    init {
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
            config { _ -> applyGhostConfig() }
        }
        val sink = synchronized(sinks) {
            sinks.getOrPut(runtime) {
                StateSink().also { sink ->
                    runtime.subscribe(
                        RuntimeEvent.ERROR.observer(OBSERVER_TAG) { },
                        RuntimeEvent.STATE.observer(OBSERVER_TAG) { sink.target(it) },
                    )
                }
            }
        }
        sink.target = onState
    }

    override suspend fun startDaemon() {
        runtime.startDaemonAsync()
    }

    override suspend fun stopDaemon() {
        runtime.stopDaemonAsync()
    }

    override suspend fun <T : Any> execute(cmd: TorCmd.Unprivileged<T>): T = runtime.executeAsync(cmd)

    override fun socksAddress(): InetSocketAddress? =
        runtime.listeners().socks.firstOrNull()?.let { InetSocketAddress(it.address.toInetAddress(), it.port.value) }

    /** Destino actual de los eventos `STATE` de un runtime ya suscrito. */
    private class StateSink {
        @Volatile var target: (RuntimeState) -> Unit = {}
    }

    private companion object {
        const val OBSERVER_TAG = "ghostp2"

        /** Runtimes ya suscritos (kmp-tor no los libera nunca: tampoco hace falta liberarlos aquí). */
        val sinks = IdentityHashMap<TorRuntime, StateSink>()
    }
}
