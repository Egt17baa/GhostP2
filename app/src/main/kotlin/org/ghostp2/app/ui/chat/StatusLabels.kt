package org.ghostp2.app.ui.chat

import androidx.annotation.StringRes
import org.ghostp2.app.R
import org.ghostp2.core.protocol.FailReason
import org.ghostp2.engine.DeliveryStatus

/**
 * Cómo se muestra el estado de entrega de un elemento: [text] (`null` ⇒ sin etiqueta, los recibidos),
 * [struck] (mensaje tachado: descartado) y [progress] (0..1, solo si se conoce el total: fotos).
 */
data class StatusLabel(@StringRes val text: Int?, val struck: Boolean, val progress: Float?)

/** Etiqueta de [status], con los textos fijados por la spec (§6.7). */
fun statusLabel(status: DeliveryStatus): StatusLabel = when (status) {
    DeliveryStatus.Received -> StatusLabel(null, struck = false, progress = null)
    // Los textos van siempre en Sending(0, 0): sin total no hay progreso (ni división por cero).
    is DeliveryStatus.Sending -> StatusLabel(
        R.string.chat_status_sending,
        struck = false,
        progress = if (status.totalBytes > 0) status.sentBytes.toFloat() / status.totalBytes else null,
    )
    DeliveryStatus.Delivered -> StatusLabel(R.string.chat_status_delivered, struck = false, progress = null)
    is DeliveryStatus.Failed -> when (status.reason) {
        FailReason.NOT_CONNECTED -> StatusLabel(R.string.chat_status_not_connected, struck = true, progress = null)
        FailReason.CONNECTION_LOST, FailReason.NO_ACK -> StatusLabel(R.string.chat_status_failed, struck = true, progress = null)
    }
}
