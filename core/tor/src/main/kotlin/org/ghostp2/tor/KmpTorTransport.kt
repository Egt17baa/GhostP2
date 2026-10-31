package org.ghostp2.tor

import android.content.Context
import io.matthewnelson.kmp.tor.runtime.core.ctrl.Reply
import io.matthewnelson.kmp.tor.runtime.core.ctrl.TorCmd
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.ghostp2.engine.PublishedService
import org.ghostp2.engine.TorState
import org.ghostp2.engine.TorTransport
import java.io.File
import java.io.IOException
import java.net.Socket
import io.matthewnelson.kmp.tor.runtime.TorState as RuntimeState

/**
 * [TorTransport] real con kmp-tor (`runtime` + binario de `resource-exec-tor`, en un proceso aparte).
 *
 * - Estado de Tor en `noBackupFilesDir/tor` (`work/` y `cache/`): fuera de las copias de seguridad. En
 *   disco solo queda el estado interno de Tor (consenso, guardas, cookie de control): las claves de los
 *   servicios ocultos y de autorización de cliente van por el puerto de control y **nunca** se escriben
 *   (servicios efímeros de `ADD_ONION`, `ONION_CLIENT_AUTH_ADD` sin `Permanent`, sin `ClientOnionAuthDir`).
 * - Configuración: ver [applyGhostConfig] (SOCKS automático solo en 127.0.0.1 y solo para .onion; sin
 *   DNSPort ni HTTPTunnelPort; control por socket Unix privado o puerto automático).
 * - [state] se deriva de los eventos `STATE` del runtime (porcentaje de bootstrap, ver [mapRuntimeState]);
 *   si el proceso de Tor muere sin un [stop], pasa a `Error` y se queda ahí hasta [stop]/[start].
 * - Registros: no se suscribe ningún observador de logs ni de eventos de Tor, el modo depuración de
 *   kmp-tor está desactivado y la salida del proceso va a `/dev/null`; `SafeLogging` queda en su valor
 *   por defecto (activado). Ningún mensaje de error de esta clase incluye claves ni direcciones.
 *
 * Debe haber **una sola instancia por proceso** (kmp-tor comparte el runtime por directorio de trabajo).
 * Sus corrutinas propias cuelgan de un `SupervisorJob() + Dispatchers.Default` (regla R7); [stop] espera
 * a la de arranque, así que no queda ninguna viva después.
 */
class KmpTorTransport internal constructor(
    internal val torDir: File,
    private val driverFactory: (onState: (RuntimeState) -> Unit) -> TorDriver,
) : TorTransport {

    internal constructor(torDir: File) : this(torDir, { onState -> KmpTorDriver(torDir, onState) })

    constructor(context: Context) : this(File(context.applicationContext.noBackupFilesDir, TOR_DIR))

    private val mutableState = MutableStateFlow<TorState>(TorState.Off)
    override val state: StateFlow<TorState> = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Serializa [start], [stop] y [wipeState]. */
    private val lifecycle = Mutex()

    /** Protege [running], [run] y [processSeen] frente al hilo de eventos de kmp-tor. */
    private val lock = Any()
    private var running = false
    private var run = 0L
    private var processSeen = false

    @Volatile private var driver: TorDriver? = null
    private var startJob: Job? = null

    override suspend fun start() {
        lifecycle.withLock {
            if (running && mutableState.value !is TorState.Error) return
            if (running) stopLocked() // desde Error: limpiar el intento anterior y volver a empezar
            val myRun = synchronized(lock) {
                running = true
                processSeen = false
                mutableState.value = TorState.Starting(0) // R13: nunca Off al volver
                ++run
            }
            val d = try {
                driver ?: withContext(Dispatchers.IO) { driverFactory(::onRuntimeState) }.also { driver = it }
            } catch (e: Exception) {
                failRun(myRun)
                return
            }
            startJob = scope.launch {
                try {
                    d.startDaemon()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    failRun(myRun)
                }
            }
        }
    }

    override suspend fun stop() {
        withContext(NonCancellable) { lifecycle.withLock { stopLocked() } }
    }

    override suspend fun wipeState() {
        withContext(NonCancellable) {
            lifecycle.withLock {
                stopLocked()
                val deleted = withContext(Dispatchers.IO) { !torDir.exists() || torDir.deleteRecursively() }
                if (!deleted) throw IOException("No se pudo borrar el estado de Tor")
            }
        }
    }

    override suspend fun publishService(
        privateKey: ByteArray?,
        clientAuthPublicKeys: List<ByteArray>,
        virtualPort: Int,
        localPort: Int,
    ): PublishedService {
        val d = awaitReady()
        val authKeys = keysOrIOException { clientAuthPublicKeys.map { TorKeys.x25519PublicKey(it) } }
        // La clave se genera aquí también para una dirección nueva: así siempre sabemos qué dirección
        // retirar si la orden queda en duda, y la devolvemos sin depender de la respuesta de Tor.
        val key = keysOrIOException { privateKey?.let { TorKeys.privateKey(it) } ?: ED25519_V3.PrivateKey.generate() }
        val bytes: ByteArray
        val publicKey: ED25519_V3.PublicKey
        try {
            bytes = TorKeys.privateKeyBytes(key) // array nuevo, nunca el de entrada
            publicKey = key.generatePublicKey()
        } catch (e: Exception) {
            key.destroy()
            throw IOException("Clave del servicio no válida")
        }
        val onion = TorKeys.onionString(publicKey.address())
        // La orden se completa aunque se cancele al llamante: así nunca queda un servicio publicado del
        // que nadie sabe (en particular, el de invitación sin autorización).
        try {
            withContext(NonCancellable) {
                try {
                    d.command(onionAddCommand(key, authKeys, virtualPort, localPort))
                } catch (e: AmbiguousTorException) {
                    // Puede que Tor lo haya publicado (p. ej. respondió tarde): se retira por si acaso.
                    runCatching { d.command(TorCmd.Onion.Delete(publicKey)) }
                    throw e
                }
            }
        } catch (e: Exception) {
            bytes.fill(0)
            throw e
        } finally {
            key.destroy()
        }
        if (!currentCoroutineContext().isActive) {
            withContext(NonCancellable) { runCatching { d.command(TorCmd.Onion.Delete(publicKey)) } }
            bytes.fill(0)
            throw CancellationException("Publicación cancelada")
        }
        return PublishedService(onion, bytes)
    }

    override suspend fun unpublishService(onion: String) {
        val d = driverIfReady() ?: return
        d.command(TorCmd.Onion.Delete(addressOrIOException(onion)))
    }

    override suspend fun addClientAuth(onion: String, privateKey: ByteArray) {
        val d = awaitReady()
        val address = addressOrIOException(onion)
        val cmd = clientAuthAddCommand(address, keysOrIOException { TorKeys.x25519PrivateKey(privateKey) })
        try {
            d.command(cmd)
        } finally {
            cmd.key.destroy()
        }
    }

    override suspend fun removeClientAuth(onion: String) {
        val d = driverIfReady() ?: return
        d.command(TorCmd.OnionClientAuth.Remove(addressOrIOException(onion)))
    }

    override suspend fun connect(onion: String, port: Int, timeoutMillis: Long): Socket {
        val d = driver
        if (mutableState.value != TorState.Ready || d == null) throw IOException("Tor no está listo")
        val proxy = d.socksAddress() ?: throw IOException("Tor no tiene puerto SOCKS")
        if (proxy.address?.isLoopbackAddress != true) throw IOException("Puerto SOCKS fuera de 127.0.0.1")
        return connectThroughSocks(proxy, onion, port, timeoutMillis)
    }

    // --- Interno ---

    private fun onRuntimeState(s: RuntimeState) {
        synchronized(lock) {
            if (!running || mutableState.value is TorState.Error) return
            if (s.daemon.isStarting || s.daemon.isOn) processSeen = true
            mapRuntimeState(s.daemon, s.network, processSeen)?.let { mutableState.value = it }
        }
    }

    private fun failRun(myRun: Long) {
        synchronized(lock) {
            if (running && run == myRun) mutableState.value = TorState.Error(START_FAILED)
        }
    }

    private suspend fun stopLocked() {
        synchronized(lock) {
            running = false
            run++
        }
        // Interrumpe también un arranque en curso. Acotado: un Tor colgado no bloquea al motor.
        driver?.let { d -> withTimeoutOrNull(STOP_TIMEOUT_MILLIS) { runCatching { d.stopDaemon() } } }
        startJob?.cancelAndJoin()
        startJob = null
        mutableState.value = TorState.Off
    }

    /** Espera a `Ready` mientras Tor arranca; `IOException` si está o pasa a `Off`/`Error`. */
    private suspend fun awaitReady(): TorDriver {
        val s = state.first { it !is TorState.Starting }
        return driver.takeIf { s == TorState.Ready } ?: throw IOException("Tor no está listo")
    }

    /**
     * Driver para retirar algo, solo con Tor `Ready`. En cualquier otro estado no hay nada que retirar:
     * un Tor parado o caído no guarda servicios ni autorizaciones, y uno que está arrancando tampoco
     * (todo se registra a partir de `Ready`). Por eso no se espera.
     */
    private fun driverIfReady(): TorDriver? = driver.takeIf { state.value == TorState.Ready }

    private inline fun <T> keysOrIOException(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw IOException("Clave no válida")
    }

    private fun addressOrIOException(onion: String) = try {
        TorKeys.onionAddress(onion)
    } catch (e: IllegalArgumentException) {
        throw IOException("Dirección .onion no válida")
    }

    /**
     * Ejecuta la orden en el puerto de control con un tiempo máximo. Los fallos de Tor salen como
     * [IOException] con un mensaje propio (la respuesta de Tor puede contener la dirección).
     */
    private suspend fun <T : Any> TorDriver.command(cmd: TorCmd.Unprivileged<T>): T = try {
        withTimeout(COMMAND_TIMEOUT_MILLIS) { execute(cmd) }
    } catch (e: TimeoutCancellationException) {
        throw AmbiguousTorException("Tor no respondió a ${cmd.keyword}")
    } catch (e: CancellationException) {
        // kmp-tor también cancela órdenes (p. ej. al detenerse): solo se propaga si nos cancelaron a nosotros.
        currentCoroutineContext().let { if (!it.isActive) throw e }
        throw AmbiguousTorException("Orden ${cmd.keyword} interrumpida")
    } catch (e: Reply.Error) {
        throw IOException("Tor rechazó ${cmd.keyword}") // respuesta de error definitiva de Tor
    } catch (e: Exception) {
        throw AmbiguousTorException("Fallo en ${cmd.keyword} (${e.javaClass.simpleName})")
    }

    private companion object {
        const val TOR_DIR = "tor"
        const val START_FAILED = "No se pudo arrancar Tor"
        const val STOP_TIMEOUT_MILLIS = 15_000L
        const val COMMAND_TIMEOUT_MILLIS = 30_000L
    }
}

/** La orden de control falló sin respuesta definitiva de Tor (tiempo agotado, interrupción, conexión caída). */
internal class AmbiguousTorException(message: String) : IOException(message)
