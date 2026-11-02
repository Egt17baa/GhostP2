package org.ghostp2.core.crypto.noise

import org.ghostp2.core.crypto.AuthenticationException
import org.ghostp2.core.crypto.Primitives
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Pruebas de uso indebido de la API Noise. */
class NoiseMisuseTest {
    private val a = Primitives.generateX25519()
    private val b = Primitives.generateX25519()
    private val psk = ByteArray(32) { 7 }
    private val empty = ByteArray(0)

    private fun kkPair() =
        HandshakeState(HandshakePattern.KKpsk2, true, empty, a, b.publicKey, psk) to
            HandshakeState(HandshakePattern.KKpsk2, false, empty, b, a.publicKey, psk)

    private fun completedKk(): Pair<HandshakeState, HandshakeState> {
        val (i, r) = kkPair()
        r.readMessage(i.writeMessage(empty))
        i.readMessage(r.writeMessage(empty))
        return i to r
    }

    @Test
    fun cipherStateUnusableAfterWipe() {
        val (i, r) = completedKk()
        val (iSend, _) = i.split()
        val (_, rRecv) = r.split()
        val ct = iSend.encryptWithAd(empty, "hola".toByteArray())
        iSend.wipe()
        rRecv.wipe()
        assertThrows<IllegalStateException> { iSend.encryptWithAd(empty, "hola".toByteArray()) }
        assertThrows<IllegalStateException> { rRecv.decryptWithAd(empty, ct) }
        assertThrows<IllegalStateException> { iSend.key }
    }

    @Test
    fun writeOutOfTurn() {
        val (i, r) = kkPair()
        assertThrows<IllegalStateException> { r.writeMessage(empty) }
        assertThrows<IllegalStateException> { i.readMessage(ByteArray(48)) }
    }

    @Test
    fun readAndWriteAfterCompletion() {
        val (i, r) = completedKk()
        assertThrows<IllegalStateException> { i.writeMessage(empty) }
        assertThrows<IllegalStateException> { r.readMessage(ByteArray(48)) }
        assertThrows<IllegalStateException> { r.writeMessage(empty) }
    }

    @Test
    fun anyCallAfterFailure() {
        val (i, r) = kkPair()
        val m1 = i.writeMessage(empty)
        m1[m1.size - 1] = (m1[m1.size - 1].toInt() xor 1).toByte()
        assertThrows<AuthenticationException> { r.readMessage(m1) }
        assertThrows<IllegalStateException> { r.writeMessage(empty) }
        assertThrows<IllegalStateException> { r.readMessage(m1) }
        assertThrows<IllegalStateException> { r.handshakeHash() }
        assertThrows<IllegalStateException> { r.split() }
    }

    @Test
    fun truncatedMessageFails() {
        val (_, r) = kkPair()
        assertThrows<AuthenticationException> { r.readMessage(ByteArray(10)) }
        assertThrows<IllegalStateException> { r.writeMessage(empty) }
    }

    @Test
    fun splitBeforeCompletionAndTwice() {
        val (i, r) = kkPair()
        assertThrows<IllegalStateException> { i.split() }
        r.readMessage(i.writeMessage(empty))
        i.readMessage(r.writeMessage(empty))
        i.split()
        assertThrows<IllegalStateException> { i.split() }
    }

    @Test
    fun handshakeWipe() {
        val (i, _) = completedKk()
        val h = i.handshakeHash()
        i.split()
        assertArrayEquals(h, i.handshakeHash())
        i.wipe()
        assertThrows<IllegalStateException> { i.handshakeHash() }
        assertThrows<IllegalStateException> { i.split() }
    }

    @Test
    fun remoteStaticValidation() {
        assertThrows<IllegalArgumentException> {
            HandshakeState(HandshakePattern.KKpsk2, true, empty, a, null, psk)
        }
        assertThrows<IllegalArgumentException> {
            HandshakeState(HandshakePattern.XXpsk3, true, empty, a, b.publicKey, psk)
        }
        assertThrows<IllegalArgumentException> {
            HandshakeState(HandshakePattern.XXpsk3, true, empty, a, psk = ByteArray(16))
        }
    }
}
