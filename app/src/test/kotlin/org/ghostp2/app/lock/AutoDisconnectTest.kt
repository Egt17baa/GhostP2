package org.ghostp2.app.lock

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoDisconnectTest {
    private val scope = TestScope()

    /** Tiempo que pasa sin que el temporizador avance (Android lo retrasa con el móvil dormido). */
    private var asleep = 0L

    private var calls = 0

    /** Reloj `elapsedRealtime`: sigue al planificador de pruebas más el tiempo [asleep]. */
    private fun now() = 1_000_000L + scope.testScheduler.currentTime + asleep

    /** Plazo armado guardado fuera del proceso (en producción, un archivo en `noBackupFilesDir`). */
    private val store = MemoryStore()

    /** Número de arranque del móvil (`Settings.Global.BOOT_COUNT`); `null` si no se puede leer. */
    private var boot: Int? = BOOT

    private fun autoDisconnect() = AutoDisconnect(scope, clock = ::now, boot = { boot }, store = store) { calls++ }

    private val auto = autoDisconnect()

    private fun minutes(n: Int) = n * 60_000L

    private fun advance(millis: Long) {
        scope.advanceTimeBy(millis)
        scope.runCurrent()
    }

    @Test
    fun expiryDisconnects() {
        auto.configure(2)
        auto.onStop(changingConfigurations = false)
        advance(minutes(2) - 1)
        assertEquals(0, calls)
        advance(1)
        assertEquals(1, calls)
        advance(minutes(30))
        assertEquals(1, calls)
    }

    @Test
    fun returningCancels() {
        auto.configure(2)
        auto.onStop(changingConfigurations = false)
        advance(minutes(1))
        auto.onStart()
        advance(minutes(5))
        assertEquals(0, calls)
    }

    @Test
    fun expiredOnReturnDisconnects() {
        auto.configure(2)
        auto.onStop(changingConfigurations = false)
        // El temporizador no llega a dispararse: el reloj sí avanza.
        asleep += minutes(3)
        auto.onStart()
        assertEquals(1, calls)
        // El temporizador cancelado no vuelve a llamar.
        advance(minutes(30))
        assertEquals(1, calls)
    }

    @Test
    fun configurationChangeDoesNotArm() {
        auto.configure(2)
        auto.onStop(changingConfigurations = true)
        advance(minutes(20))
        auto.onStart()
        assertEquals(0, calls)
    }

    @Test
    fun neverDoesNothing() {
        auto.configure(0)
        auto.onStop(changingConfigurations = false)
        advance(minutes(60))
        asleep += minutes(60)
        auto.onStart()
        assertEquals(0, calls)
        // Sin configurar es «Nunca».
        val fresh = AutoDisconnect(scope, clock = ::now) { calls++ }
        fresh.onStop(changingConfigurations = false)
        advance(minutes(60))
        fresh.onStart()
        assertEquals(0, calls)
    }

    @Test
    fun changingToNeverCancelsArmedTimer() {
        auto.configure(2)
        auto.onStop(changingConfigurations = false)
        advance(minutes(1))
        auto.configure(0)
        advance(minutes(5))
        asleep += minutes(5)
        auto.onStart()
        assertEquals(0, calls)
    }

    @Test
    fun onlyOncePerExit() {
        auto.configure(2)
        auto.onStop(changingConfigurations = false)
        advance(minutes(3))
        assertEquals(1, calls)
        auto.onStart()
        assertEquals(1, calls)
        // Otra salida vuelve a armarlo.
        auto.onStop(changingConfigurations = false)
        advance(minutes(2))
        assertEquals(2, calls)
    }

    @Test
    fun noStopNoTimer() {
        // Arranque al encender (R20) o reinicio STICKY: la actividad no ha pasado por onStop.
        auto.configure(2)
        advance(minutes(30))
        asleep += minutes(30)
        auto.onStart()
        assertEquals(0, calls)
    }

    /** I2: el temporizador cuenta el tiempo dormido; corta en el primer despertar tras el plazo. */
    @Test
    fun countsTimeAsleep() {
        auto.configure(2)
        auto.onStop(changingConfigurations = false)
        advance(1)
        // El móvil duerme 3 minutos: el planificador (tiempo despierto) apenas avanza.
        asleep += minutes(3)
        scope.runCurrent()
        assertEquals(0, calls)
        // Al siguiente despertar (como mucho 10 s de tiempo despierto) se corta sin volver a la app.
        advance(10_000)
        assertEquals(1, calls)
        assertTrue(auto.returnHome.value)
        advance(minutes(30))
        assertEquals(1, calls)
    }

    /** M5: al volver cuenta el plazo con el que se armó, no el actual. */
    @Test
    fun returnUsesTheTimeoutArmedWith() {
        auto.configure(2)
        auto.onStop(changingConfigurations = false)
        auto.configure(15)
        asleep += minutes(3)
        auto.onStart()
        assertEquals(1, calls)
    }

    @Test
    fun armingIsStoredAndReturningClearsIt() {
        auto.configure(2)
        val at = now()
        auto.onStop(changingConfigurations = false)
        assertEquals(Armed(BOOT, at + minutes(2), 2), store.armed)
        auto.onStart()
        assertNull(store.armed)
    }

    @Test
    fun storedDeadlineIsClearedAfterDisconnectNeverAndCancel() {
        auto.configure(2)
        auto.onStop(changingConfigurations = false)
        advance(minutes(2))
        assertEquals(1, calls)
        assertNull(store.armed)

        auto.onStop(changingConfigurations = false)
        auto.configure(0)
        assertNull(store.armed)

        auto.configure(2)
        auto.onStop(changingConfigurations = false)
        // «Desconectar» (en la app o en la notificación).
        auto.cancel()
        assertNull(store.armed)
        advance(minutes(30))
        assertEquals(1, calls)
    }

    @Test
    fun withoutBootCountNothingIsStored() {
        boot = null
        auto.configure(2)
        auto.onStop(changingConfigurations = false)
        assertNull(store.armed)
    }

    /** I1: reinicio STICKY (proceso nuevo) con el plazo ya vencido: hay que desconectar sin arrancar. */
    @Test
    fun restartWithExpiredDeadlineDisconnects() {
        store.armed = Armed(BOOT, now() - 1, 2)
        val restarted = autoDisconnect()
        assertTrue(restarted.resumeAfterRestart())
        assertNull(store.armed)
        assertTrue(restarted.returnHome.value)
        // Quien llama para el servicio y el motor; el temporizador no llama además.
        advance(minutes(30))
        assertEquals(0, calls)
    }

    /** I1: reinicio STICKY con el plazo aún por vencer: se vuelve a armar por lo que queda. */
    @Test
    fun restartWithFutureDeadlineRearms() {
        store.armed = Armed(BOOT, now() + minutes(1), 2)
        val restarted = autoDisconnect()
        assertFalse(restarted.resumeAfterRestart())
        advance(minutes(1) - 1)
        assertEquals(0, calls)
        advance(1)
        assertEquals(1, calls)
        assertNull(store.armed)
    }

    /** Volver a la app tras un reinicio STICKY con el plazo rearmado: cuenta desde la salida original. */
    @Test
    fun returnAfterRearmUsesTheOriginalExit() {
        store.armed = Armed(BOOT, now() + minutes(1), 2)
        val restarted = autoDisconnect()
        assertFalse(restarted.resumeAfterRestart())
        asleep += minutes(2)
        restarted.onStart()
        assertEquals(1, calls)
        assertNull(store.armed)
    }

    /** Un plazo de otro arranque del móvil (elapsedRealtime empezó de cero) no vale. */
    @Test
    fun deadlineFromAnotherBootIsIgnored() {
        store.armed = Armed(BOOT - 1, now() - 1, 2)
        val restarted = autoDisconnect()
        assertFalse(restarted.resumeAfterRestart())
        assertNull(store.armed)
        advance(minutes(30))
        assertEquals(0, calls)
        assertFalse(restarted.returnHome.value)
    }

    @Test
    fun implausibleStoredDeadlineIsIgnored() {
        // Armado «en el futuro» (el reloj fue hacia atrás) o sin plazo.
        listOf(Armed(BOOT, now() + minutes(5), 2), Armed(BOOT, now() - 1, 0)).forEach {
            store.armed = it
            assertFalse(autoDisconnect().resumeAfterRestart())
            assertNull(store.armed)
        }
        boot = null
        store.armed = Armed(BOOT, now() - 1, 2)
        assertFalse(autoDisconnect().resumeAfterRestart())
        advance(minutes(30))
        assertEquals(0, calls)
    }

    /** Arranque al encender (R20): borra un plazo guardado, sin actuar sobre él. */
    @Test
    fun bootStartDropsStoredDeadline() {
        store.armed = Armed(BOOT, now() - 1, 2)
        val booted = autoDisconnect()
        booted.onBootStart()
        assertNull(store.armed)
        assertFalse(booted.resumeAfterRestart())
        advance(minutes(30))
        assertEquals(0, calls)
    }

    /** M1: tras un corte, la interfaz vuelve una vez a la lista con «Activar». */
    @Test
    fun returnHomeAfterDisconnectUntilShown() {
        assertFalse(auto.returnHome.value)
        auto.configure(2)
        auto.onStop(changingConfigurations = false)
        asleep += minutes(3)
        auto.onStart()
        assertTrue(auto.returnHome.value)
        auto.returnedHome()
        assertFalse(auto.returnHome.value)
        // Volver antes del plazo no cambia nada.
        auto.onStop(changingConfigurations = false)
        auto.onStart()
        assertFalse(auto.returnHome.value)
    }

    /**
     * m1: la app se abre en un proceso nuevo antes de que llegue el reinicio STICKY retrasado y el plazo
     * guardado ya venció: se desconecta al volver y el reinicio pendiente se detiene sin arrancar.
     */
    @Test
    fun openingWithExpiredStoredDeadlineDisconnectsAndStopsThePendingRestart() {
        store.armed = Armed(BOOT, now() - 1, 2)
        val reopened = autoDisconnect()
        reopened.configure(2)
        reopened.onStart()
        assertEquals(1, calls)
        assertNull(store.armed)
        assertTrue(reopened.returnHome.value)
        reopened.returnedHome()
        // El reinicio STICKY que seguía pendiente: se detiene (quien llama no arranca nada).
        assertTrue(reopened.resumeAfterRestart())
        assertFalse(reopened.returnHome.value)
        assertEquals(1, calls)
        // Solo ese reinicio pendiente.
        assertFalse(reopened.resumeAfterRestart())
        advance(minutes(30))
        assertEquals(1, calls)
    }

    /** m1, en el otro orden: el reinicio STICKY llega primero y se detiene; abrir la app no corta otra vez. */
    @Test
    fun restartFirstThenOpeningDisconnectsOnce() {
        store.armed = Armed(BOOT, now() - 1, 2)
        val restarted = autoDisconnect()
        assertTrue(restarted.resumeAfterRestart())
        restarted.onStart()
        assertEquals(0, calls)
        assertNull(store.armed)
        assertTrue(restarted.returnHome.value)
    }

    /** m1: abrir la app en un proceso nuevo antes de que venza el plazo guardado lo borra sin cortar. */
    @Test
    fun openingBeforeStoredDeadlineClearsIt() {
        store.armed = Armed(BOOT, now() + minutes(1), 2)
        val reopened = autoDisconnect()
        reopened.onStart()
        assertNull(store.armed)
        assertFalse(reopened.resumeAfterRestart())
        advance(minutes(30))
        asleep += minutes(30)
        assertEquals(0, calls)
        assertFalse(reopened.returnHome.value)
    }

    /** m1: al abrir la app, un plazo guardado se comprueba igual que en el reinicio STICKY. */
    @Test
    fun openingIgnoresImplausibleStoredDeadline() {
        val implausible = listOf(
            Armed(BOOT - 1, now() - 1, 2),
            Armed(BOOT, now() + minutes(5), 2),
            Armed(BOOT, now() - 1, 0),
            Armed(BOOT, now() - 1, 3),
        )
        implausible.forEach {
            store.armed = it
            val reopened = autoDisconnect()
            reopened.onStart()
            assertNull(store.armed)
            assertFalse(reopened.resumeAfterRestart())
        }
        boot = null
        store.armed = Armed(BOOT, now() - 1, 2)
        autoDisconnect().onStart()
        assertNull(store.armed)
        assertEquals(0, calls)
    }

    /** m3: unos minutos guardados que no son de [org.ghostp2.data.AppSettings.DISCONNECT_MINUTES] no valen. */
    @Test
    fun storedMinutesOutsideTheChoicesAreIgnored() {
        listOf(Armed(BOOT, now() - 1, 3), Armed(BOOT, now() + minutes(1), 1_000_000)).forEach {
            store.armed = it
            assertFalse(autoDisconnect().resumeAfterRestart())
            assertNull(store.armed)
        }
        advance(minutes(30))
        assertEquals(0, calls)
    }

    private class MemoryStore : ArmedStore {
        var armed: Armed? = null

        override fun load(): Armed? = armed

        override fun save(armed: Armed) {
            this.armed = armed
        }

        override fun clear() {
            armed = null
        }
    }

    private companion object {
        const val BOOT = 7
    }
}
