package org.ghostp2.app.ui.pairing

import androidx.compose.ui.graphics.asAndroidBitmap
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import org.ghostp2.app.TestGhostApp
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QrCodeTest {
    @Test
    fun encodeThenDecodeRoundTrips() {
        val matrix = QrCode.encode(INVITATION, 320)
        assertEquals(320, matrix.width)
        assertEquals(320, matrix.height)

        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) BLACK else WHITE
        }
        val source = RGBLuminanceSource(matrix.width, matrix.height, pixels)
        val result = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true))
        assertEquals(INVITATION, result.text)
    }

    @Test
    fun imageBitmapHasTheMatrixPixels() {
        val matrix = QrCode.encode(INVITATION, 200)
        val bitmap = QrCode.toImageBitmap(matrix).asAndroidBitmap()
        assertEquals(matrix.width, bitmap.width)
        assertEquals(matrix.height, bitmap.height)
        for (y in 0 until matrix.height step 7) {
            for (x in 0 until matrix.width step 7) {
                assertEquals(if (matrix[x, y]) BLACK else WHITE, bitmap.getPixel(x, y))
            }
        }
    }

    private companion object {
        val INVITATION = "ghostp2:" + "a".repeat(56) + "." + "b".repeat(26)
        const val BLACK = 0xFF000000.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()
    }
}
