package org.ghostp2.app.ui

import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import org.ghostp2.app.TestGhostApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class GhostTextFieldTest {
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
}
