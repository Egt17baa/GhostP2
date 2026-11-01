package org.ghostp2.app.lock

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** Si el móvil puede desbloquear GhostP2: huella (u otra biometría) o el PIN, patrón o contraseña. */
object DeviceLock {
    /** Huella o credencial del dispositivo; esta combinación funciona desde API 26. */
    const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun available(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS
}
