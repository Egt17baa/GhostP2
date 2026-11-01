package org.ghostp2.app.lock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.first
import org.ghostp2.app.R

/**
 * Pantalla de bloqueo (R22): "GhostP2 está bloqueado" y "Desbloquear". Al mostrarse (con la actividad
 * ya en primer plano) y al pulsar "Desbloquear" pide la huella o la credencial del dispositivo con
 * `BiometricPrompt`. Si el móvil ya no tiene bloqueo de pantalla ([deviceLockAvailable] falso) no pide
 * nada y llama a [onNoDeviceLock]: GhostP2 nunca queda inaccesible.
 */
@Composable
fun LockScreen(
    onUnlocked: () -> Unit,
    onNoDeviceLock: () -> Unit,
    deviceLockAvailable: (Context) -> Boolean = DeviceLock::available,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val title = stringResource(R.string.lock_prompt_title)
    val currentOnUnlocked by rememberUpdatedState(onUnlocked)
    val currentOnNoDeviceLock by rememberUpdatedState(onNoDeviceLock)
    val currentAvailable by rememberUpdatedState(deviceLockAvailable)

    fun unlock() {
        if (!currentAvailable(context)) {
            currentOnNoDeviceLock()
            return
        }
        // Sin FragmentActivity (p. ej. la actividad vacía de las pruebas) no hay diálogo: queda bloqueado.
        val activity = context.findFragmentActivity() ?: return
        authenticate(
            activity,
            title,
            onSucceeded = { currentOnUnlocked() },
            onNoDeviceLock = { if (!currentAvailable(context)) currentOnNoDeviceLock() },
        )
    }

    LaunchedEffect(Unit) {
        if (!currentAvailable(context)) {
            currentOnNoDeviceLock()
            return@LaunchedEffect
        }
        // Tras onStop el FragmentManager ya guardó su estado y BiometricPrompt no se mostraría.
        lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
        unlock()
    }

    Column(
        modifier = Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(R.string.lock_title),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = ::unlock) { Text(stringResource(R.string.lock_unlock)) }
    }
}

/**
 * Muestra `BiometricPrompt` con huella o credencial del dispositivo y sin botón negativo (la credencial
 * hace de alternativa). Cancelar o fallar deja la pantalla bloqueada; "Desbloquear" vuelve a pedirlo.
 */
private fun authenticate(
    activity: FragmentActivity,
    title: String,
    onSucceeded: () -> Unit,
    onNoDeviceLock: () -> Unit,
) {
    val callback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSucceeded()

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            // El bloqueo de pantalla se quitó entre la comprobación y el diálogo.
            if (errorCode in NO_DEVICE_LOCK_ERRORS) onNoDeviceLock()
        }
    }
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle(title)
        .setAllowedAuthenticators(DeviceLock.AUTHENTICATORS)
        .build()
    BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
}

private val NO_DEVICE_LOCK_ERRORS = setOf(
    BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL,
    BiometricPrompt.ERROR_NO_BIOMETRICS,
    BiometricPrompt.ERROR_HW_NOT_PRESENT,
)

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
