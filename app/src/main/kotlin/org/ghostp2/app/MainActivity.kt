package org.ghostp2.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.ghostp2.app.ui.ActivityActions
import org.ghostp2.app.ui.GhostRoot
import org.ghostp2.app.ui.theme.GhostTheme

class MainActivity : ComponentActivity() {
    /** Al pulsar Activar en API 33+ se pide el permiso; se conceda o no, el servicio arranca. */
    private val notificationPermissionThenStart =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startGhostService() }

    /** Solo pide el permiso de notificaciones (paso de la bienvenida), sin arrancar nada. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val batteryExemption =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Impide capturas de pantalla y miniaturas en recientes
        window.setFlags(FLAG_SECURE, FLAG_SECURE)
        val actions = ActivityActions(
            activate = ::activate,
            disconnect = ::disconnect,
            requestBatteryExemption = { runCatching { batteryExemption.launch(BatteryOptimization.requestIntent(this)) } },
            requestNotifications = ::requestNotifications,
        )
        setContent { GhostTheme { GhostRoot(container, actions) } }
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
        if (needsNotificationPermission()) {
            notificationPermissionThenStart.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startGhostService()
        }
    }

    private fun requestNotifications() {
        if (needsNotificationPermission()) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun startGhostService() {
        ContextCompat.startForegroundService(this, GhostService.startIntent(this))
    }

    private fun disconnect() {
        // Si el servicio no está en marcha no hace nada; su onDestroy detiene el motor.
        stopService(GhostService.stopIntent(this))
        container.stop()
    }
}
