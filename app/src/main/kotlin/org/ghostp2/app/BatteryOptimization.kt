package org.ghostp2.app

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.core.net.toUri
import java.util.Locale

/**
 * Exención de la optimización de batería (spec §6.9): sin ella, Doze corta las conexiones de Tor y no
 * llegan mensajes con la pantalla apagada.
 */
object BatteryOptimization {
    fun isIgnoring(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * Diálogo del sistema para eximir a GhostP2. Uso justificado (lint `BatteryLife`): mensajería P2P sin
     * servidor ni push (sin Google Play Services), que solo recibe si mantiene la conexión con Tor.
     */
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())

    /** Lista del sistema con las apps y su optimización de batería (sin diálogo directo). */
    fun settingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /**
     * Pide la exención con [launch]: primero el diálogo directo ([requestIntent]); algunos fabricantes lo
     * quitan (`ActivityNotFoundException`) y entonces se abre la lista del sistema ([settingsIntent]),
     * donde el usuario elige GhostP2 a mano. Si tampoco existe, no hay nada que abrir.
     */
    fun launchRequest(context: Context, launch: (Intent) -> Unit) {
        try {
            launch(requestIntent(context))
        } catch (e: ActivityNotFoundException) {
            try {
                launch(settingsIntent())
            } catch (e: ActivityNotFoundException) {
                // Ni diálogo ni lista: queda la ayuda del fabricante que muestra la bienvenida.
            }
        }
    }

    /**
     * Ayuda para el ahorro de energía propio del fabricante ([android.os.Build.MANUFACTURER]), que la
     * exención de Android no desactiva: dónde dejar GhostP2 sin restricciones. Sin distinguir mayúsculas.
     */
    @StringRes
    fun manufacturerHint(manufacturer: String): Int = when (manufacturer.trim().lowercase(Locale.ROOT)) {
        "xiaomi", "redmi", "poco" -> R.string.battery_hint_xiaomi
        "samsung" -> R.string.battery_hint_samsung
        "huawei", "honor" -> R.string.battery_hint_huawei
        "oneplus", "oppo", "realme" -> R.string.battery_hint_oppo
        else -> R.string.battery_hint_generic
    }
}
