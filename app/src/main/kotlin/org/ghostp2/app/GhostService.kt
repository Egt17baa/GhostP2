package org.ghostp2.app

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.annotation.VisibleForTesting
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.Notice

/**
 * Servicio en primer plano (`specialUse`) que mantiene GhostP2 activo.
 *
 * - [ACTION_START]: notificación fija ([Notifications.status]) con `startForeground` y arranque del motor
 *   del [AppContainer] (que antes comprueba el almacenamiento; si falla, el servicio se detiene solo y la
 *   pantalla muestra el error). Registra un `NetworkCallback` de la red por defecto que llama a
 *   `engine.onNetworkChanged()`.
 * - Intent `null`: el sistema recrea el servicio tras matar el proceso (`START_STICKY`, regla R19);
 *   se comporta igual que [ACTION_START]. Si entonces falla el almacenamiento, se detiene y deja una
 *   notificación que pide abrir la app. Si el plazo de "Desconectar al salir de la app" (R29) guardado
 *   ya venció, se detiene sin arrancar nada ni notificar; si no, se vuelve a armar por lo que queda.
 * - [ACTION_START] con [EXTRA_FROM_BOOT] (lo envía [BootReceiver] al encender el móvil, R20): como el
 *   reinicio `START_STICKY`, sin nadie mirando la pantalla; si no puede pasar a primer plano o falla el
 *   almacenamiento, se detiene y deja la notificación que pide abrir la app.
 * - [ACTION_STOP] (acción "Desconectar") o cualquier otra acción: detiene el motor y el servicio, que
 *   ya no vuelve solo (`stopSelfResult`: el sistema solo recrea servicios iniciados).
 * - Mientras existe, reenvía `engine.notices` a notificaciones sin contenido (sin nombre con "Ocultar el
 *   nombre del contacto"; sin aviso de mensaje para el chat abierto en primer plano,
 *   [AppContainer.foregroundChatId]), y con el motor en `Error` la notificación fija pasa a "sin
 *   conexión" (con "Desconectar").
 *
 * No crea Tor ni repositorios: todo es del [AppContainer] (uno por proceso). Si Tor falla con el motor en
 * marcha (regla R18) el motor queda en `Error` y el servicio sigue: la pantalla ofrece "Reintentar", que
 * vuelve a enviar [ACTION_START], y al volver la red se reinicia solo una vez (como mucho uno cada
 * [RECOVERY_SPACING_MILLIS]). Se detiene siempre con `stopSelfResult(último startId)`. Solo se arranca al
 * encender el móvil si el usuario lo activó en los ajustes (R20, desactivado por defecto; ver
 * [BootReceiver]); `START_STICKY` cubre que el sistema mate el proceso con GhostP2 activo.
 */
class GhostService : Service() {
    @VisibleForTesting
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + SilentExceptionHandler)

    /** Reloj monótono (inyectable en pruebas). */
    @VisibleForTesting
    internal var elapsedRealtime: () -> Long = { android.os.SystemClock.elapsedRealtime() }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Cambia con cada START/STOP (solo en el hilo principal): un arranque antiguo no detiene uno nuevo. */
    private var generation = 0

    /** En primer plano por un START (solo en el hilo principal). */
    private var foreground = false

    /**
     * `startId` del último `onStartCommand` (solo en el hilo principal): se detiene con
     * `stopSelfResult(latestStartId)`, que no hace nada si el sistema ya tiene encolado un START más nuevo.
     */
    private var latestStartId = 0

    /** Último reinicio automático por volver la red (reloj [elapsedRealtime]); solo en el hilo principal. */
    private var lastRecoveryAt: Long? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = container
        // UNDISPATCHED: suscrito ya al volver, antes de arrancar el motor (los avisos no se reproducen).
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            container.engine.notices.collect { notice ->
                // Un fallo al notificar nunca cierra la app ni corta los avisos siguientes.
                try {
                    // El chat ya está a la vista: sin aviso de mensaje nuevo.
                    if (notice is Notice.MessageArrived && notice.contactId == container.foregroundChatId) return@collect
                    val hideNames = hideNames()
                    val name = when {
                        hideNames -> null
                        notice is Notice.MessageArrived -> nameOf(notice.contactId)
                        notice is Notice.IdentityChanged -> nameOf(notice.contactId)
                        else -> null
                    }
                    Notifications.forNotice(this@GhostService, notice, name, hideNames)
                        ?.let { Notifications.post(this@GhostService, it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Sin notificación para este aviso.
                }
            }
        }
        scope.launch {
            container.engine.status.map { it is EngineStatus.Error }.distinctUntilChanged().collect { error ->
                withContext(Dispatchers.Main) {
                    // startForeground otra vez (no notify): se actualiza aunque falte POST_NOTIFICATIONS.
                    if (foreground) {
                        try {
                            enterForeground(Notifications.status(this@GhostService, error))
                        } catch (_: Exception) {
                            // Ya está en primer plano; la notificación anterior sigue.
                        }
                    }
                }
            }
        }
    }

    /** "Ocultar el nombre del contacto", leído en cada aviso; si no se puede leer, se oculta. */
    private suspend fun hideNames(): Boolean =
        try {
            container.settings.get().hideNotificationNames
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            true
        }

    private suspend fun nameOf(contactId: String): String? =
        try {
            container.contacts.all().firstOrNull { it.id == contactId }?.name
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    /**
     * Pone el servicio en primer plano con [notification] (o la sustituye si ya lo está). Punto de
     * inyección para las pruebas.
     */
    @VisibleForTesting
    internal var enterForeground: (Notification) -> Unit = { notification ->
        ServiceCompat.startForeground(
            this,
            Notifications.STATUS_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when {
            // "Desconectar al salir de la app" (R29) venció con el proceso muerto: ni motor ni notificación.
            intent == null -> if (container.autoDisconnect.resumeAfterRestart()) stopExpired() else onStart(restarted = true)
            // Al encender el móvil nadie mira la pantalla: igual que el reinicio STICKY.
            intent.action == ACTION_START -> {
                val fromBoot = intent.getBooleanExtra(EXTRA_FROM_BOOT, false)
                if (fromBoot) container.autoDisconnect.onBootStart()
                onStart(restarted = fromBoot)
            }
            else -> {
                // ACTION_STOP (o un intent desconocido): nunca quedarse en marcha sin pedirlo.
                container.autoDisconnect.cancel()
                onStop()
            }
        }
        return START_STICKY
    }

    private fun onStart(restarted: Boolean) {
        val notification = Notifications.status(this, error = container.engine.status.value is EngineStatus.Error)
        if (restarted) {
            try {
                enterForeground(notification)
            } catch (e: IllegalStateException) {
                // API 31+: un reinicio STICKY (o un arranque al encender el móvil) en segundo plano puede no
                // tener permiso para pasar a primer plano. Sin motor ni NetworkCallback: se detiene y pide
                // abrir la app (sin bucle de cierres). Cualquier otro IllegalStateException se relanza.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || e !is ForegroundServiceStartNotAllowedException) throw e
                generation++
                runCatching { Notifications.postStopped(this) }
                stopSelfResult(latestStartId)
                return
            }
        } else {
            // Iniciado por el usuario desde la actividad (puede pasar a primer plano): un fallo aquí se ve.
            enterForeground(notification)
        }
        foreground = true
        registerNetworkCallback()
        startEngine(++generation, notifyIfStopped = restarted)
    }

    /**
     * Pide arrancar el motor; si el arranque no llega a hacerse (almacenamiento ilegible o no disponible)
     * y nadie ha pedido otra cosa desde entonces ([generation] sigue siendo `mine`), se detiene el servicio
     * (la pantalla muestra el error) y, si [notifyIfStopped], se avisa de que hay que abrir la app.
     */
    private fun startEngine(mine: Int, notifyIfStopped: Boolean) {
        val started = container.start()
        scope.launch {
            if (!started.await()) {
                withContext(Dispatchers.Main) {
                    if (generation == mine) {
                        onStop()
                        if (notifyIfStopped) runCatching { Notifications.postStopped(this@GhostService) }
                    }
                }
            }
        }
    }

    /**
     * Regla R18 (modificada): al volver la red, si el motor quedó en `Error` (p. ej. Tor se cayó) y el
     * usuario no ha pulsado "Desconectar", se reinicia una vez. Como mucho un reinicio por aviso de red y
     * con al menos [RECOVERY_SPACING_MILLIS] entre dos reinicios, para no entrar en un bucle si Tor sigue
     * fallando (entonces queda "Reintentar"). En el hilo principal, como [onStop].
     */
    private fun recoverOnNetworkReturn() {
        if (!foreground || networkCallback == null) return // "Desconectar" ya pulsado
        if (container.engine.status.value !is EngineStatus.Error) return
        val now = elapsedRealtime()
        val last = lastRecoveryAt
        if (last != null && now - last < RECOVERY_SPACING_MILLIS) return
        lastRecoveryAt = now
        startEngine(generation, notifyIfStopped = false)
    }

    private fun onStop() {
        generation++
        foreground = false
        container.stop()
        unregisterNetworkCallback()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelfResult(latestStartId)
    }

    /** Reinicio STICKY con el plazo de R29 ya vencido: se detiene sin pasar a primer plano ni arrancar. */
    private fun stopExpired() {
        generation++
        container.stop()
        stopSelfResult(latestStartId)
    }

    /** Si está en primer plano por un START (para las pruebas). */
    @VisibleForTesting
    internal val inForeground: Boolean get() = foreground

    override fun onDestroy() {
        // Ya no hay primer plano: un cambio de estado del motor tardío no vuelve a llamar a startForeground.
        val wasForeground = foreground
        foreground = false
        generation++
        unregisterNetworkCallback()
        // Parado sin ACTION_STOP ("Desconectar" o "Desconectar al salir de la app", R29, con
        // stopService): la notificación fija "activo" se quita aquí, sin esperar a Android.
        if (wasForeground) ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        // Sin servicio no hay notificación fija: el motor no sigue en marcha a escondidas.
        container.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val engine = container.engine
        val main = Handler(Looper.getMainLooper())
        val callback = object : ConnectivityManager.NetworkCallback() {
            // También al registrarse (red actual): inofensivo, solo reinicia las esperas (y, con el motor
            // en Error, recién pasado a primer plano no hay reinicio porque el motor aún no ha fallado).
            override fun onAvailable(network: Network) {
                engine.onNetworkChanged()
                main.post { if (networkCallback === this) recoverOnNetworkReturn() }
            }

            override fun onLost(network: Network) = engine.onNetworkChanged()
        }
        manager.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback) }
    }

    companion object {
        const val ACTION_START = "org.ghostp2.app.action.START"
        const val ACTION_STOP = "org.ghostp2.app.action.STOP"

        /** Extra de [ACTION_START]: arranque al encender el móvil ([BootReceiver], R20). */
        const val EXTRA_FROM_BOOT = "org.ghostp2.app.extra.FROM_BOOT"

        fun startIntent(context: Context, fromBoot: Boolean = false): Intent =
            Intent(context, GhostService::class.java).setAction(ACTION_START)
                .apply { if (fromBoot) putExtra(EXTRA_FROM_BOOT, true) }

        fun stopIntent(context: Context): Intent =
            Intent(context, GhostService::class.java).setAction(ACTION_STOP)

        /** Separación mínima entre dos reinicios automáticos del motor al volver la red (R18). */
        const val RECOVERY_SPACING_MILLIS = 30_000L
    }
}
