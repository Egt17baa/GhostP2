package org.ghostp2.app

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ghostp2.app.lock.AppLock
import org.ghostp2.app.lock.DeviceLock
import org.ghostp2.app.photo.PhotoEncoder
import org.ghostp2.app.photo.PhotoQueue
import org.ghostp2.app.ui.clearOwnClip
import org.ghostp2.data.AppSettings
import org.ghostp2.data.SettingsRepository
import org.ghostp2.data.StoreCorruptedException
import org.ghostp2.data.StoreUnavailableException
import org.ghostp2.data.StoredRepositories
import org.ghostp2.data.createRepositories
import org.ghostp2.engine.ChatItem
import org.ghostp2.engine.ContactRepository
import org.ghostp2.engine.DeletionJournal
import org.ghostp2.engine.DeliveryStatus
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.IdentityRepository
import org.ghostp2.engine.MessengerEngine
import org.ghostp2.engine.Notice
import org.ghostp2.engine.TorTransport
import org.ghostp2.engine.UnpairDelivery
import org.ghostp2.tor.KmpTorTransport

/** Aplicación: crea los canales de notificación y es la única dueña del [AppContainer]. */
open class GhostApp : Application() {
    /** Contenedor único del proceso (se crea en el primer acceso). */
    val container: AppContainer by lazy { createContainer() }

    /** Punto de inyección para las pruebas (FakeTor y almacenamiento en memoria). */
    protected open fun createContainer(): AppContainer = AppContainer(this)

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }
}

/**
 * Manejador de los scopes del proceso ([AppContainer.scope], que heredan el motor y sus sesiones, y el del
 * [GhostService]): una excepción inesperada en una corrutina hija no cierra la app (en Android, sin
 * manejador, mataría el proceso). No registra nada: la excepción podría citar datos de la app.
 */
internal val SilentExceptionHandler = CoroutineExceptionHandler { _, _ ->
    // A propósito vacío: sin registros (regla de no registrar contenido ni claves).
}

/**
 * Espera a que el elemento [itemId] de [contactId] deje de estar en [DeliveryStatus.Sending] o salga del
 * historial (borrado, descartado o motor parado).
 */
internal suspend fun awaitPhotoFinished(
    conversations: StateFlow<Map<String, List<ChatItem>>>,
    contactId: String,
    itemId: String,
) {
    conversations.first { all -> all[contactId]?.firstOrNull { it.id == itemId }?.status !is DeliveryStatus.Sending }
}

/** El contenedor del proceso a partir de cualquier `Context`. */
val Context.container: AppContainer get() = (applicationContext as GhostApp).container

/**
 * Almacenamiento de identidad, contactos, ajustes y borrados pendientes, con el borrado forzado para
 * cuando "Reintentar" no sirve.
 */
interface AppStorage {
    val identities: IdentityRepository
    val contacts: ContactRepository
    val settings: SettingsRepository
    val deletions: DeletionJournal

    /**
     * Lee el almacenamiento sin conservar nada. Lanza [StoreCorruptedException] o
     * [StoreUnavailableException] si no se puede leer; nunca borra ni sustituye nada.
     */
    suspend fun probe()

    /** Ver [StoredRepositories.forceReset]: borra archivo y claves sin condiciones. */
    suspend fun forceReset()
}

private class StoredAppStorage(private val repositories: StoredRepositories) : AppStorage {
    override val identities: IdentityRepository get() = repositories.identities
    override val contacts: ContactRepository get() = repositories.contacts
    override val settings: SettingsRepository get() = repositories.settings
    override val deletions: DeletionJournal get() = repositories.deletions

    override suspend fun probe() {
        // `load()` devuelve copias recién descifradas: se rellenan con ceros enseguida.
        repositories.identities.load()?.let { id ->
            id.identityPrivate.fill(0)
            id.onionPrivateKey?.fill(0)
        }
    }

    override suspend fun forceReset() = repositories.forceReset()
}

/** Estado del almacenamiento cifrado visto por la interfaz. */
sealed interface StorageState {
    /** Todavía no se ha comprobado. */
    data object Unknown : StorageState

    data object Ok : StorageState

    /**
     * Error inesperado al comprobar el almacenamiento (ni corrupción ni fallo pasajero conocido):
     * "Reintentar"; nunca se borra nada por esto. Como [Unavailable], cuenta en la racha de fallos.
     */
    data class Failed(override val failures: Int) : StorageState, FailureStreak

    /** Datos ilegibles e irrecuperables: solo cabe "Empezar de cero" ([AppContainer.wipeCorrupted]). */
    data object Corrupted : StorageState

    /**
     * Fallo pasajero ("Reintentar"), `failures` veces seguidas. A partir de [FAILURES_BEFORE_RESET] se
     * ofrece además "Empezar de cero" con doble confirmación ([AppContainer.forceReset]).
     */
    data class Unavailable(override val failures: Int) : StorageState, FailureStreak

    /**
     * Estados con "Reintentar": `failures` = comprobaciones seguidas sin éxito ([Unavailable] o [Failed],
     * mezclados). A partir de [FAILURES_BEFORE_RESET] se ofrece además "Empezar de cero" con doble
     * confirmación ([AppContainer.forceReset]).
     */
    sealed interface FailureStreak {
        val failures: Int
        val canForceReset: Boolean get() = failures >= FAILURES_BEFORE_RESET
    }

    companion object {
        const val FAILURES_BEFORE_RESET = 3
    }
}

/**
 * Inyección de dependencias manual: el único [TorTransport] y el único almacenamiento del proceso y el
 * [MessengerEngine] que los usa, creado una sola vez con `SupervisorJob() + Dispatchers.Default` (R7).
 * El servicio y la interfaz piden arrancar y detener a través de [start] y [stop], que se ordenan entre
 * sí: un `stop` cancela un `start` en curso y un `start` posterior espera a que termine el `stop`.
 * Nunca se arranca el motor si el almacenamiento falla (se informa en [storage]; regla R16: nada en claro).
 */
class AppContainer internal constructor(
    context: Context,
    val tor: TorTransport,
    private val store: AppStorage,
) {
    /** Producción: Tor con kmp-tor y `ghostp2.bin` cifrado con Android Keystore. */
    constructor(context: Context) : this(
        context,
        KmpTorTransport(context.applicationContext),
        StoredAppStorage(createRepositories(context.applicationContext)),
    )

    internal val appContext: Context = context.applicationContext

    /**
     * Scope del proceso (vive lo que la aplicación), con [SilentExceptionHandler]: el motor lo recibe y
     * todos sus scopes (bucles, sesiones) lo heredan.
     */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + SilentExceptionHandler)

    /** Con el diario guardado: los borrados de contacto interrumpidos se reanudan al arrancar. */
    val engine = MessengerEngine(tor, store.identities, store.contacts, scope, deletions = store.deletions)

    /** Contactos guardados (para nombres y la pantalla de diagnóstico). */
    val contacts: ContactRepository get() = store.contacts

    /** Ajustes de la app, en el mismo almacenamiento cifrado. */
    val settings: SettingsRepository get() = store.settings

    /**
     * Bloqueo de la app (R22), alimentado desde [settings] en [scope]. La actividad le pasa sus
     * `onStart`/`onStop` y la raíz de la interfaz solo muestra la pantalla de bloqueo mientras dure.
     */
    val lock = AppLock()

    /**
     * Contacto cuyo chat está abierto y en primer plano (entre `onResume` y `onPause`), o `null`: el
     * servicio no notifica sus mensajes nuevos, que ya se están viendo. Solo en memoria.
     */
    @Volatile
    var foregroundChatId: String? = null
        private set

    /** El chat de [contactId] pasa a verse en primer plano. */
    fun chatShown(contactId: String) {
        synchronized(control) { foregroundChatId = contactId }
    }

    /** El chat de [contactId] deja de verse (si otro chat lo sustituyó ya, no cambia nada). */
    fun chatHidden(contactId: String) {
        synchronized(control) { if (foregroundChatId == contactId) foregroundChatId = null }
    }

    /** Si el móvil tiene bloqueo de pantalla ([DeviceLock.available]); las pruebas lo sustituyen. */
    internal var deviceLockAvailable: (Context) -> Boolean = DeviceLock::available

    /**
     * Cola de fotos salientes: una en vuelo y como mucho 3 en total, contando la que está en vuelo (R25).
     * Solo envía con el motor `Online` (si no, `NOT_ACTIVE`); pasa a la siguiente cuando la anterior deja
     * de estar en `Sending` o sale del historial ([awaitPhotoFinished]). [stop] la vacía.
     */
    val photos = PhotoQueue(
        scope,
        encode = { uri -> PhotoEncoder.encode(appContext.contentResolver, uri, maxPhotoSidePx()) },
        send = { contactId, jpeg -> if (engine.status.value == EngineStatus.Online) engine.sendPhoto(contactId, jpeg) else null },
        awaitFinished = { contactId, itemId -> awaitPhotoFinished(engine.conversations, contactId, itemId) },
    )

    /** Lado mayor de las fotos según los ajustes; si no se pueden leer, el valor por defecto. */
    private suspend fun maxPhotoSidePx(): Int = try {
        settings.get().maxPhotoSidePx
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        AppSettings().maxPhotoSidePx
    }

    private val mutableStorage = MutableStateFlow<StorageState>(StorageState.Unknown)

    /** Primera espera antes de volver a leer los ajustes del bloqueo (después 2, 4 y 8 veces más). */
    @Volatile internal var lockRetryMillis = LOCK_RETRY_MILLIS

    /** Cuántas comprobaciones del almacenamiento han terminado (para reintentar leer los ajustes del bloqueo). */
    private val storageChecks = MutableStateFlow(0)
    val storage: StateFlow<StorageState> = mutableStorage.asStateFlow()

    private val mutableStartFailed = MutableStateFlow(false)

    /**
     * El último arranque falló por un error inesperado (el motor quedó detenido): la pantalla muestra un
     * error genérico con "Reintentar". Se borra al volver a pedir [start].
     */
    val startFailed: StateFlow<Boolean> = mutableStartFailed.asStateFlow()

    private val mutableLastDiagnostic = MutableStateFlow<Notice?>(null)

    /**
     * Último aviso de diagnóstico del motor ([Notice.PublicationFailed] o [Notice.ClientAuthFailed]),
     * para la pantalla de diagnóstico; no contiene secretos (como mucho ids de contactos).
     */
    val lastDiagnostic: StateFlow<Notice?> = mutableLastDiagnostic.asStateFlow()

    init {
        // Si los ajustes no se pueden leer, se reintenta [LOCK_SETTINGS_RETRIES] veces con esperas
        // crecientes y, después, solo tras cada nueva comprobación del almacenamiento ("Reintentar",
        // "Activar"…). Mientras, el bloqueo sigue sin configurar (cerrado) y la raíz muestra la carga o
        // el error del almacenamiento.
        scope.launch {
            settings.settings
                .retryWhen { _, attempt ->
                    if (attempt < LOCK_SETTINGS_RETRIES) {
                        delay(lockRetryMillis shl attempt.toInt())
                    } else {
                        val seen = storageChecks.value
                        storageChecks.first { it > seen }
                    }
                    true
                }
                .collect { lock.configure(it.lockEnabled, it.lockAfterMinutes) }
        }
        // Suscrito desde ya (UNDISPATCHED): los avisos no se reproducen.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            engine.notices.collect { notice ->
                if (notice is Notice.PublicationFailed || notice is Notice.ClientAuthFailed) {
                    mutableLastDiagnostic.value = notice
                }
            }
        }
    }

    /** Un arranque pedido con [start]: su corrutina y su resultado. */
    private class StartRun(val job: Job, val result: Deferred<Boolean>)

    // Protegidos por `control`.
    private val control = Any()
    private var startRun: StartRun? = null
    private var stopJob: Job? = null

    /** Cambia al empezar y al terminar cada [wipeAll] (con el cerrojo tomado). */
    private var wipeGeneration = 0L

    /** Serializa las comprobaciones y los borrados del almacenamiento. */
    private val storageLock = Mutex()

    /** Comprueba que el almacenamiento se puede leer ([AppStorage.probe]) y actualiza [storage]. */
    suspend fun checkStorage(): StorageState = storageLock.withLock { probeLocked() }

    private suspend fun probeLocked(): StorageState {
        val result = try {
            store.probe()
            StorageState.Ok
        } catch (_: StoreCorruptedException) {
            StorageState.Corrupted
        } catch (_: StoreUnavailableException) {
            StorageState.Unavailable(nextStreak())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Inesperado: falla cerrado (ni texto en claro ni borrados); la causa no se registra.
            StorageState.Failed(nextStreak())
        }
        mutableStorage.value = result
        storageChecks.update { it + 1 }
        return result
    }

    private fun nextStreak(): Int = ((mutableStorage.value as? StorageState.FailureStreak)?.failures ?: 0) + 1

    /**
     * Comprueba el almacenamiento y, si está bien, arranca el motor. El resultado es `false` si el
     * almacenamiento falla (el motor no se arranca) o si un [stop] cancela el arranque. Mientras hay un
     * arranque en curso devuelve el mismo resultado.
     */
    fun start(): Deferred<Boolean> = synchronized(control) {
        startRun?.takeIf { it.job.isActive }?.let { return it.result }
        val previousStop = stopJob
        val result = CompletableDeferred<Boolean>()
        mutableStartFailed.value = false
        val generation = wipeGeneration
        val job = scope.launch {
            previousStop?.join()
            try {
                // Pedido durante "Borrar todo" (generación cambiada): no arranca, o crearía una identidad nueva.
                if (checkStorage() == StorageState.Ok && synchronized(control) { wipeGeneration == generation }) {
                    // Ejecución nueva: el aviso de diagnóstico anterior ya no vale (los de este arranque sí).
                    mutableLastDiagnostic.value = null
                    engine.start()
                    result.complete(true)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Inesperado (engine.start() no debería lanzar): se deja el motor detenido.
                mutableStartFailed.value = true
                runCatching { engine.stop() }
            }
        }
        // Cancelado o con el almacenamiento fallando: false.
        job.invokeOnCompletion { result.complete(false) }
        startRun = StartRun(job, result)
        result
    }

    /** Detiene el motor; antes cancela (y espera) un arranque en curso. */
    fun stop(): Job = synchronized(control) {
        // Las fotos pendientes no sobreviven a una parada.
        photos.clear()
        val run = startRun
        startRun = null
        val previousStop = stopJob
        scope.launch {
            run?.job?.cancelAndJoin()
            previousStop?.join()
            try {
                engine.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // engine.stop() termina en Stopped aunque falle al liberar; sin cerrar la app.
            }
        }.also { stopJob = it }
    }

    /**
     * "Empezar de cero" con datos ilegibles ([StorageState.Corrupted]): detiene el servicio y el motor
     * y ejecuta [MessengerEngine.wipeAll] (borra identidad, contactos y estado de Tor).
     */
    suspend fun wipeCorrupted() {
        stopEverything()
        storageLock.withLock {
            try {
                engine.wipeAll()
            } finally {
                mutableStorage.update { StorageState.Unknown }
                probeLocked()
            }
        }
    }

    /**
     * "Empezar de cero" tras fallos pasajeros repetidos (doble confirmación en la interfaz): detiene todo,
     * borra archivo y claves sin condiciones ([StoredRepositories.forceReset]) y el estado de Tor.
     */
    suspend fun forceReset() {
        stopEverything()
        storageLock.withLock {
            try {
                store.forceReset()
                runCatching { tor.wipeState() }
            } finally {
                mutableStorage.update { StorageState.Unknown }
                probeLocked()
            }
        }
    }

    /**
     * "Borrar todo" de los ajustes (doble confirmación en la interfaz). Vacía la cola de fotos y, con el
     * cerrojo del almacenamiento tomado: [MessengerEngine.wipeAll] (avisa a los contactos conectados,
     * detiene el motor y borra identidad, contactos, claves, borrados pendientes y el estado de Tor),
     * [SettingsRepository.wipe] (ajustes por defecto: `onboardingDone == false`, la raíz vuelve a la
     * bienvenida), detiene el servicio, quita todas las notificaciones, vacía el portapapeles si tiene
     * una invitación de GhostP2 y vuelve a comprobar el almacenamiento.
     *
     * El motor se borra antes de detener el servicio: [stop] cerraría las sesiones y los contactos no
     * recibirían el aviso. Detener todo con el cerrojo aún tomado cancela un arranque pedido entretanto
     * (espera el cerrojo en [checkStorage]); uno pedido después de detener y antes de soltar el cerrojo
     * lo descarta [wipeGeneration]. Si no, crearía una identidad nueva tras el borrado.
     * Lanza si falla el borrado del almacenamiento; aun así queda todo detenido.
     */
    suspend fun wipeAll() {
        synchronized(control) { wipeGeneration++ }
        photos.clear()
        storageLock.withLock {
            try {
                engine.wipeAll()
                settings.wipe()
            } finally {
                withContext(NonCancellable) {
                    stopEverything()
                    // También las publicadas durante el aviso a los contactos (nombres de contactos borrados).
                    try {
                        NotificationManagerCompat.from(appContext).cancelAll()
                    } catch (_: Exception) {
                        // Sin gestor de notificaciones: no queda nada que quitar.
                    }
                    clearOwnClip(appContext)
                    synchronized(control) { foregroundChatId = null }
                    mutableLastDiagnostic.value = null
                    mutableStorage.update { StorageState.Unknown }
                    probeLocked()
                    // Aún con el cerrojo: un start() pedido durante el borrado ve otra generación y no arranca.
                    synchronized(control) { wipeGeneration++ }
                }
            }
        }
    }

    /**
     * Borra el contacto avisándole ([MessengerEngine.deleteContact]) en el scope del proceso: salir de
     * la pantalla no corta la entrega (hasta 2 min) ni el borrado local.
     */
    fun deleteContact(contactId: String): Deferred<UnpairDelivery> =
        scope.async { engine.deleteContact(contactId) }

    /**
     * La app se ha abierto (o ha vuelto a primer plano): con el motor en marcha, corta las esperas de
     * reconexión y reintenta ya, como un cambio de red (restricción global: "reinicio al cambiar la red
     * o al abrir la app"). Con el motor parado no hace nada.
     */
    fun onAppOpened() {
        if (engine.status.value == EngineStatus.Online) engine.onNetworkChanged()
    }

    /**
     * El móvil ya no tiene bloqueo de pantalla: desbloquea y avisa ([AppLock.disableForMissingDeviceLock])
     * y guarda `lockEnabled = false`. Si no se puede guardar, el siguiente arranque lo vuelve a detectar.
     */
    fun disableLockForMissingDeviceLock() {
        lock.disableForMissingDeviceLock()
        scope.launch {
            try {
                settings.update { it.copy(lockEnabled = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // El almacenamiento falla: la raíz lo muestra; el bloqueo ya está abierto.
            }
        }
    }

    private suspend fun stopEverything() {
        photos.clear()
        // Sin servicio no hay notificación fija "activo"; si no estaba en marcha no hace nada.
        appContext.stopService(Intent(appContext, GhostService::class.java))
        stop().join()
    }
}

/** Valor por defecto de [AppContainer.lockRetryMillis]. */
private const val LOCK_RETRY_MILLIS = 1_000L

/** Reintentos con espera; agotados, solo se reintenta tras otra comprobación del almacenamiento. */
private const val LOCK_SETTINGS_RETRIES = 4L
