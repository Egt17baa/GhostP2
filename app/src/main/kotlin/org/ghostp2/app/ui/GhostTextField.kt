package org.ghostp2.app.ui

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.text.input.ImeAction

/**
 * Único campo de texto de la app ("teclado de incógnito"): pide al teclado que no aprenda de lo escrito
 * (`IME_FLAG_NO_PERSONALIZED_LEARNING`, ver [NoLearningRequest]) y desactiva la autocorrección. Ninguna
 * otra pantalla usa `TextField`, `OutlinedTextField` ni `BasicTextField` directamente (lo comprueba
 * `SourceRulesTest`). El texto lo guarda quien llama, en memoria: nunca en estado guardable.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun GhostTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    singleLine: Boolean = false,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: () -> Unit = {},
) {
    InterceptPlatformTextInput(interceptor = NoLearningInterceptor) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            label = label?.let { { Text(it) } },
            singleLine = singleLine,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = imeAction),
            // Primero lo de quien llama y después lo normal de la acción (pasar al siguiente campo, cerrar el teclado…).
            keyboardActions = KeyboardActions(onAny = {
                onImeAction()
                defaultKeyboardAction(imeAction)
            }),
        )
    }
}

/** Sustituye cada petición de teclado por una [NoLearningRequest]. */
@OptIn(ExperimentalComposeUiApi::class)
private val NoLearningInterceptor = PlatformTextInputInterceptor { request, nextHandler ->
    nextHandler.startInputMethod(NoLearningRequest(request))
}

/**
 * Petición de teclado que, tras la del campo ([delegate]), añade `IME_FLAG_NO_PERSONALIZED_LEARNING` a
 * `imeOptions` conservando los bits que puso el campo (acción, etc.).
 */
internal class NoLearningRequest(private val delegate: PlatformTextInputMethodRequest) : PlatformTextInputMethodRequest {
    override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
        delegate.createInputConnection(outAttributes).also {
            outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        }
}
