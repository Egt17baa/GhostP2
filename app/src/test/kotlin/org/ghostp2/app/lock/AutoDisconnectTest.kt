package org.ghostp2.app.lock

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoDisconnectTest {
    private val scope = TestScope()

    /** Tiempo que pasa sin que el temporizador avance (Android lo retrasa con el móvil dormido). */
    private var asleep = 0L

    private var calls = 0

    /** El reloj sigue al planificador de pruebas más el tiempo [asleep]. */
    private val auto = AutoDisconnect(scope, clock = { 1_000_000L + scope.testScheduler.currentTime + asleep }) { calls++ }

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
        val fresh = AutoDisconnect(scope, clock = { scope.testScheduler.currentTime }) { calls++ }
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
}
