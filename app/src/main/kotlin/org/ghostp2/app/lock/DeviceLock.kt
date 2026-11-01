package org.ghostp2.app.lock

import android.app.KeyguardManager
import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL

/** Si el móvil puede desbloquear GhostP2: huella (u otra biometría) o el PIN, patrón o contraseña. */
object DeviceLock {
    /** Huella o credencial del dispositivo; esta combinación funciona desde API 26. */
    const val AUTHENTICATORS = BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /**
     * Falso solo si el móvil seguro que no tiene bloqueo de pantalla (ver [availableFor]); ante cualquier
     * otra respuesta, verdadero: el bloqueo de GhostP2 sigue activo (falla cerrado).
     */
    fun available(context: Context): Boolean {
        val status = BiometricManager.from(context).canAuthenticate(AUTHENTICATORS)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        // Sin KeyguardManager no se sabe: se supone que sí hay bloqueo.
        return availableFor(status) { keyguard?.isDeviceSecure ?: true }
    }

    /**
     * "Sin bloqueo de pantalla" solo con `BIOMETRIC_ERROR_NONE_ENROLLED` o `BIOMETRIC_ERROR_NO_HARDWARE`
     * y además sin credencial del dispositivo (`deviceSecure` falso). Cualquier otro estado (éxito,
     * hardware ocupado, actualización pendiente, desconocido…) cuenta como disponible.
     */
    internal fun availableFor(status: Int, deviceSecure: () -> Boolean): Boolean = when (status) {
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> deviceSecure()
        else -> true
    }
}
