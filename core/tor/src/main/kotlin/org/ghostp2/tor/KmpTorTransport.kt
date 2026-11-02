package org.ghostp2.tor

import android.content.Context
import io.matthewnelson.kmp.tor.runtime.core.config.TorOption
import io.matthewnelson.kmp.tor.runtime.core.config.TorSetting
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
import kotlinx.coroutines.flow.firstOrNull
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
import org.ghostp2.engine.bridges.BridgeSetup
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
 * Si Tor no se detiene en el plazo de [stop] (15 s), o `stopDaemon` lanza y el runtime no está en `Off`, la
 * instancia queda "envenenada" ([poisoned]): [wipeState] lanza y cada [start] reintenta una vez detenerlo; si
 * tampoco, `Error` con `stuck`.
 *
 * Puentes (R30): en cada [start], `bridges()` da el ajuste guardado. Con `null` («No») todo sigue como antes: ni
 * IPtProxy ni archivos nuevos. Con puentes:
 * - antes de arrancar Tor se crea el marcador `noBackupFilesDir/tor-bridged`, se arrancan los transportes
 *   ([PtController], uno por arranque, en `noBackupFilesDir/pt`) y se añade [applyBridgeConfig]. Nunca se
 *   arranca Tor sin puente: sin líneas o si falla un transporte (cualquier `Throwable`, también un
 *   `LinkageError` de JNI), `Error` con `bridge` sin llegar a arrancarlo y con los transportes parados;
 * - con Tor ya arrancado, `GETCONF UseBridges` debe dar `1` antes de publicar `Ready` (hasta entonces,
 *   `Starting(100)`). Cualquier otra respuesta, o un fallo de la orden, es `Error` con `bridge` y se detiene Tor
 *   en el acto: así nunca se usa un Tor que kmp-tor no reconfiguró (p. ej. uno anterior aún enganchado al runtime);
 * - cualquier `Error` de esa ejecución lleva `bridge` (salvo `stuck`), también un fallo del transporte con Tor
 *   en marcha ([PtController.failures]);
 * - cada parada para Tor y después los transportes, y borra `torDir` y el estado de IPtProxy; luego quita el
 *   marcador. Con Tor atascado borra lo que puede y deja el marcador: el siguiente [start] (con cualquier ajuste)
 *   ve el marcador, como tras la muerte del proceso, y lo borra todo antes de arrancar.
 * Ningún mensaje de error nombra el puente. Si no se puede leer el ajuste, o con «No» no se puede borrar lo que dejó
 * un arranque por puente, es un `Error` de Tor sin `bridge`: no se arranca, pero el puente no tiene la culpa. Si se
 * cancela un [start] a medias, se paran los transportes, el estado queda en `Error` (nunca en `Starting`) y la
 * cancelación se relanza.
 *
 * Debe haber **una sola instancia por proceso** (kmp-tor comparte el runtime por directorio de trabajo).
 * Sus corrutinas propias cuelgan de un `SupervisorJob() + Dispatchers.Default` (regla R7); [stop] espera
 * a la de arranque, así que no queda ninguna viva después.
 */
class KmpTorTransport internal constructor(
    internal val torDir: File,
    /** Marcador de arranque por puente (R30): si existe al arrancar, el proceso murió con puente activo. */
    internal val markerFile: File,
    /** `stateDir` de IPtProxy. */
    internal val ptStateDir: File,
    private val stopTimeoutMillis: Long,
    private val bridges: suspend () -> BridgeSetup?,
    private val ptFactory: (stateDir: File) -> PtController,
    private val driverFactory: (onState: (RuntimeState) -> Unit) -> TorDriver,
) : TorTransport {

    /** Sin puentes (pruebas): marcador y estado de IPtProxy junto a [torDir]. */
    internal constructor(
        torDir: File,
        stopTimeoutMillis: Long,
        driverFactory: (onState: (RuntimeState) -> Unit) -> TorDriver,
    ) : this(
        torDir,
        File(torDir.absoluteFile.parentFile, MARKER_FILE),
        File(torDir.absoluteFile.parentFile, PT_DIR),
        stopTimeoutMillis,
        { null },
        ::IptProxyController,
        driverFactory,
    )

    private constructor(base: File, bridges: suspend () -> BridgeSetup?) : this(
        File(base, TOR_DIR),
        File(base, MARKER_FILE),
        File(base, PT_DIR),
        STOP_TIMEOUT_MILLIS,
        bridges,
        ::IptProxyController,
        { onState -> KmpTorDriver(File(base, TOR_DIR), onState) },
    )

    /**
     * Producción, en `noBackupFilesDir`. [bridges] da el ajuste «Puentes» guardado en cada [start]; `null` = sin
     * puentes. Un `BridgeSetup` vacío falla cerrado (error de puente, sin arrancar Tor).
     */
    constructor(context: Context, bridges: suspend () -> BridgeSetup?) :
        this(context.applicationContext.noBackupFilesDir, bridges)

    private val mutableState = MutableStateFlow<TorState>(TorState.Off)
    override val state: StateFlow<TorState> = mutableState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Serializa [start], [stop] y [wipeState]. */
    private val lifecycle = Mutex()

    /**
     * Protege [running], [run], [processSeen], [readyThisRun], [bridgeCheckPending] y [runtimeReady] frente al hilo
     * de eventos de kmp-tor.
     */
    private val lock = Any()
    private var running = false
    private var run = 0L
    private var processSeen = false

    /** Ejecución por puente que aún no confirmó `UseBridges 1`: el `Ready` del runtime se muestra como `Starting(100)`. */
    private var bridgeCheckPending = false

    /** El último estado del runtime en esta ejecución era `Ready` (aunque se muestre `Starting(100)`). */
    private var runtimeReady = false

    /** Ya hubo `Ready` en esta ejecución: desde entonces Tor puede tener servicios y autorizaciones. */
    @Volatile private var readyThisRun = false

    @Volatile private var driver: TorDriver? = null
    private var startJob: Job? = null

    /** La ejecución actual va por puente; se fija en [start] y se borra en [stopLocked]. */
    @Volatile private var bridgedRun = false
    override val bridged: Boolean get() = bridgedRun

    /** Transportes de la ejecución actual (o `null`) y la corrutina que vigila sus fallos. */
    private var pt: PtController? = null
    private var ptWatch: Job? = null

    /**
     * "Envenenado": un `stopDaemon` agotó su plazo, o lanzó sin que el runtime quedara en `Off`, y el proceso de
     * Tor puede seguir vivo. Mientras dure, no se arranca otro Tor (ni se reutiliza ese) y [wipeState] informa de
     * fallo. Cada [start] reintenta una vez detenerlo con el mismo plazo: si termina, se arranca con normalidad; si
     * no, el estado queda en `Error` con `stuck` (la interfaz pide cerrar y volver a abrir la app). Solo vive en
     * memoria. También lo fija la corrutina de arranque si no puede detener un Tor sin `UseBridges 1`.
     */
    @Volatile private var poisoned = false

    override suspend fun start() {
        lifecycle.withLock {
            if (running && mutableState.value !is TorState.Error) return
            if (running) stopLocked() // desde Error: limpiar el intento anterior y volver a empezar
            if (poisoned && !recoverLocked()) return
            val myRun = synchronized(lock) {
                running = true
                processSeen = false
                readyThisRun = false
                bridgedRun = false
                bridgeCheckPending = false
                runtimeReady = false
                mutableState.value = TorState.Starting(0) // R13: nunca Off al volver
                ++run
            }
            try {
                startRunLocked(myRun)
            } catch (e: CancellationException) {
                // Cancelado a mitad: sin transportes escuchando y sin quedarse en Starting(0) (un start() posterior
                // parte de Error y limpia el intento). La cancelación se relanza siempre.
                abortBridgesLocked()
                failRun(myRun, START_CANCELLED)
                throw e
            } catch (e: Throwable) {
                abortBridgesLocked()
                failRun(myRun)
            }
        }
    }

    /** El cuerpo de [start] tras fijar `Starting(0)`. Cualquier fallo termina en `Error` (ver [start]). */
    private suspend fun startRunLocked(myRun: Long) {
        val setup = try {
            bridges()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Sin saber el ajuste no se arranca (podría ser con puente), pero no es un fallo del puente.
            failRun(myRun, BRIDGES_UNREADABLE, bridge = false)
            return
        }
        // R30: el proceso murió (o Tor se atascó) con un arranque por puente: se borra todo antes de seguir.
        if (withContext(Dispatchers.IO) { markerFile.exists() }) {
            driver = null // como en wipeState: torDir va a desaparecer
            if (!withContext(Dispatchers.IO) { wipeDisk(removeMarker = true) }) {
                // Con «No» es un fallo de Tor (su estado en disco), no del puente.
                failRun(myRun, WIPE_FAILED, bridge = setup != null)
                return
            }
        }
        val bridgeConfig = if (setup == null) emptyList() else startBridgesLocked(myRun, setup) ?: return
        val d = try {
            withContext(Dispatchers.IO) { obtainDriver() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            abortBridgesLocked()
            failRun(myRun)
            return
        }
        startJob = scope.launch {
            try {
                d.startDaemon(bridgeConfig)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failRun(myRun)
                return@launch
            }
            if (setup != null) verifyBridgesApplied(myRun, d)
        }
    }

    /**
     * Parte de [start] con puente, antes de arrancar Tor: marcador, transportes y configuración. Devuelve la
     * configuración de puentes, o `null` si falló (el estado ya es `Error` con `bridge` y Tor no se arranca).
     */
    private suspend fun startBridgesLocked(myRun: Long, setup: BridgeSetup): List<TorSetting>? {
        synchronized(lock) {
            bridgedRun = true
            bridgeCheckPending = true
        }
        if (setup.lines.isEmpty()) {
            failRun(myRun, NO_BRIDGES) // «Mis puentes» sin líneas válidas: falla cerrado
            return null
        }
        val marked = withContext(Dispatchers.IO) {
            try {
                markerFile.parentFile?.mkdirs()
                markerFile.exists() || markerFile.createNewFile()
            } catch (e: Exception) {
                false
            }
        }
        if (!marked) {
            failRun(myRun, START_FAILED)
            return null
        }
        val controller: PtController
        val settings = try {
            controller = ptFactory(ptStateDir)
            pt = controller
            bridgeSettings(setup, controller.start(setup.kinds))
        } catch (e: CancellationException) {
            stopPt()
            throw e
        } catch (e: Throwable) {
            // También LinkageError (libgojni.so) o un fallo de kmp-tor al crear los ajustes: falla cerrado.
            stopPt()
            failRun(myRun, PT_FAILED)
            return null
        }
        ptWatch = scope.launch {
            if (controller.failures.firstOrNull() != null) failRun(myRun, PT_LOST)
        }
        return settings
    }

    /**
     * Tras arrancar Tor por puente (en la corrutina de arranque): `GETCONF UseBridges` debe ser `1`. Si lo es, deja
     * pasar `Ready`. Si no (o la orden falla), `Error` con `bridge` y detiene Tor ya, sin esperar al [stop] del motor;
     * si no se detiene, envenena (el siguiente [start] no lo reutiliza).
     */
    private suspend fun verifyBridgesApplied(myRun: Long, d: TorDriver) {
        val applied = try {
            val entries = d.command(TorCmd.Config.Get(TorOption.UseBridges))
            entries.isNotEmpty() && entries.all { it.option == TorOption.UseBridges && it.setting == "1" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            false
        }
        if (applied) {
            synchronized(lock) {
                if (!running || run != myRun || mutableState.value is TorState.Error) return
                bridgeCheckPending = false
                if (runtimeReady) {
                    readyThisRun = true
                    mutableState.value = TorState.Ready
                }
            }
            return
        }
        if (!failRun(myRun, BRIDGES_NOT_APPLIED, bridge = true)) return // ya parado o en otro intento
        if (!stopDaemonVerified(d)) poisoned = true
    }

    /** Para los transportes y su vigilancia de un arranque que no sigue adelante (no lanza). */
    private fun abortBridgesLocked() {
        ptWatch?.cancel()
        ptWatch = null
        stopPt()
    }

    override suspend fun stop() {
        withContext(NonCancellable) { lifecycle.withLock { stopLocked() } }
    }

    override suspend fun wipeState() {
        withContext(NonCancellable) {
            lifecycle.withLock {
                stopLocked()
                // El siguiente start() vuelve a pedir el driver a la fábrica, con torDir recién vacío.
                // kmp-tor conserva el entorno y el runtime por directorio, pero en cada arranque vuelve
                // a crear los directorios, a escribir geoip si falta y a generar la configuración.
                driver = null
                // Se borra aunque Tor no se haya detenido (lo que pueda), pero entonces se informa del fallo:
                // un Tor aún vivo podría volver a escribir parte de su estado (y el marcador se queda, R30).
                val deleted = withContext(Dispatchers.IO) { wipeDisk(removeMarker = !poisoned) }
                if (poisoned) throw IOException(STOP_TIMED_OUT)
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
        // Regla global: una dirección con clave (la .onion permanente) nunca se publica sin autorización
        // de cliente. Sin clave y sin autorización solo se usa para el servicio temporal de la invitación.
        if (privateKey != null && clientAuthPublicKeys.isEmpty()) {
            throw IOException("La .onion permanente necesita autorización de cliente")
        }
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
        val d = driverForRemoval() ?: return
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
        val d = driverForRemoval() ?: return
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
            mapRuntimeState(s.daemon, s.network, processSeen)?.let {
                runtimeReady = it == TorState.Ready
                val shown = when {
                    it == TorState.Ready && bridgeCheckPending -> TorState.Starting(100) // falta GETCONF UseBridges
                    it is TorState.Error && bridgedRun -> it.copy(bridge = true)
                    else -> it
                }
                if (shown == TorState.Ready) readyThisRun = true
                mutableState.value = shown
            }
        }
    }

    /**
     * `Error` de la ejecución [myRun] si sigue siendo la actual (`true` si lo fijó); con puente, por defecto con
     * `bridge`.
     */
    private fun failRun(myRun: Long, message: String = START_FAILED, bridge: Boolean = bridgedRun): Boolean =
        synchronized(lock) {
            if (running && run == myRun) {
                mutableState.value = TorState.Error(message, bridge = bridge)
                true
            } else {
                false
            }
        }

    /** Para los transportes de la ejecución, si los hay (no lanza). */
    private fun stopPt() {
        val controller = pt ?: return
        pt = null
        try {
            controller.stop()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Mejor esfuerzo: PtController.stop no debería lanzar.
        }
    }

    /**
     * Borra [torDir] y el estado de IPtProxy (los dos, aunque falle uno) y, si todo se borró y [removeMarker],
     * después el marcador. `true` si no queda nada de lo que había que borrar.
     */
    private fun wipeDisk(removeMarker: Boolean): Boolean {
        val tor = !torDir.exists() || torDir.deleteRecursively()
        val pts = !ptStateDir.exists() || ptStateDir.deleteRecursively()
        if (!tor || !pts) return false
        return !removeMarker || !markerFile.exists() || markerFile.delete()
    }

    private fun obtainDriver(): TorDriver =
        driver ?: driverFactory(::onRuntimeState).also { driver = it }

    /**
     * Con el transporte envenenado: reintenta una vez detener Tor (mismo plazo que [stop]). `true` si
     * terminó (ya no está envenenado); si no, `false` con el estado en `Error` "atascado". Mientras
     * tanto el estado es `Starting(0)` (R13: [start] nunca deja `Off`).
     */
    private suspend fun recoverLocked(): Boolean {
        mutableState.value = TorState.Starting(0)
        val d = try {
            withContext(Dispatchers.IO) { obtainDriver() }
        } catch (e: CancellationException) {
            mutableState.value = stuckError()
            throw e
        } catch (e: Exception) {
            mutableState.value = stuckError()
            return false
        }
        val finished = try {
            stopDaemonVerified(d)
        } catch (e: CancellationException) {
            mutableState.value = stuckError()
            throw e
        }
        if (!finished) {
            mutableState.value = stuckError()
            return false
        }
        poisoned = false
        return true
    }

    /**
     * Detiene Tor con el plazo de [stop]. `true` solo si se detuvo: `stopDaemon` terminó, o lanzó pero el runtime
     * ya está en `Off`. Con el plazo agotado, o si lanzó con Tor aún en marcha, `false` (quien llama envenena).
     */
    private suspend fun stopDaemonVerified(d: TorDriver): Boolean =
        withTimeoutOrNull(stopTimeoutMillis) {
            try {
                d.stopDaemon()
                true
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                try {
                    d.isDaemonOff()
                } catch (_: Throwable) {
                    false
                }
            }
        } ?: false

    private fun stuckError() = TorState.Error(STOP_TIMED_OUT, stuck = true)

    private suspend fun stopLocked() {
        synchronized(lock) {
            running = false
            run++
        }
        ptWatch?.cancel()
        ptWatch = null
        // Interrumpe también un arranque en curso. Acotado: un Tor colgado no bloquea al motor, pero
        // envenena el transporte (ver [poisoned]). Un fallo que no sea por tiempo solo envenena si Tor
        // sigue en marcha. Ya envenenado no se vuelve a esperar (solo [start] lo reintenta).
        val d = driver
        if (d != null && !poisoned) {
            if (!stopDaemonVerified(d)) poisoned = true
        }
        startJob?.cancelAndJoin()
        startJob = null
        // R30: los transportes, después de Tor (que los usa), aunque Tor se haya atascado.
        stopPt()
        if (bridgedRun) {
            // R30: con puente, cada parada borra el estado de Tor y de IPtProxy (como wipeState, con un driver
            // nuevo en el siguiente arranque). Con Tor atascado se borra lo que se pueda y el marcador se queda.
            driver = null
            withContext(Dispatchers.IO) { wipeDisk(removeMarker = !poisoned) }
            bridgedRun = false
        }
        mutableState.value = TorState.Off
    }

    /** Espera a `Ready` mientras Tor arranca; `IOException` si está o pasa a `Off`/`Error`. */
    private suspend fun awaitReady(): TorDriver {
        val s = state.first { it !is TorState.Starting }
        return driver.takeIf { s == TorState.Ready } ?: throw IOException("Tor no está listo")
    }

    /**
     * Driver para retirar algo, sin esperar nunca. Antes del primer `Ready` de esta ejecución no hay nada
     * que retirar (todo se registra a partir de `Ready`): `null`. Después, se actúa en cualquier estado
     * que no sea `Off`/`Error` (p. ej. `Starting(100)` con la red desactivada: Tor sigue teniendo los
     * servicios). Con Tor parado o caído, `null`: no guarda nada.
     */
    private fun driverForRemoval(): TorDriver? {
        val s = state.value
        if (s == TorState.Off || s is TorState.Error) return null
        return driver.takeIf { readyThisRun }
    }

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
        const val MARKER_FILE = "tor-bridged"
        const val PT_DIR = "pt"
        const val START_FAILED = "No se pudo arrancar Tor"
        const val NO_BRIDGES = "No hay puentes válidos"
        const val PT_FAILED = "No se pudo iniciar el transporte del puente"
        const val PT_LOST = "El transporte del puente ha fallado"
        const val BRIDGES_UNREADABLE = "No se pudo leer el ajuste de puentes"
        const val BRIDGES_NOT_APPLIED = "Tor no aplicó la configuración del puente"
        const val START_CANCELLED = "Arranque de Tor cancelado"
        const val WIPE_FAILED = "No se pudo borrar el estado de Tor"
        const val STOP_TIMED_OUT = "Tor no se detuvo a tiempo; hay que reiniciar la app"
        const val STOP_TIMEOUT_MILLIS = 15_000L
        const val COMMAND_TIMEOUT_MILLIS = 30_000L
    }
}

/** La orden de control falló sin respuesta definitiva de Tor (tiempo agotado, interrupción, conexión caída). */
internal class AmbiguousTorException(message: String) : IOException(message)
