package org.ghostp2.app.lock

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Desconectar al salir de la app" (R29). Vive lo que el proceso, en el [org.ghostp2.app.AppContainer],
 * hermano de [AppLock] y alimentado igual (desde los ajustes y los `onStart`/`onStop` de la actividad).
 *
 * - [onStop] sin cambio de configuración (girar el móvil no es salir) y con un plazo configurado arma un
 *   temporizador en [scope]; al vencer llama a [disconnect].
 * - [onStart] lo cancela y, si el plazo ya venció según [clock] (Android puede retrasar el temporizador
 *   con el móvil dormido), llama a [disconnect] antes de nada.
 * - [disconnect] se llama como mucho una vez por salida. Sin [onStop] en esta ejecución (arranque al
 *   encender, R20, o reinicio STICKY sin abrir la app) no hay temporizador.
 * - Hasta la primera [configure], o con 0, está desactivado ("Nunca"); pasar a 0 cancela un
 *   temporizador armado.
 *
 * El reloj es `elapsedRealtime`: cuenta también el tiempo con el móvil dormido y no cambia con la hora.
 */
class AutoDisconnect(
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val disconnect: () -> Unit,
) {
    /** Plazo según la última [configure]; 0 = "Nunca". */
    @get:Synchronized
    var minutes = 0
        private set

    // Protegidos por `this`.
    private var stoppedAt: Long? = null
    private var timer: Job? = null

    /** Cambia con cada salida: un temporizador de una salida anterior no desconecta. */
    private var exit = 0L

    @Synchronized
    fun configure(minutes: Int) {
        this.minutes = minutes.coerceAtLeast(0)
        if (this.minutes == 0) disarm()
    }

    @Synchronized
    fun onStop(changingConfigurations: Boolean) {
        if (changingConfigurations || minutes == 0) return
        disarm()
        val mine = ++exit
        val afterMillis = minutes * MILLIS_PER_MINUTE
        stoppedAt = clock()
        timer = scope.launch {
            delay(afterMillis)
            if (claim(mine)) disconnect()
        }
    }

    fun onStart() {
        val expired = synchronized(this) {
            val since = stoppedAt
            val afterMillis = minutes * MILLIS_PER_MINUTE
            disarm()
            since != null && afterMillis > 0 && clock() - since >= afterMillis
        }
        if (expired) disconnect()
    }

    /** El temporizador de la salida [mine] venció y nadie ha vuelto: se queda con la desconexión. */
    @Synchronized
    private fun claim(mine: Long): Boolean {
        if (exit != mine || stoppedAt == null) return false
        stoppedAt = null
        timer = null
        return true
    }

    /** Con `this` tomado. */
    private fun disarm() {
        exit++
        stoppedAt = null
        timer?.cancel()
        timer = null
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
    }
}
