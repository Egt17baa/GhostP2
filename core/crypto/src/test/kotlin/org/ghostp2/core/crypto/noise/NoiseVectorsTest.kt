package org.ghostp2.core.crypto.noise

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.ghostp2.core.crypto.AuthenticationException
import org.ghostp2.core.crypto.Primitives
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class NoiseVectorsTest {

    private fun hex(s: String): ByteArray =
        ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }

    private fun loadVector(protocol: String): JsonObject {
        val text = javaClass.getResource("/noise-vectors.json")!!.readText()
        val vectors = Json.parseToJsonElement(text).jsonObject["vectors"]!!.jsonArray
        return vectors.map { it.jsonObject }.single { it["protocol_name"]!!.jsonPrimitive.content == protocol }
    }

    private fun JsonObject.str(k: String): String? = this[k]?.jsonPrimitive?.content

    private fun pattern(protocol: String): HandshakePattern =
        HandshakePattern.entries.single { it.protocolName == protocol }

    @ParameterizedTest
    @ValueSource(strings = ["Noise_KKpsk2_25519_ChaChaPoly_SHA256", "Noise_XXpsk3_25519_ChaChaPoly_SHA256"])
    fun vectorsMatch(protocol: String) {
        val v = loadVector(protocol)
        val p = pattern(protocol)
        val initiator = HandshakeState(
            pattern = p,
            initiator = true,
            prologue = hex(v.str("init_prologue")!!),
            localStatic = Primitives.x25519PublicFromPrivate(hex(v.str("init_static")!!)),
            remoteStatic = v.str("init_remote_static")?.let(::hex),
            psk = hex(v["init_psks"]!!.jsonArray[0].jsonPrimitive.content),
            fixedEphemeral = Primitives.x25519PublicFromPrivate(hex(v.str("init_ephemeral")!!)),
        )
        val responder = HandshakeState(
            pattern = p,
            initiator = false,
            prologue = hex(v.str("resp_prologue")!!),
            localStatic = Primitives.x25519PublicFromPrivate(hex(v.str("resp_static")!!)),
            remoteStatic = v.str("resp_remote_static")?.let(::hex),
            psk = hex(v["resp_psks"]!!.jsonArray[0].jsonPrimitive.content),
            fixedEphemeral = Primitives.x25519PublicFromPrivate(hex(v.str("resp_ephemeral")!!)),
        )

        val messages = v["messages"]!!.jsonArray.map { it.jsonObject }
        var iSend: CipherState? = null
        var iRecv: CipherState? = null
        var rSend: CipherState? = null
        var rRecv: CipherState? = null
        // En los vectores de cacophony los mensajes alternan iniciador/respondedor
        // durante toda la lista (handshake y transporte), empezando por el iniciador.
        messages.forEachIndexed { idx, m ->
            val payload = hex(m.str("payload")!!)
            val expected = hex(m.str("ciphertext")!!)
            val fromInitiator = idx % 2 == 0
            val (ct, pt) = if (!initiator.isComplete) {
                val (w, r) = if (fromInitiator) initiator to responder else responder to initiator
                val ct = w.writeMessage(payload)
                ct to r.readMessage(ct)
            } else {
                if (iSend == null) {
                    assertTrue(responder.isComplete)
                    assertArrayEquals(initiator.handshakeHash(), responder.handshakeHash())
                    assertArrayEquals(hex(v.str("handshake_hash")!!), initiator.handshakeHash())
                    initiator.split().let { iSend = it.first; iRecv = it.second }
                    responder.split().let { rSend = it.first; rRecv = it.second }
                }
                val (s, r) = if (fromInitiator) iSend!! to rRecv!! else rSend!! to iRecv!!
                val ct = s.encryptWithAd(ByteArray(0), payload)
                ct to r.decryptWithAd(ByteArray(0), ct)
            }
            assertArrayEquals(expected, ct, "ciphertext del mensaje $idx")
            assertArrayEquals(payload, pt, "payload del mensaje $idx")
        }
        assertNotNull(iSend, "el handshake debe completarse antes del transporte")
        assertArrayEquals(hex(v.str("handshake_hash")!!), responder.handshakeHash())
        assertArrayEquals(hex(v.str("init_static")!!).let { Primitives.x25519PublicFromPrivate(it).publicKey }, responder.remoteStaticKey)
        assertArrayEquals(hex(v.str("resp_static")!!).let { Primitives.x25519PublicFromPrivate(it).publicKey }, initiator.remoteStaticKey)
    }

    @Test
    fun kkWithWrongPskFailsAuthentication() {
        val a = Primitives.generateX25519()
        val b = Primitives.generateX25519()
        val ini = HandshakeState(HandshakePattern.KKpsk2, true, ByteArray(0), a, b.publicKey, ByteArray(32) { 1 })
        val res = HandshakeState(HandshakePattern.KKpsk2, false, ByteArray(0), b, a.publicKey, ByteArray(32) { 2 })
        res.readMessage(ini.writeMessage(ByteArray(0)))
        val m2 = res.writeMessage(ByteArray(0))
        assertThrows<AuthenticationException> { ini.readMessage(m2) }
        assertFalse(ini.isComplete)
    }

    @Test
    fun xxRoundTripAndTamperDetection() {
        val a = Primitives.generateX25519()
        val b = Primitives.generateX25519()
        val psk = Primitives.randomBytes(32)
        val ini = HandshakeState(HandshakePattern.XXpsk3, true, "p".toByteArray(), a, psk = psk)
        val res = HandshakeState(HandshakePattern.XXpsk3, false, "p".toByteArray(), b, psk = psk)
        res.readMessage(ini.writeMessage(ByteArray(0)))
        ini.readMessage(res.writeMessage("hola".toByteArray()))
        val m3 = ini.writeMessage("adiós".toByteArray())
        val tampered = m3.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows<AuthenticationException> { res.readMessage(tampered) }
        assertArrayEquals(b.publicKey, ini.remoteStaticKey)
        assertTrue(ini.isComplete)
        assertFalse(res.isComplete)
    }

    @Test
    fun oversizedMessageIsRejected() {
        val ini = HandshakeState(HandshakePattern.XXpsk3, true, ByteArray(0), Primitives.generateX25519(), psk = ByteArray(32))
        assertThrows<IllegalArgumentException> { ini.writeMessage(ByteArray(65535 - 32 + 1)) }
    }
}
