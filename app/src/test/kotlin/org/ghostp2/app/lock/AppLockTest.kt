package org.ghostp2.app.lock

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {
    private var now = 1_000_000L
    private val lock = AppLock(clock = { now })

    private fun minutes(n: Int) = n * 60_000L

    @Test
    fun coldStartLocksWhenEnabled() {
        lock.configure(enabled = true, afterMinutes = 1)
        assertTrue(lock.locked.value)
        lock.unlock()
        assertFalse(lock.locked.value)
        // Las siguientes configuraciones solo cambian los parámetros.
        lock.configure(enabled = true, afterMinutes = 5)
        assertFalse(lock.locked.value)
    }

    @Test
    fun coldStartWithLockDisabledIsUnlocked() {
        // Sin configurar, falla cerrado; la primera configuración sin bloqueo abre.
        assertTrue(lock.locked.value)
        lock.configure(enabled = false, afterMinutes = 1)
        assertFalse(lock.locked.value)
    }

    @Test
    fun shortBackgroundStaysUnlocked() {
        lock.configure(enabled = true, afterMinutes = 1)
        lock.unlock()
        lock.onStop(changingConfigurations = false)
        now += minutes(1) - 1
        lock.onStart()
        assertFalse(lock.locked.value)
    }

    @Test
    fun longBackgroundLocks() {
        lock.configure(enabled = true, afterMinutes = 5)
        lock.unlock()
        lock.onStop(changingConfigurations = false)
        now += minutes(5)
        lock.onStart()
        assertTrue(lock.locked.value)
    }

    @Test
    fun immediateLocksOnAnyStop() {
        lock.configure(enabled = true, afterMinutes = 0)
        lock.unlock()
        lock.onStop(changingConfigurations = false)
        lock.onStart()
        assertTrue(lock.locked.value)
    }

    @Test
    fun configurationChangeDoesNotLock() {
        lock.configure(enabled = true, afterMinutes = 0)
        lock.unlock()
        lock.onStop(changingConfigurations = true)
        now += minutes(30)
        lock.onStart()
        assertFalse(lock.locked.value)
    }

    @Test
    fun enablingLaterDoesNotLockImmediately() {
        lock.configure(enabled = false, afterMinutes = 1)
        lock.configure(enabled = true, afterMinutes = 1)
        assertFalse(lock.locked.value)
        // Pero sí al volver tras el plazo.
        lock.onStop(changingConfigurations = false)
        now += minutes(1)
        lock.onStart()
        assertTrue(lock.locked.value)
    }

    @Test
    fun disablingUnlocks() {
        lock.configure(enabled = true, afterMinutes = 1)
        assertTrue(lock.locked.value)
        lock.configure(enabled = false, afterMinutes = 1)
        assertFalse(lock.locked.value)
        // Sin bloqueo, volver tras mucho tiempo no bloquea.
        lock.onStop(changingConfigurations = false)
        now += minutes(60)
        lock.onStart()
        assertFalse(lock.locked.value)
    }

    @Test
    fun missingDeviceLockUnlocksAndRaisesNotice() {
        lock.configure(enabled = true, afterMinutes = 0)
        lock.disableForMissingDeviceLock()
        assertFalse(lock.locked.value)
        assertTrue(lock.missingDeviceLockNotice.value)
        // Hasta que los ajustes lo confirmen, ya no bloquea al volver.
        lock.onStop(changingConfigurations = false)
        lock.onStart()
        assertFalse(lock.locked.value)
        lock.missingDeviceLockNoticeShown()
        assertFalse(lock.missingDeviceLockNotice.value)
    }
}
