package org.ghostp2.app.ui.chat

import org.ghostp2.app.R
import org.ghostp2.app.TestGhostApp
import org.ghostp2.core.protocol.FailReason
import org.ghostp2.engine.DeliveryStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class StatusLabelsTest {
    private val app = RuntimeEnvironment.getApplication()

    @Test
    fun everyStatusHasItsLabel() {
        assertEquals(StatusLabel(null, struck = false, progress = null), statusLabel(DeliveryStatus.Received))
        // Textos: siempre Sending(0, 0), sin progreso (nunca se divide por cero).
        assertEquals(StatusLabel(R.string.chat_status_sending, struck = false, progress = null), statusLabel(DeliveryStatus.Sending(0, 0)))
        assertEquals(StatusLabel(R.string.chat_status_sending, struck = false, progress = 0.25f), statusLabel(DeliveryStatus.Sending(25, 100)))
        assertEquals(StatusLabel(R.string.chat_status_delivered, struck = false, progress = null), statusLabel(DeliveryStatus.Delivered))
        assertEquals(
            StatusLabel(R.string.chat_status_not_connected, struck = true, progress = null),
            statusLabel(DeliveryStatus.Failed(FailReason.NOT_CONNECTED)),
        )
        listOf(FailReason.CONNECTION_LOST, FailReason.NO_ACK).forEach {
            assertEquals(StatusLabel(R.string.chat_status_failed, struck = true, progress = null), statusLabel(DeliveryStatus.Failed(it)))
        }
        // Los textos fijados por la spec, exactos.
        assertEquals("enviando…", app.getString(R.string.chat_status_sending))
        assertEquals("✓ entregado", app.getString(R.string.chat_status_delivered))
        assertEquals("No entregado: tu contacto no está conectado. Descartado", app.getString(R.string.chat_status_not_connected))
        assertEquals("No entregado. Descartado", app.getString(R.string.chat_status_failed))
    }
}
