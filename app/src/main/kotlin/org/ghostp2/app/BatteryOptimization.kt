package org.ghostp2.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings
import androidx.core.net.toUri

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
}
