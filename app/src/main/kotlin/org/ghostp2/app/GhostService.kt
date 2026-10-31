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
import android.os.IBinder
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
 *   notificación que pide abrir la app.
 * - [ACTION_STOP] (acción "Desconectar") o cualquier otra acción: detiene el motor y el servicio, que
 *   ya no vuelve solo (`stopSelf`: el sistema solo recrea servicios iniciados).
 * - Mientras existe, reenvía `engine.notices` a notificaciones sin contenido, y con el motor en `Error`
 *   la notificación fija pasa a "sin conexión" (con "Desconectar").
 *
 * No crea Tor ni repositorios: todo es del [AppContainer] (uno por proceso). Si Tor falla con el motor en
 * marcha (regla R18) el motor queda en `Error` y el servicio sigue: la pantalla ofrece "Reintentar", que
 * vuelve a enviar [ACTION_START]. No se arranca solo al encender el móvil (spec §6.9: no hay receptor de
 * `BOOT_COMPLETED`); `START_STICKY` solo cubre que el sistema mate el proceso con GhostP2 activo.
 */
class GhostService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Cambia con cada START/STOP (solo en el hilo principal): un arranque antiguo no detiene uno nuevo. */
    private var generation = 0

    /** En primer plano por un START (solo en el hilo principal). */
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = container
        // UNDISPATCHED: suscrito ya al volver, antes de arrancar el motor (los avisos no se reproducen).
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            container.engine.notices.collect { notice ->
                // Un fallo al notificar nunca cierra la app ni corta los avisos siguientes.
                try {
                    val name = when (notice) {
                        is Notice.MessageArrived -> nameOf(notice.contactId)
                        is Notice.IdentityChanged -> nameOf(notice.contactId)
                        else -> null
                    }
                    Notifications.forNotice(this@GhostService, notice, name)?.let { Notifications.post(this@GhostService, it) }
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
        when {
            intent == null -> onStart(restarted = true)
            intent.action == ACTION_START -> onStart(restarted = false)
            else -> onStop() // ACTION_STOP (o un intent desconocido): nunca quedarse en marcha sin pedirlo.
        }
        return START_STICKY
    }

    private fun onStart(restarted: Boolean) {
        val notification = Notifications.status(this, error = container.engine.status.value is EngineStatus.Error)
        if (restarted) {
            try {
                enterForeground(notification)
            } catch (e: IllegalStateException) {
                // API 31+: un reinicio STICKY en segundo plano puede no tener permiso para pasar a primer
                // plano. Sin motor ni NetworkCallback: se detiene y pide abrir la app (sin bucle de cierres).
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || e !is ForegroundServiceStartNotAllowedException) throw e
                generation++
                runCatching { Notifications.postStopped(this) }
                stopSelf()
                return
            }
        } else {
            // Iniciado por el usuario desde la actividad (puede pasar a primer plano): un fallo aquí se ve.
            enterForeground(notification)
        }
        foreground = true
        registerNetworkCallback()
        val mine = ++generation
        val started = container.start()
        scope.launch {
            if (!started.await()) {
                // Almacenamiento ilegible o no disponible: sin servicio (la pantalla muestra el error).
                withContext(Dispatchers.Main) {
                    if (generation == mine) {
                        onStop()
                        if (restarted) runCatching { Notifications.postStopped(this@GhostService) }
                    }
                }
            }
        }
    }

    private fun onStop() {
        generation++
        foreground = false
        container.stop()
        unregisterNetworkCallback()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        // Sin servicio no hay notificación fija: el motor no sigue en marcha a escondidas.
        container.stop()
        scope.cancel()
        super.onDestroy()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val engine = container.engine
        val callback = object : ConnectivityManager.NetworkCallback() {
            // También al registrarse (red actual): inofensivo, solo reinicia las esperas.
            override fun onAvailable(network: Network) = engine.onNetworkChanged()

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

        fun startIntent(context: Context): Intent =
            Intent(context, GhostService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent =
            Intent(context, GhostService::class.java).setAction(ACTION_STOP)
    }
}
