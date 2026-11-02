package org.ghostp2.app.ui

import android.text.InputType
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
import androidx.compose.ui.text.input.KeyboardType

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
    /** Alto máximo en líneas; con más texto, el campo se desplaza por dentro en vez de crecer. */
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Default,
    onImeAction: () -> Unit = {},
    /** Tipo de teclado; p. ej. `Ascii` para las líneas de «Mis puentes». */
    keyboardType: KeyboardType = KeyboardType.Unspecified,
    /**
     * Pide además al teclado que no muestre sugerencias ni aprenda: `TYPE_TEXT_FLAG_NO_SUGGESTIONS` y la variante
     * «contraseña visible» (`TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`), que los teclados respetan mucho más; el texto
     * se sigue viendo y el campo sigue siendo de varias líneas. Lo que haga cada teclado depende de él.
     */
    noSuggestions: Boolean = false,
) {
    InterceptPlatformTextInput(interceptor = if (noSuggestions) NoSuggestionsInterceptor else NoLearningInterceptor) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = modifier,
            enabled = enabled,
            label = label?.let { { Text(it) } },
            singleLine = singleLine,
            maxLines = maxLines,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = keyboardType, imeAction = imeAction),
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

/** Como [NoLearningInterceptor], y además sin sugerencias. */
@OptIn(ExperimentalComposeUiApi::class)
private val NoSuggestionsInterceptor = PlatformTextInputInterceptor { request, nextHandler ->
    nextHandler.startInputMethod(NoLearningRequest(request, noSuggestions = true))
}

/**
 * Petición de teclado que, tras la del campo ([delegate]), añade `IME_FLAG_NO_PERSONALIZED_LEARNING` a
 * `imeOptions` conservando los bits que puso el campo (acción, etc.); con [noSuggestions], también
 * `TYPE_TEXT_FLAG_NO_SUGGESTIONS` a `inputType` y, en un campo de texto, la variante
 * `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` (conserva `TYPE_TEXT_FLAG_MULTI_LINE` y los demás bits).
 */
internal class NoLearningRequest(
    private val delegate: PlatformTextInputMethodRequest,
    private val noSuggestions: Boolean = false,
) : PlatformTextInputMethodRequest {
    override fun createInputConnection(outAttributes: EditorInfo): InputConnection =
        delegate.createInputConnection(outAttributes).also {
            outAttributes.imeOptions = outAttributes.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            if (noSuggestions) {
                var type = outAttributes.inputType or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                if (type and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT) {
                    type = (type and InputType.TYPE_MASK_VARIATION.inv()) or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                }
                outAttributes.inputType = type
            }
        }
}
