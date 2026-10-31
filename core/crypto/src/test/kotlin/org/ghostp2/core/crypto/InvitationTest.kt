package org.ghostp2.core.crypto

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InvitationTest {
    private val onion = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad"
    private val secret = ByteArray(16) { (it * 7 + 1).toByte() }

    @Test
    fun knownOnionIsValid() {
        assertTrue(OnionAddress.isValid(onion))
        assertTrue(OnionAddress.isValid("$onion.onion"))
        assertTrue(OnionAddress.isValid(onion.uppercase()))
        assertFalse(OnionAddress.isValid(onion.dropLast(1)))
        assertFalse(OnionAddress.isValid(""))
    }

    @Test
    fun base32KnownVectorsAndStrictDecode() {
        assertEquals("mzxw6ytboi", Base32.encode("foobar".toByteArray()))
        assertArrayEquals("foobar".toByteArray(), Base32.decode("MZXW6YTBOI"))
        assertThrows<IllegalArgumentException> { Base32.decode("mzxw6ytb0i") }
        assertThrows<IllegalArgumentException> { Base32.decode("mzxw6yb") }
    }

    @Test
    fun invitationRoundTrip() {
        val inv = Invitation(onion, secret)
        val text = inv.encode()
        assertTrue(text.startsWith("ghostp2:"))
        assertEquals(8 + 56 + 1 + 26, text.length)
        assertEquals(inv, Invitation.parse(text))
        assertFalse(inv.toString().contains(Base32.encode(secret)))
    }

    @Test
    fun invitationToleratesWhitespaceAndCase() {
        val text = Invitation(onion, secret).encode()
        val messy = " " + text.uppercase().chunked(10).joinToString("\n ") + "\n"
        assertEquals(Invitation(onion, secret), Invitation.parse(messy))
    }

    @Test
    fun invitationRejectsBadChecksum() {
        val text = Invitation(onion, secret).encode()
        val i = 8 + 3
        val bad = text.substring(0, i) + (if (text[i] == 'a') 'b' else 'a') + text.substring(i + 1)
        val e = assertThrows<IllegalArgumentException> { Invitation.parse(bad) }
        assertTrue(e.message!!.startsWith("Invitación no válida"))
    }

    @Test
    fun invitationRejectsWrongSecretLength() {
        assertThrows<IllegalArgumentException> { Invitation(onion, ByteArray(15)) }
        val short = "ghostp2:$onion." + Base32.encode(ByteArray(15))
        val e = assertThrows<IllegalArgumentException> { Invitation.parse(short) }
        assertTrue(e.message!!.startsWith("Invitación no válida"))
    }

    @Test
    fun invitationRejectsMissingPrefix() {
        val text = Invitation(onion, secret).encode().removePrefix("ghostp2:")
        val e = assertThrows<IllegalArgumentException> { Invitation.parse(text) }
        assertTrue(e.message!!.startsWith("Invitación no válida"))
    }
}
