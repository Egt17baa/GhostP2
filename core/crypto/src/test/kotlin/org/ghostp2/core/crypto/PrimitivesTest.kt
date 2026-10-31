package org.ghostp2.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PrimitivesTest {
    private fun hex(s: String): ByteArray {
        val c = s.replace(" ", "").replace("\n", "")
        return ByteArray(c.length / 2) { c.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    @Test
    fun x25519MatchesRfc7748() {
        val priv = hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a")
        val pub = hex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f")
        assertEquals(
            "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            Primitives.x25519(priv, pub).toHex(),
        )
    }

    @Test
    fun x25519RejectsLowOrderPoint() {
        val priv = Primitives.generateX25519().privateKey
        assertThrows<AuthenticationException> { Primitives.x25519(priv, ByteArray(32)) }
    }

    @Test
    fun generatedKeysAgree() {
        val a = Primitives.generateX25519()
        val b = Primitives.generateX25519()
        assertArrayEquals(Primitives.x25519(a.privateKey, b.publicKey), Primitives.x25519(b.privateKey, a.publicKey))
        assertArrayEquals(a.publicKey, Primitives.x25519PublicFromPrivate(a.privateKey).publicKey)
    }

    @Test
    fun hkdfMatchesRfc5869Case1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = ByteArray(13) { it.toByte() }
        val info = ByteArray(10) { (0xf0 + it).toByte() }
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Primitives.hkdf(salt, ikm, info, 42).toHex(),
        )
    }

    @Test
    fun hmacMatchesRfc4231Case1() {
        assertEquals(
            "b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
            Primitives.hmacSha256(ByteArray(20) { 0x0b }, "Hi There".toByteArray()).toHex(),
        )
    }

    @Test
    fun sha256ConcatenatesParts() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Primitives.sha256("a".toByteArray(), "bc".toByteArray()).toHex(),
        )
    }

    private val key = ByteArray(32) { (0x80 + it).toByte() }
    private val nonce = hex("070000004041424344454647")
    private val aad = hex("50515253c0c1c2c3c4c5c6c7")
    private val pt = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip " +
        "for the future, sunscreen would be it.").toByteArray()

    @Test
    fun chachaMatchesRfc8439Section2_8_2() {
        val expectedCt = hex(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d6" +
                "3dbea45e8ca9671282fafb69da92728b1a71de0a9e060b2905d6a5b67ecd3b36" +
                "92ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116",
        )
        val tag = hex("1ae10b594f09e26a7e902ecbd0600691")
        val sealed = Primitives.chachaSeal(key, nonce, aad, pt)
        assertArrayEquals(expectedCt + tag, sealed)
        assertArrayEquals(pt, Primitives.chachaOpen(key, nonce, aad, sealed))
    }

    @Test
    fun chachaOpenRejectsTampering() {
        val sealed = Primitives.chachaSeal(key, nonce, aad, pt)
        sealed[3] = (sealed[3].toInt() xor 1).toByte()
        assertThrows<AuthenticationException> { Primitives.chachaOpen(key, nonce, aad, sealed) }
    }

    @Test
    fun chachaOpenRejectsShortInput() {
        assertThrows<AuthenticationException> { Primitives.chachaOpen(key, nonce, aad, ByteArray(5)) }
    }

    @Test
    fun chachaValidatesSizes() {
        assertThrows<IllegalArgumentException> { Primitives.chachaSeal(ByteArray(31), nonce, aad, pt) }
        assertThrows<IllegalArgumentException> { Primitives.chachaSeal(key, ByteArray(8), aad, pt) }
    }

    @Test
    fun constantTimeEqualsAndWipe() {
        assertTrue(Primitives.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 2)))
        assertFalse(Primitives.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1, 3)))
        assertFalse(Primitives.constantTimeEquals(byteArrayOf(1, 2), byteArrayOf(1)))
        val a = byteArrayOf(1, 2, 3)
        Primitives.wipe(a)
        assertArrayEquals(ByteArray(3), a)
    }
}
