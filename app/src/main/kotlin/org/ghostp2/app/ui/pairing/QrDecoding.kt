package org.ghostp2.app.ui.pairing

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.atomic.AtomicBoolean

private val QR_HINTS: Map<DecodeHintType, Any> = mapOf(
    DecodeHintType.TRY_HARDER to true,
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.CHARACTER_SET to "UTF-8",
)

/**
 * Texto del QR que haya en el plano de luminancia [data] (`width`×`height`, cada fila empieza cada
 * [rowStride] bytes); `null` si no hay QR, está dañado o ZXing falla con él. No guarda ni registra
 * nada del fotograma.
 */
fun decodeQrLuminance(data: ByteArray, width: Int, height: Int, rowStride: Int): String? =
    try {
        val source = PlanarYUVLuminanceSource(data, rowStride, height, 0, 0, width, height, false)
        QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), QR_HINTS).text
    } catch (_: ReaderException) {
        // Sin QR, ilegible o con la suma de comprobación mal: se prueba con el siguiente fotograma.
        null
    } catch (_: RuntimeException) {
        // ZXing también puede fallar con excepciones sin comprobar ante datos raros (o un plano más
        // corto de lo dicho): es "sin QR", nunca un cierre del hilo del analizador.
        null
    }

/**
 * La invitación de GhostP2 que lleva [text] (sin los espacios de los extremos), o `null` si no empieza
 * por `ghostp2:` (sin distinguir mayúsculas): un QR ajeno se ignora y no se conecta a nada.
 */
fun invitationOrNull(text: String): String? = text.trim().takeIf { it.startsWith(INVITATION_PREFIX, ignoreCase = true) }

private const val INVITATION_PREFIX = "ghostp2:"

/**
 * Busca la invitación en los fotogramas de la cámara (en el hilo del analizador, nunca en el principal).
 * Copia el plano Y en un búfer propio reutilizado, que se borra tras cada fotograma (lleva el secreto si
 * enfoca una invitación), y avisa a [onInvitation] una sola vez. El `ImageProxy` siempre se cierra y
 * un fotograma ilegible se salta sin lanzar (una excepción en el hilo del analizador cerraría la app).
 * [accept] decide qué QR valen y qué se entrega (por defecto, [invitationOrNull]; «Mis puentes» usa el suyo).
 */
class QrAnalyzer(
    private val accept: (String) -> String? = ::invitationOrNull,
    private val onInvitation: (String) -> Unit,
) : ImageAnalysis.Analyzer {
    private val reported = AtomicBoolean(false)
    private var buffer = ByteArray(0)

    override fun analyze(image: ImageProxy) {
        image.use {
            if (reported.get()) return
            val plane = try {
                it.planes[0]
            } catch (_: IllegalStateException) {
                // Imagen ya cerrada por CameraX: se salta el fotograma (lanzar cerraría la app).
                return
            }
            val rowStride = plane.rowStride
            val size = rowStride * it.height
            if (buffer.size < size) buffer = ByteArray(size)
            try {
                // `duplicate()`: no se mueve la posición del búfer de CameraX. La última fila puede venir
                // sin relleno; lo que falte queda a cero.
                val source = plane.buffer.duplicate()
                val copied = minOf(source.remaining(), size)
                source.get(buffer, 0, copied)
                val invitation = decodeQrLuminance(buffer, it.width, it.height, rowStride)?.let(accept)
                if (invitation != null && reported.compareAndSet(false, true)) onInvitation(invitation)
            } finally {
                buffer.fill(0)
            }
        }
    }
}
