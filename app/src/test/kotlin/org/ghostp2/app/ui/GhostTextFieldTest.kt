package org.ghostp2.app.ui

import android.text.InputType
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.input.KeyboardType
import org.ghostp2.app.TestGhostApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class GhostTextFieldTest {
    @get:Rule
    val compose = createComposeRule()

    /** De punta a punta: el teclado que se conecta al campo enfocado recibe las opciones de incógnito. */
    @Test
    fun focusedFieldAsksKeyboardNotToLearnNorAutocorrect() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            var text by remember { mutableStateOf("") }
            GhostTextField(text, { text = it }, modifier = Modifier.testTag(FIELD), label = "Mensaje")
        }
        compose.onNodeWithTag(FIELD).performClick()
        compose.waitForIdle()

        val attributes = EditorInfo()
        val connection = compose.runOnIdle { view.onCreateInputConnection(attributes) }

        assertNotNull("El campo enfocado no abrió conexión con el teclado", connection)
        assertTrue(attributes.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertEquals(0, attributes.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertEquals(InputType.TYPE_CLASS_TEXT, attributes.inputType and InputType.TYPE_MASK_CLASS)
    }

    /** El campo de «Mis puentes»: teclado ASCII, sin sugerencias, sin autocorrección y sin aprender. */
    @Test
    fun asciiFieldWithoutSuggestions() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            var text by remember { mutableStateOf("") }
            GhostTextField(
                text, { text = it }, modifier = Modifier.testTag(FIELD), label = "Puentes",
                keyboardType = KeyboardType.Ascii, noSuggestions = true,
            )
        }
        compose.onNodeWithTag(FIELD).performClick()
        compose.waitForIdle()

        val attributes = EditorInfo()
        compose.runOnIdle { view.onCreateInputConnection(attributes) }

        assertTrue(attributes.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
        assertTrue(attributes.imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII != 0)
        assertTrue(attributes.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
        assertEquals(0, attributes.inputType and InputType.TYPE_TEXT_FLAG_AUTO_CORRECT)
        assertEquals(InputType.TYPE_CLASS_TEXT, attributes.inputType and InputType.TYPE_MASK_CLASS)
        // Contraseña visible: los teclados la respetan mucho más que "sin sugerencias" y no aprenden de ella.
        assertEquals(
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            attributes.inputType and InputType.TYPE_MASK_VARIATION,
        )
        // Y sigue siendo de varias líneas.
        assertTrue(attributes.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0)
    }

    /** Un campo normal no pasa a contraseña. */
    @Test
    fun normalFieldIsNotAPasswordField() {
        lateinit var view: View
        compose.setContent {
            view = LocalView.current
            var text by remember { mutableStateOf("") }
            GhostTextField(text, { text = it }, modifier = Modifier.testTag(FIELD), label = "Mensaje")
        }
        compose.onNodeWithTag(FIELD).performClick()
        compose.waitForIdle()

        val attributes = EditorInfo()
        compose.runOnIdle { view.onCreateInputConnection(attributes) }

        assertEquals(0, attributes.inputType and InputType.TYPE_MASK_VARIATION)
    }

    @Test
    fun noLearningRequestSetsFlag() {
        val connection = BaseInputConnection(View(RuntimeEnvironment.getApplication()), false)
        // Delegado falso: pone sus propios bits, como el campo de texto de Compose.
        val delegate = object : PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: EditorInfo): InputConnection {
                outAttributes.imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN
                return connection
            }
        }
        val attributes = EditorInfo()

        val result = NoLearningRequest(delegate).createInputConnection(attributes)

        assertSame(connection, result)
        val expected = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        assertEquals(expected, attributes.imeOptions)
    }

    private companion object {
        const val FIELD = "campo"
    }
}
