package org.ghostp2.app.ui.pairing

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Código QR de una invitación. Lleva el secreto: la matriz y el bitmap solo viven en memoria (nunca se
 * guardan en disco, en la caché ni en el estado guardado, y nunca se registran).
 */
object QrCode {
    /**
     * Matriz de [text] con corrección `M` y margen de 2 módulos, de `sizePx`×`sizePx` como mínimo:
     * con `0`, el tamaño nativo (un elemento por módulo, sin escalar).
     */
    fun encode(text: String, sizePx: Int): BitMatrix =
        QRCodeWriter().encode(
            text,
            BarcodeFormat.QR_CODE,
            sizePx,
            sizePx,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 2,
                EncodeHintType.CHARACTER_SET to "UTF-8",
            ),
        )

    /**
     * Bitmap en negro sobre blanco con un píxel por elemento de [matrix] (también en tema oscuro, para
     * leerlo). Con la matriz nativa es un píxel por módulo: quien lo dibuja lo amplía sin suavizar
     * (`FilterQuality.None`).
     */
    fun toImageBitmap(matrix: BitMatrix): ImageBitmap {
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) pixels[row + x] = if (matrix[x, y]) BLACK else WHITE
        }
        val bitmap = createBitmap(width, height)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap.asImageBitmap()
    }

    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
