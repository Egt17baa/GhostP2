package org.ghostp2.app.lock

import android.content.Context
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import org.ghostp2.app.R

/**
 * Pantalla de bloqueo (R22): "GhostP2 está bloqueado" y "Desbloquear". Pide la huella o la credencial
 * del dispositivo con el [LockPrompt] de la actividad al reanudarse ésta con la pantalla a la vista
 * (también al componerse ya reanudada) y al pulsar "Desbloquear". El éxito lo recibe el [LockPrompt].
 * Si el móvil ya no tiene bloqueo de pantalla ([deviceLockAvailable] falso) no pide nada y llama a
 * [onNoDeviceLock]: GhostP2 nunca queda inaccesible. Sin [prompt] (fuera de la actividad) no hay diálogo.
 */
@Composable
fun LockScreen(
    onNoDeviceLock: () -> Unit,
    deviceLockAvailable: (Context) -> Boolean = DeviceLock::available,
    prompt: LockPrompt? = LocalLockPrompt.current,
) {
    val context = LocalContext.current
    val title = stringResource(R.string.lock_prompt_title)
    val currentOnNoDeviceLock by rememberUpdatedState(onNoDeviceLock)
    val currentAvailable by rememberUpdatedState(deviceLockAvailable)

    /** Comprueba que el móvil sigue teniendo bloqueo de pantalla y, si lo tiene, `request` pide el diálogo. */
    fun unlock(request: (LockPrompt) -> Unit) {
        if (!currentAvailable(context)) {
            currentOnNoDeviceLock()
            return
        }
        prompt?.let(request)
    }

    LifecycleResumeEffect(prompt) {
        unlock { it.showOnResume(title) }
        onPauseOrDispose { }
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
        Button(onClick = { unlock { it.show(title) } }) { Text(stringResource(R.string.lock_unlock)) }
    }
}
