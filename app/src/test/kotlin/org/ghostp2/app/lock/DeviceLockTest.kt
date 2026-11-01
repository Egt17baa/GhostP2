package org.ghostp2.app.lock

import androidx.biometric.BiometricManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceLockTest {
    @Test
    fun onlyNoneEnrolledOrNoHardwareWithoutCredentialMeansNoScreenLock() {
        assertFalse(DeviceLock.availableFor(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) { false })
        assertFalse(DeviceLock.availableFor(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) { false })
        // Con credencial del dispositivo sí hay bloqueo.
        assertTrue(DeviceLock.availableFor(BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) { true })
        assertTrue(DeviceLock.availableFor(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE) { true })
    }

    @Test
    fun unknownStatusesFailClosed() {
        listOf(
            BiometricManager.BIOMETRIC_SUCCESS,
            BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE,
            BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED,
            BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED,
            BiometricManager.BIOMETRIC_STATUS_UNKNOWN,
            12345,
        ).forEach { status ->
            // Ni siquiera se consulta la credencial: el bloqueo sigue activo.
            assertTrue("$status", DeviceLock.availableFor(status) { error("no se consulta") })
        }
    }
}
