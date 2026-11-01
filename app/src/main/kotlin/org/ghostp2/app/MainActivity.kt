package org.ghostp2.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import org.ghostp2.app.lock.LocalLockPrompt
import org.ghostp2.app.lock.LockPrompt
import org.ghostp2.app.ui.ActivityActions
import org.ghostp2.app.ui.GhostRoot
import org.ghostp2.app.ui.theme.GhostTheme

/** Única actividad. Es una [FragmentActivity] porque `BiometricPrompt` (bloqueo de la app, R22) la necesita. */
class MainActivity : FragmentActivity() {
    /** Al pulsar Activar en API 33+ se pide el permiso; se conceda o no, el servicio arranca. */
    private val notificationPermissionThenStart =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startGhostService() }

    /** Solo pide el permiso de notificaciones (paso de la bienvenida), sin arrancar nada. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Ya se pidió el permiso en la bienvenida: "Activar" no vuelve a preguntar (solo en memoria). */
    private var notificationsAsked = false

    private val batteryExemption =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    /** El único `BiometricPrompt` de esta actividad (se crea en [onCreate]: necesita su ViewModelStore). */
    internal lateinit var lockPrompt: LockPrompt
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Impide capturas de pantalla y miniaturas en recientes
        window.setFlags(FLAG_SECURE, FLAG_SECURE)
        lockPrompt = LockPrompt(
            this,
            onSucceeded = container.lock::unlock,
            // El diálogo dice que no hay bloqueo de pantalla: se comprueba antes de desactivar nada.
            onNoDeviceLock = { if (!container.deviceLockAvailable(this)) container.disableLockForMissingDeviceLock() },
        )
        val actions = ActivityActions(
            activate = ::activate,
            disconnect = ::disconnect,
            requestBatteryExemption = {
                runCatching { BatteryOptimization.launchRequest(this) { batteryExemption.launch(it) } }
            },
            requestNotifications = ::requestNotifications,
        )
        setContent {
            CompositionLocalProvider(LocalLockPrompt provides lockPrompt) { GhostTheme { GhostRoot(container, actions) } }
        }
    }

    override fun onStart() {
        super.onStart()
        // Antes de nada: si venció "Desconectar al salir de la app" (R29), se desconecta ya.
        container.autoDisconnect.onStart()
        // Vuelve de segundo plano: bloquea si pasó el tiempo de "Bloquear tras".
        container.lock.onStart()
    }

    override fun onStop() {
        super.onStop()
        // Girar el móvil recrea la actividad, pero no es salir de la app.
        container.lock.onStop(isChangingConfigurations)
        container.autoDisconnect.onStop(isChangingConfigurations)
    }

    override fun onResume() {
        super.onResume()
        // Al abrir la app (o volver a ella) se reintenta ya con los contactos desconectados.
        container.onAppOpened()
    }

    private fun needsNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    private fun activate() {
        if (needsNotificationPermission() && !notificationsAsked) {
            notificationPermissionThenStart.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startGhostService()
        }
    }

    private fun requestNotifications() {
        if (needsNotificationPermission()) {
            notificationsAsked = true
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    /** Arranca el servicio en primer plano (las pruebas lo sustituyen). */
    internal var startForeground: (Intent) -> Unit = { ContextCompat.startForegroundService(this, it) }

    /**
     * Arranca el servicio solo con la actividad a la vista (la respuesta del permiso puede llegar ya en
     * segundo plano). Si Android lo niega (`IllegalStateException`; en API 31+
     * `ForegroundServiceStartNotAllowedException`), no se cierra la app: el motor sigue parado y el aviso
     * sigue ofreciendo "Activar".
     */
    internal fun startGhostService() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        try {
            startForeground(GhostService.startIntent(this))
        } catch (_: IllegalStateException) {
            // Nada que hacer: el usuario puede volver a pulsar "Activar".
        }
    }

    private fun disconnect() {
        // Si el servicio no está en marcha no hace nada; su onDestroy detiene el motor.
        stopService(GhostService.stopIntent(this))
        container.stop()
    }
}
