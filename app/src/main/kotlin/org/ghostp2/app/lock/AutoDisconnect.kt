package org.ghostp2.app.lock

import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * "Desconectar al salir de la app" (R29). Vive lo que el proceso, en el [org.ghostp2.app.AppContainer],
 * hermano de [AppLock] y alimentado igual (desde los ajustes y los `onStart`/`onStop` de la actividad).
 *
 * - [onStop] sin cambio de configuración (girar el móvil no es salir) y con un plazo configurado arma un
 *   temporizador en [scope]; al vencer llama a [disconnect]. El temporizador espera en tramos cortos y
 *   en cada despertar mira [clock]: con el móvil dormido corta en el primer despertar tras el plazo.
 * - [onStart] lo cancela y, si el plazo ya venció según [clock], llama a [disconnect] antes de nada. El
 *   plazo es el que había al armarlo, no el actual.
 * - [disconnect] se llama como mucho una vez por salida. Tras un corte, [returnHome] queda a `true`
 *   hasta [returnedHome]: la interfaz vuelve a la lista con "Activar".
 * - El plazo armado se guarda en [store] con el número de arranque del móvil ([boot]): si Android mata
 *   el proceso y lo recrea con `START_STICKY`, [resumeAfterRestart] lo retoma. Un plazo de otro
 *   arranque (el reloj empezó de cero) no vale. Sin [boot] no se guarda nada.
 * - Hasta la primera [configure], o con 0, está desactivado ("Nunca"); pasar a 0 cancela un
 *   temporizador armado.
 *
 * El reloj es `elapsedRealtime`: cuenta también el tiempo con el móvil dormido y no cambia con la hora.
 */
class AutoDisconnect(
    private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val boot: () -> Int? = { null },
    private val store: ArmedStore = ArmedStore.None,
    private val disconnect: () -> Unit,
) {
    /** Plazo según la última [configure]; 0 = "Nunca". */
    @get:Synchronized
    var minutes = 0
        private set

    // Protegidos por `this`.
    private var stoppedAt: Long? = null
    private var afterMillis = 0L
    private var timer: Job? = null

    /** Tramo máximo de espera del temporizador (tiempo despierto); las pruebas lo acortan. */
    @VisibleForTesting
    @Volatile
    internal var pollMillis = POLL_MILLIS

    /** Cambia con cada salida: un temporizador de una salida anterior no desconecta. */
    private var exit = 0L

    private val mutableReturnHome = MutableStateFlow(false)

    /** Hubo un corte y la interfaz aún no ha vuelto a la lista. */
    val returnHome: StateFlow<Boolean> = mutableReturnHome.asStateFlow()

    /** La interfaz ya ha vuelto a la lista tras el corte. */
    fun returnedHome() {
        mutableReturnHome.value = false
    }

    @Synchronized
    fun configure(minutes: Int) {
        this.minutes = minutes.coerceAtLeast(0)
        if (this.minutes == 0) disarm()
    }

    @Synchronized
    fun onStop(changingConfigurations: Boolean) {
        if (changingConfigurations || minutes == 0) return
        disarm()
        val now = clock()
        arm(now, minutes * MILLIS_PER_MINUTE)
        val bootCount = boot()
        if (bootCount != null) persist { store.save(Armed(bootCount, now + afterMillis, minutes)) }
    }

    fun onStart() {
        val expired = synchronized(this) {
            val since = stoppedAt
            val after = afterMillis
            disarm()
            since != null && after > 0 && clock() - since >= after
        }
        if (expired) cut()
    }

    /** "Desconectar" (en la app o en la notificación): ya no hay nada que cortar. */
    @Synchronized
    fun cancel() = disarm()

    /**
     * Arranque al encender el móvil (R20): un plazo guardado es de antes del reinicio y no se usa. Si en
     * esta ejecución ya se armó uno, se respeta.
     */
    @Synchronized
    fun onBootStart() {
        if (stoppedAt == null) persist { store.clear() }
    }

    /**
     * Reinicio `START_STICKY` (el proceso es nuevo y la actividad no ha pasado por aquí). Con un plazo
     * guardado de este arranque del móvil: si ya venció devuelve `true` (quien llama para el servicio y
     * el motor sin arrancarlos; [disconnect] no se llama); si no, lo vuelve a armar por lo que queda.
     */
    fun resumeAfterRestart(): Boolean {
        val expired = synchronized(this) {
            if (stoppedAt != null) return false
            val armed = persisted { store.load() }
            val now = clock()
            val bootCount = boot()
            val after = (armed?.minutes ?: 0) * MILLIS_PER_MINUTE
            val since = armed?.let { it.deadline - after }
            if (armed == null || bootCount == null || armed.boot != bootCount || after <= 0 || since == null || since > now) {
                persist { store.clear() }
                return false
            }
            if (now >= armed.deadline) {
                disarm()
                true
            } else {
                arm(since, after)
                false
            }
        }
        if (expired) mutableReturnHome.value = true
        return expired
    }

    /** Con `this` tomado y sin temporizador: arma la salida actual desde [since]. */
    private fun arm(since: Long, after: Long) {
        val mine = ++exit
        stoppedAt = since
        afterMillis = after
        timer = scope.launch {
            // `delay` no avanza con el móvil dormido: tramos cortos contra el reloj real.
            while (true) {
                val left = since + after - clock()
                if (left <= 0) break
                delay(minOf(left, pollMillis))
            }
            if (claim(mine)) cut()
        }
    }

    private fun cut() {
        mutableReturnHome.value = true
        disconnect()
    }

    /** El temporizador de la salida [mine] venció y nadie ha vuelto: se queda con la desconexión. */
    @Synchronized
    private fun claim(mine: Long): Boolean {
        if (exit != mine || stoppedAt == null) return false
        stoppedAt = null
        timer = null
        persist { store.clear() }
        return true
    }

    /** Con `this` tomado. */
    private fun disarm() {
        exit++
        persist { store.clear() }
        stoppedAt = null
        timer?.cancel()
        timer = null
    }

    private companion object {
        const val MILLIS_PER_MINUTE = 60_000L
        const val POLL_MILLIS = 10_000L

        /** Un fallo del disco solo deja el plazo en memoria (como antes de guardarlo). */
        inline fun persist(block: () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Sin plazo guardado: un reinicio STICKY no lo retomará.
            }
        }

        inline fun <T> persisted(block: () -> T?): T? = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}

/** Plazo armado de R29: [deadline] en `elapsedRealtime` del arranque [boot] del móvil, con [minutes]. */
data class Armed(val boot: Int, val deadline: Long, val minutes: Int)

/** Dónde se guarda el plazo armado para sobrevivir a que Android mate el proceso. */
interface ArmedStore {
    /** El plazo guardado, o `null` si no hay ninguno o no se puede leer. Puede lanzar. */
    fun load(): Armed?

    fun save(armed: Armed)

    fun clear()

    /** Sin guardar nada. */
    object None : ArmedStore {
        override fun load(): Armed? = null

        override fun save(armed: Armed) = Unit

        override fun clear() = Unit
    }
}
