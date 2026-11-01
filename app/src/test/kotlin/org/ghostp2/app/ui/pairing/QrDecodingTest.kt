package org.ghostp2.app.ui.pairing

import androidx.camera.core.ImageProxy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import kotlin.random.Random

/** Lectura del QR en el plano Y (luminancia) de un fotograma de la cámara, sin cámara. */
class QrDecodingTest {
    @Test
    fun decodesInvitationFromLuminancePlane() {
        val plane = planeWithQr(INVITATION)
        assertEquals(INVITATION, decodeQrLuminance(plane, WIDTH, HEIGHT, ROW_STRIDE))
    }

    @Test
    fun noiseDecodesToNull() {
        val noise = Random(42).nextBytes(ROW_STRIDE * HEIGHT)
        assertNull(decodeQrLuminance(noise, WIDTH, HEIGHT, ROW_STRIDE))
        assertNull(decodeQrLuminance(ByteArray(ROW_STRIDE * HEIGHT) { 0x7F }, WIDTH, HEIGHT, ROW_STRIDE))
    }

    @Test
    fun foreignQrIsIgnored() {
        assertNull(invitationOrNull("https://example.com"))
        assertNull(invitationOrNull("WIFI:S:casa;T:WPA;P:secreta;;"))
        assertNull(invitationOrNull("  "))
        assertNull(invitationOrNull("xghostp2:abc"))
        assertEquals("GHOSTP2:" + INVITATION.removePrefix("ghostp2:").uppercase(), invitationOrNull("  GHOSTP2:" + INVITATION.removePrefix("ghostp2:").uppercase() + "\n"))
        assertEquals(INVITATION, invitationOrNull(INVITATION))

        // Un QR ajeno que se lee bien no llega a informarse: nada que aceptar ni a lo que conectar.
        val seen = mutableListOf<String>()
        val analyzer = QrAnalyzer { seen += it }
        val foreign = FakeImage(planeWithQr("https://example.com"))
        analyzer.analyze(foreign.proxy)
        assertEquals(emptyList<String>(), seen)
        assertEquals(1, foreign.closes)
    }

    @Test
    fun analyzerReportsOnceAndAlwaysCloses() {
        val seen = mutableListOf<String>()
        val analyzer = QrAnalyzer { seen += it }
        val noise = FakeImage(Random(1).nextBytes(ROW_STRIDE * HEIGHT))
        analyzer.analyze(noise.proxy)
        assertEquals(emptyList<String>(), seen)

        val first = FakeImage(planeWithQr(INVITATION))
        val second = FakeImage(planeWithQr(INVITATION))
        analyzer.analyze(first.proxy)
        analyzer.analyze(second.proxy)
        assertEquals(listOf(INVITATION), seen)
        assertEquals(listOf(1, 1, 1), listOf(noise.closes, first.closes, second.closes))
        // El fotograma de la cámara no se toca (el búfer es de CameraX).
        assertArrayEquals(planeWithQr(INVITATION), first.bytes)
    }

    @Test
    fun analyzerSkipsAClosedImageWithoutThrowing() {
        val seen = mutableListOf<String>()
        val analyzer = QrAnalyzer { seen += it }
        val broken = FakeImage(ByteArray(0), failPlanes = true)
        // Lanzar en el hilo del analizador cerraría la app: el fotograma se salta y se cierra.
        analyzer.analyze(broken.proxy)
        assertEquals(1, broken.closes)

        // El analizador sigue funcionando con el siguiente fotograma.
        val good = FakeImage(planeWithQr(INVITATION))
        analyzer.analyze(good.proxy)
        assertEquals(listOf(INVITATION), seen)
        assertEquals(1, good.closes)
    }

    @Test
    fun zxingRuntimeFailuresDecodeToNull() {
        // Plano más corto de lo dicho (ArrayIndexOutOfBounds dentro de ZXing).
        assertNull(decodeQrLuminance(ByteArray(10), WIDTH, HEIGHT, ROW_STRIDE))
        // Fila más estrecha que la imagen (IllegalArgumentException del origen de luminancia).
        assertNull(decodeQrLuminance(ByteArray(ROW_STRIDE * HEIGHT), WIDTH, HEIGHT, WIDTH - 1))
        // Tamaños sin sentido.
        assertNull(decodeQrLuminance(ByteArray(0), 0, 0, 0))

        // Un plano corto también se salta en el analizador (sin lanzar) y la imagen se cierra.
        val short = FakeImage(ByteArray(100))
        QrAnalyzer { }.analyze(short.proxy)
        assertEquals(1, short.closes)
    }

    /** Plano Y de 640×480 con `rowStride` 704: fondo gris, QR con margen y relleno ajeno tras cada fila. */
    private fun planeWithQr(text: String): ByteArray {
        val matrix = QrCode.encode(text, 300)
        val plane = ByteArray(ROW_STRIDE * HEIGHT)
        for (y in 0 until HEIGHT) {
            for (x in 0 until ROW_STRIDE) {
                val value = when {
                    x >= WIDTH -> 0x33 // relleno de la fila: no es imagen
                    x in LEFT until LEFT + matrix.width && y in TOP until TOP + matrix.height ->
                        if (matrix[x - LEFT, y - TOP]) 30 else 220
                    else -> 180
                }
                plane[y * ROW_STRIDE + x] = value.toByte()
            }
        }
        return plane
    }

    /** `ImageProxy` de prueba con un solo plano Y; cuenta los `close()`. */
    private class FakeImage(val bytes: ByteArray, failPlanes: Boolean = false) {
        var closes = 0
        private val plane = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ImageProxy.PlaneProxy::class.java)) { _, method, _ ->
            when (method.name) {
                "getRowStride" -> ROW_STRIDE
                "getPixelStride" -> 1
                "getBuffer" -> ByteBuffer.wrap(bytes).asReadOnlyBuffer()
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ImageProxy.PlaneProxy
        val proxy = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ImageProxy::class.java)) { _, method, _ ->
            when (method.name) {
                "close" -> closes++.let { null }
                "getWidth" -> WIDTH
                "getHeight" -> HEIGHT
                "getPlanes" -> if (failPlanes) throw IllegalStateException("cerrada") else arrayOf(plane)
                else -> throw UnsupportedOperationException(method.name)
            }
        } as ImageProxy
    }

    private companion object {
        const val WIDTH = 640
        const val HEIGHT = 480
        const val ROW_STRIDE = 704
        const val LEFT = 170
        const val TOP = 90
        val INVITATION = "ghostp2:" + "a".repeat(56) + "." + "b".repeat(26)
    }
}
