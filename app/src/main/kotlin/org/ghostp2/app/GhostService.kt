package org.ghostp2.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ghostp2.engine.Notice

/**
 * Servicio en primer plano (`specialUse`) que mantiene GhostP2 activo.
 *
 * - [ACTION_START]: notificación fija ([Notifications.status]) con `startForeground` y arranque del motor
 *   del [AppContainer] (que antes comprueba el almacenamiento; si falla, el servicio se detiene solo y la
 *   pantalla muestra el error). Registra un `NetworkCallback` de la red por defecto que llama a
 *   `engine.onNetworkChanged()`.
 * - [ACTION_STOP] (acción "Desconectar"): detiene el motor y el servicio.
 * - Mientras existe, reenvía `engine.notices` a notificaciones sin contenido.
 *
 * No crea Tor ni repositorios: todo es del [AppContainer] (uno por proceso). Si Tor falla con el motor en
 * marcha (regla R18) el motor queda en `Error` y el servicio sigue: la pantalla ofrece "Reintentar", que
 * vuelve a enviar [ACTION_START]. `START_NOT_STICKY`: no se reanuda solo tras matar el proceso (spec §6.9).
 */
class GhostService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Cambia con cada START/STOP (solo en el hilo principal): un arranque antiguo no detiene uno nuevo. */
    private var generation = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val container = container
        // UNDISPATCHED: suscrito ya al volver, antes de arrancar el motor (los avisos no se reproducen).
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            container.engine.notices.collect { notice ->
                val name = when (notice) {
                    is Notice.MessageArrived -> nameOf(notice.contactId)
                    is Notice.IdentityChanged -> nameOf(notice.contactId)
                    else -> null
                }
                Notifications.forNotice(this@GhostService, notice, name)?.let { Notifications.post(this@GhostService, it) }
            }
        }
    }

    private suspend fun nameOf(contactId: String): String? =
        runCatching { container.contacts.all().firstOrNull { it.id == contactId }?.name }.getOrNull()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> onStart()
            else -> onStop() // ACTION_STOP (o un intent desconocido): nunca quedarse en marcha sin pedirlo.
        }
        return START_NOT_STICKY
    }

    private fun onStart() {
        ServiceCompat.startForeground(
            this,
            Notifications.STATUS_ID,
            Notifications.status(this),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        registerNetworkCallback()
        val mine = ++generation
        val started = container.start()
        scope.launch {
            if (!started.await()) {
                // Almacenamiento ilegible o no disponible: sin servicio (la pantalla muestra el error).
                withContext(Dispatchers.Main) { if (generation == mine) onStop() }
            }
        }
    }

    private fun onStop() {
        generation++
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
