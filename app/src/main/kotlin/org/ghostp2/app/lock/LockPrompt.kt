package org.ghostp2.app.lock

import androidx.annotation.VisibleForTesting
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * El único `BiometricPrompt` de la actividad (R22): huella o credencial del dispositivo y sin botón
 * negativo. Se crea en `onCreate` de [org.ghostp2.app.MainActivity]; tras girar el móvil, el nuevo
 * sustituye al anterior como receptor del resultado (biometric 1.1.0 guarda el diálogo en curso en un
 * fragmento de la actividad y su estado en un `ViewModel`).
 *
 * - [show] no hace nada si ya hay un diálogo (fragmento con [BIOMETRIC_FRAGMENT_TAG]: doble pulsación o
 *   cambio de configuración) o si la actividad ya guardó su estado (tras `onStop`).
 * - [showOnResume] además se salta la reanudación que es la vuelta del propio diálogo: si estaba abierto
 *   al pausarse la actividad (credencial en otra pantalla o diálogo del sistema) o acabó después. Así,
 *   cancelar no lo vuelve a abrir en bucle; "Desbloquear" o volver a la app sí.
 */
class LockPrompt(
    private val activity: FragmentActivity,
    private val onSucceeded: () -> Unit,
    /** Error de "sin bloqueo de pantalla" del diálogo: el llamante vuelve a comprobarlo antes de desactivar. */
    private val onNoDeviceLock: () -> Unit,
) {
    private val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = handleSucceeded()

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = handleError(errorCode)
        },
    )

    /** La próxima [showOnResume] no pide nada (ver la clase). Solo en el hilo principal. */
    private var skipNextResume = false

    init {
        activity.lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE) skipNextResume = showing
            },
        )
    }

    /** Hay un diálogo en curso (su fragmento sigue en la actividad). */
    val showing: Boolean
        get() = activity.supportFragmentManager.findFragmentByTag(BIOMETRIC_FRAGMENT_TAG) != null

    /** Pide el desbloqueo; `false` si no se ha abierto (ya había uno o el estado estaba guardado). */
    fun show(title: String): Boolean {
        if (showing || activity.supportFragmentManager.isStateSaved) return false
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(DeviceLock.AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
        return true
    }

    /** [show] al reanudarse la actividad, salvo si es la vuelta del propio diálogo. */
    fun showOnResume(title: String): Boolean {
        if (skipNextResume) {
            skipNextResume = false
            return false
        }
        return show(title)
    }

    @VisibleForTesting
    internal fun handleSucceeded() {
        skipNextResume = true
        onSucceeded()
    }

    /** Cancelar o fallar deja la app bloqueada; solo los errores de "sin bloqueo" llaman a [onNoDeviceLock]. */
    @VisibleForTesting
    internal fun handleError(errorCode: Int) {
        skipNextResume = true
        if (errorCode in NO_DEVICE_LOCK_ERRORS) onNoDeviceLock()
    }

    private companion object {
        val NO_DEVICE_LOCK_ERRORS = setOf(
            BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
            BiometricPrompt.ERROR_NO_BIOMETRICS,
            BiometricPrompt.ERROR_HW_NOT_PRESENT,
        )
    }
}

/** Etiqueta del fragmento con el que biometric 1.1.0 muestra el diálogo (privada en la librería). */
internal const val BIOMETRIC_FRAGMENT_TAG = "androidx.biometric.BiometricFragment"

/** El [LockPrompt] de la actividad; `null` fuera de [org.ghostp2.app.MainActivity] (p. ej. en las pruebas). */
val LocalLockPrompt = staticCompositionLocalOf<LockPrompt?> { null }
