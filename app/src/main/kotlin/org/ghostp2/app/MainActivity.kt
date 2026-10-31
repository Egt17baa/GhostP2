package org.ghostp2.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager.LayoutParams.FLAG_SECURE
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private val batteryIgnoring = mutableStateOf(false)

    /** Al pulsar Activar en API 33+ se pide el permiso; se arranque o no con él, el servicio arranca. */
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startGhostService() }

    private val batteryExemption =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refreshBattery() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Impide capturas de pantalla y miniaturas en recientes
        window.setFlags(FLAG_SECURE, FLAG_SECURE)
        val actions = DiagnosticActions(
            activate = ::activate,
            disconnect = ::disconnect,
            requestBatteryExemption = { runCatching { batteryExemption.launch(BatteryOptimization.requestIntent(this)) } },
        )
        setContent {
            MaterialTheme {
                Surface {
                    DiagnosticScreen(container, actions, batteryIgnoring.value)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshBattery()
        // Al abrir la app (o volver a ella) se reintenta ya con los contactos desconectados.
        container.onAppOpened()
    }

    private fun refreshBattery() {
        batteryIgnoring.value = BatteryOptimization.isIgnoring(this)
    }

    private fun activate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startGhostService()
        }
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
