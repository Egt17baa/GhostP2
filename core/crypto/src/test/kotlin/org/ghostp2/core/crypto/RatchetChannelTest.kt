package org.ghostp2.core.crypto

import org.ghostp2.core.crypto.noise.HandshakePattern
import org.ghostp2.core.crypto.noise.HandshakeState
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
    fun fromHandshakeConsumesSplit() {
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

    // ---- Formato en el cable (fija el formato frente a otra implementación) ----

    private val fixedCk = ByteArray(32) { it.toByte() }
    private val otherCk = ByteArray(32) { 0x55 }
    private val fixedCounter = 0x0102_0304L

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
    private fun u32(v: Long) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    /** Nonce independiente de la implementación: 8 ceros || contador BE. */
    private fun refNonce(counter: Long) = ByteArray(8) + u32(counter)

    @Test
    fun frameLayoutMatchesSpecification() {
        val sender = RatchetChannel.fromChainKeys(fixedCk, otherCk, sendCounter = fixedCounter)
        val receiver = RatchetChannel.fromChainKeys(otherCk, fixedCk, recvCounter = fixedCounter)
        val pt1 = "hola ñ".toByteArray(Charsets.UTF_8)
        val pt2 = "adios".toByteArray()
        val f1 = sender.encrypt(pt1)
        val f2 = sender.encrypt(pt2)

        // Cabecera: versión 1 y contador big-endian
        assertEquals(1, f1[0].toInt())
        assertArrayEquals(u32(fixedCounter), f1.copyOfRange(1, 5))
        assertArrayEquals(u32(fixedCounter + 1), f2.copyOfRange(1, 5))

        // Primera trama: mk = HMAC(ck, 0x01), aad = 5 bytes de cabecera
        val mk1 = Primitives.hmacSha256(fixedCk, byteArrayOf(0x01))
        val padded1 = Primitives.chachaOpen(mk1, refNonce(fixedCounter), f1.copyOfRange(0, 5), f1.copyOfRange(5, f1.size))
        assertEquals(256, padded1.size)
        assertArrayEquals(u32(pt1.size.toLong()), padded1.copyOfRange(0, 4))
        assertArrayEquals(pt1, padded1.copyOfRange(4, 4 + pt1.size))
        assertArrayEquals(ByteArray(256 - 4 - pt1.size), padded1.copyOfRange(4 + pt1.size, 256))

        // Tras descifrar la primera, la siguiente abre con ck' = HMAC(ck, 0x02), mk' = HMAC(ck', 0x01)
        assertArrayEquals(pt1, receiver.decrypt(f1))
        val ck2 = Primitives.hmacSha256(fixedCk, byteArrayOf(0x02))
        val mk2 = Primitives.hmacSha256(ck2, byteArrayOf(0x01))
        val padded2 = Primitives.chachaOpen(mk2, refNonce(fixedCounter + 1), f2.copyOfRange(0, 5), f2.copyOfRange(5, f2.size))
        assertArrayEquals(u32(pt2.size.toLong()), padded2.copyOfRange(0, 4))
        assertArrayEquals(pt2, padded2.copyOfRange(4, 4 + pt2.size))
        assertArrayEquals(pt2, receiver.decrypt(f2))
    }

    @Test
    fun knownAnswerVector() {
        // Vector generado con una implementación independiente (Python + cryptography):
        // ck = 00..1f, contador inicial = 0x01020304, mensajes "hola ñ" (UTF-8) y "adios"
        val expected1 = "0101020304951180aa1fd0b6125e42e96975412ac8508cfa8dab3f55e2b707eb5442048fb4d93929d832f20003a18672" +
            "5afb694b481da7f875af22482a54f044d2712362c049279a127d80152d181b979f9caaf0847580ef46241de720e12645" +
            "0383c0dbed0815412b062941a93bb858a6705c93a3001eb979dfa8cab448e4fb7db971a0c46eed287ce699f7b1b0e89a" +
            "adf7371965be31d914a124e331b4c908b9e11fa00dbaff3ae31721f079e07673d6123427f19a6c308384c5cda5818b8b" +
            "4caa699daa839a99db03fae11eb7e7b854a946e42846552208c3f7a59dc42e807f02a486734232c663597fbcabe19d70" +
            "6d00c52740e7a34641e47dc10fa57f211baef822129b2249dd881c039689f571cd78ba3e3f"
        val expected2 = "01010203051d241ff4df6584e459e6825e782463ab5d73f9e32e384fea8a6faf072fa351b80faae5a1a4a7d6901e5ff6" +
            "1bb9f80f6ef1a7fc9525f637d4feb6d942cd7e685dbe3fc63ac5deeb9cea6db457497cc08c6a33d32b08abf8d7dd2281" +
            "b305d5e662388d2e5a64e0173183587ad47899d43609ceda1ee5593b291329fe4d0b1d5794339836ea51dca718d310ab" +
            "bd490517b11e71b3222301d9eebf7e7bb79de091ef7ae46c4a3c2f4f3b8f02d066ae31b17fa0bbeb5802cff577a67075" +
            "71e342ebc642c6478544ada7b5246075032dc50adb9648bb59d2b2c7860d24f739d5af7a582564e8a040d5249ddba386" +
            "945a0e3788e16bb3b2fba1105833e110b985652ca9e68bb5b9ea602d5496aa287f516c4994"
        val sender = RatchetChannel.fromChainKeys(fixedCk, otherCk, sendCounter = fixedCounter)
        assertEquals(expected1, toHex(sender.encrypt("hola ñ".toByteArray(Charsets.UTF_8))))
        assertEquals(expected2, toHex(sender.encrypt("adios".toByteArray())))

        val receiver = RatchetChannel.fromChainKeys(otherCk, fixedCk, recvCounter = fixedCounter)
        assertArrayEquals("hola ñ".toByteArray(Charsets.UTF_8), receiver.decrypt(hex(expected1)))
        assertArrayEquals("adios".toByteArray(), receiver.decrypt(hex(expected2)))
    }

    @Test
    fun headerIsBoundAsAad() {
        val sender = RatchetChannel.fromChainKeys(fixedCk, otherCk, sendCounter = fixedCounter)
        val f = sender.encrypt("cabecera".toByteArray())
        val mk = Primitives.hmacSha256(fixedCk, byteArrayOf(0x01))
        val nonce = refNonce(fixedCounter)
        val body = f.copyOfRange(5, f.size)
        // Con aad vacía o con otra cabecera la etiqueta no valida: la cabecera está autenticada
        assertThrows<AuthenticationException> { Primitives.chachaOpen(mk, nonce, empty, body) }
        val otherHeader = f.copyOfRange(0, 5).also { it[0] = 2 }
        assertNotEquals(f[0], otherHeader[0])
        assertThrows<AuthenticationException> { Primitives.chachaOpen(mk, nonce, otherHeader, body) }
        // Con la cabecera correcta sí abre
        Primitives.chachaOpen(mk, nonce, f.copyOfRange(0, 5), body)
    }
}
