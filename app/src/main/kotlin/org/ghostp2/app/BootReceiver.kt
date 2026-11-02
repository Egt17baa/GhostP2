package org.ghostp2.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Activar GhostP2 al encender el móvil" (R20, desactivado por defecto; spec §6.9). Solo atiende
 * `ACTION_BOOT_COMPLETED` (una difusión protegida: solo la envía el sistema). Con `goAsync()` comprueba
 * el almacenamiento y lee los ajustes del [AppContainer]; si `startOnBoot && onboardingDone`, arranca
 * [GhostService] en primer plano con [GhostService.EXTRA_FROM_BOOT]. Si el almacenamiento o la lectura
 * fallan, o tardan más de [READ_TIMEOUT_MILLIS], no hace nada (falla cerrado: GhostP2 queda apagado).
 *
 * El servicio es `specialUse`: Android 14 y 15 permiten arrancar servicios en primer plano desde
 * `BOOT_COMPLETED` salvo los tipos `dataSync`, `camera`, `mediaPlayback`, `phoneCall`,
 * `mediaProjection` y `microphone`. Si aun así el sistema lo rechaza
 * (`ForegroundServiceStartNotAllowedException`, un `IllegalStateException`), no se reintenta: se deja
 * la notificación "GhostP2 se ha detenido · abre la app".
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext
        val container = app.container
        // Nulo si no lo entrega el sistema (p. ej. en pruebas que llaman a onReceive directamente).
        val pending: PendingResult? = goAsync()
        lastWork = container.scope.launch {
            try {
                if (withTimeoutOrNull(READ_TIMEOUT_MILLIS) { shouldStart(container) } == true) start(app)
            } finally {
                pending?.finish()
            }
        }
    }

    private suspend fun shouldStart(container: AppContainer): Boolean =
        try {
            container.checkStorage() == StorageState.Ok &&
                container.settings.get().let { it.startOnBoot && it.onboardingDone }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            false
        }

    private fun start(context: Context) {
        try {
            ContextCompat.startForegroundService(context, GhostService.startIntent(context, fromBoot = true))
        } catch (_: IllegalStateException) {
            // El sistema no deja arrancarlo ahora: apagado y con el aviso para abrir la app.
            try {
                Notifications.postStopped(context)
            } catch (_: Exception) {
                // Sin aviso; GhostP2 sigue apagado.
            }
        }
    }

    companion object {
        /** Margen dentro de los ~10 s que el sistema da a un receptor con `goAsync()`. */
        const val READ_TIMEOUT_MILLIS = 8_000L

        /** Último trabajo lanzado (para que las pruebas esperen a que termine). */
        @VisibleForTesting
        @Volatile
        internal var lastWork: Job? = null
    }
}
