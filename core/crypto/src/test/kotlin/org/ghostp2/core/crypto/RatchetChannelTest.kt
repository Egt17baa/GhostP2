package org.ghostp2.core.crypto

import org.ghostp2.core.crypto.noise.HandshakePattern
import org.ghostp2.core.crypto.noise.HandshakeState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Pruebas del canal con ratchet simétrico por mensaje. */
class RatchetChannelTest {
    private val empty = ByteArray(0)

    /** Crea dos canales (iniciador, respondedor) a partir de un handshake KKpsk2 real. */
    private fun channels(): Pair<RatchetChannel, RatchetChannel> {
        val a = Primitives.generateX25519()
        val b = Primitives.generateX25519()
        val psk = Primitives.randomBytes(32)
        val i = HandshakeState(HandshakePattern.KKpsk2, true, empty, a, b.publicKey, psk)
        val r = HandshakeState(HandshakePattern.KKpsk2, false, empty, b, a.publicKey, psk)
        r.readMessage(i.writeMessage(empty))
        i.readMessage(r.writeMessage(empty))
        return RatchetChannel.fromHandshake(i, true) to RatchetChannel.fromHandshake(r, false)
    }

    @Test
    fun roundTripBothDirections() {
        val (alice, bob) = channels()
        for (k in 0 until 5) {
            val m1 = "Hola $k: ñ 👻".toByteArray(Charsets.UTF_8)
            assertArrayEquals(m1, bob.decrypt(alice.encrypt(m1)))
            val m2 = "Respuesta $k: ñ 👻".toByteArray(Charsets.UTF_8)
            assertArrayEquals(m2, alice.decrypt(bob.encrypt(m2)))
        }
        // Mensaje vacío y del tamaño máximo
        assertArrayEquals(empty, bob.decrypt(alice.encrypt(empty)))
        val big = Primitives.randomBytes(RatchetChannel.MAX_PLAINTEXT)
        assertArrayEquals(big, alice.decrypt(bob.encrypt(big)))
    }

    @Test
    fun rejectsReplay() {
        val (alice, bob) = channels()
        val f = alice.encrypt("uno".toByteArray())
        bob.decrypt(f)
        assertThrows<ChannelSecurityException> { bob.decrypt(f) }
    }

    @Test
    fun rejectsReorder() {
        val (alice, bob) = channels()
        val f1 = alice.encrypt("uno".toByteArray())
        val f2 = alice.encrypt("dos".toByteArray())
        assertThrows<ChannelSecurityException> { bob.decrypt(f2) }
        assertArrayEquals("uno".toByteArray(), bob.decrypt(f1))
        assertArrayEquals("dos".toByteArray(), bob.decrypt(f2))
    }

    @Test
    fun rejectsTamperingWithoutAdvancing() {
        val (alice, bob) = channels()
        val f = alice.encrypt("intacto".toByteArray())
        // Un bit cambiado en cada posición relevante: versión, contador, cuerpo y etiqueta
        for (pos in listOf(0, 1, 4, 5, f.size / 2, f.size - 1)) {
            val t = f.copyOf()
            t[pos] = (t[pos].toInt() xor 0x01).toByte()
            assertThrows<ChannelSecurityException> { bob.decrypt(t) }
        }
        assertArrayEquals("intacto".toByteArray(), bob.decrypt(f))
    }

    @Test
    fun paddingHidesShortLengths() {
        val (alice, _) = channels()
        val a = alice.encrypt("sí".toByteArray(Charsets.UTF_8))
        val b = alice.encrypt("x".repeat(150).toByteArray())
        assertEquals(a.size, b.size)
        // 5 de cabecera + 256 de bloque relleno + 16 de etiqueta
        assertEquals(5 + 256 + 16, a.size)
    }

    @Test
    fun rejectsOwnReflectedFrame() {
        val (alice, bob) = channels()
        val fa = alice.encrypt("mío".toByteArray())
        assertThrows<ChannelSecurityException> { alice.decrypt(fa) }
        val fb = bob.encrypt("mío".toByteArray())
        assertThrows<ChannelSecurityException> { bob.decrypt(fb) }
        // El rechazo no avanzó nada: las tramas siguen valiendo para su destinatario
        assertArrayEquals("mío".toByteArray(), bob.decrypt(fa))
        assertArrayEquals("mío".toByteArray(), alice.decrypt(fb))
    }

    @Test
    fun destroyedChannelRefusesWork() {
        val (alice, bob) = channels()
        val f = alice.encrypt("antes".toByteArray())
        alice.destroy()
        bob.destroy()
        assertThrows<ChannelSecurityException> { alice.encrypt("después".toByteArray()) }
        assertThrows<ChannelSecurityException> { bob.decrypt(f) }
        // destroy() es idempotente
        alice.destroy()
    }

    @Test
    fun rejectsPlaintextOverMax() {
        val (alice, _) = channels()
        assertThrows<IllegalArgumentException> { alice.encrypt(ByteArray(RatchetChannel.MAX_PLAINTEXT + 1)) }
    }

    @Test
    fun rejectsInvalidFrameSizes() {
        val (alice, bob) = channels()
        assertThrows<ChannelSecurityException> { bob.decrypt(empty) }
        assertThrows<ChannelSecurityException> { bob.decrypt(ByteArray(5 + 16)) }
        // Truncada y con bytes de más
        val f = alice.encrypt("hola".toByteArray())
        assertThrows<ChannelSecurityException> { bob.decrypt(f.copyOf(f.size - 1)) }
        assertThrows<ChannelSecurityException> { bob.decrypt(f.copyOf(f.size + 1)) }
        val maxFrame = 5 + RatchetChannel.paddedSize(RatchetChannel.MAX_PLAINTEXT) + 16
        assertThrows<ChannelSecurityException> { bob.decrypt(ByteArray(maxFrame + 256)) }
        assertArrayEquals("hola".toByteArray(), bob.decrypt(f))
    }

    @Test
    fun counterExhaustionIsRejected() {
        val ck = Primitives.randomBytes(32)
        val ck2 = Primitives.randomBytes(32)
        val ch = RatchetChannel.fromChainKeys(ck, ck2, sendCounter = 0xFFFF_FFFEL)
        ch.encrypt("último".toByteArray())
        assertThrows<ChannelSecurityException> { ch.encrypt("sobra".toByteArray()) }
    }

    @Test
    fun handshakeCipherStatesAreWiped() {
        val a = Primitives.generateX25519()
        val b = Primitives.generateX25519()
        val psk = Primitives.randomBytes(32)
        val i = HandshakeState(HandshakePattern.KKpsk2, true, empty, a, b.publicKey, psk)
        val r = HandshakeState(HandshakePattern.KKpsk2, false, empty, b, a.publicKey, psk)
        r.readMessage(i.writeMessage(empty))
        i.readMessage(r.writeMessage(empty))
        RatchetChannel.fromHandshake(i, true)
        // split() ya se consumió: no puede volver a derivarse el transporte
        assertThrows<IllegalStateException> { i.split() }
    }
}
