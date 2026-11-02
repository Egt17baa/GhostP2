package org.ghostp2.tor

import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3
import io.matthewnelson.kmp.tor.runtime.core.key.X25519
import org.ghostp2.core.crypto.Primitives
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Conversiones entre `ByteArray` (lo que guarda el motor) y los tipos de clave de kmp-tor. */
class TorKeysTest {

    @Test
    fun privateKeyRoundTrip() {
        val original = ED25519_V3.PrivateKey.generate()
        val bytes = TorKeys.privateKeyBytes(original)
        assertEquals(64, bytes.size)

        val back = TorKeys.privateKey(bytes)
        assertArrayEquals(bytes, back.encoded())
        assertEquals(original.generatePublicKey().address(), back.generatePublicKey().address())

        // Copias independientes: borrar el array del motor no destruye la clave de kmp-tor, ni al revés.
        bytes.fill(0)
        assertFalse(back.isDestroyed())
        assertTrue(back.encoded().any { it != 0.toByte() })
        val again = TorKeys.privateKeyBytes(back)
        back.destroy()
        assertTrue(again.any { it != 0.toByte() })
    }

    @Test
    fun privateKeyRejectsWrongSizeOrBlank() {
        assertThrows(IllegalArgumentException::class.java) { TorKeys.privateKey(ByteArray(32) { 1 }) }
        assertThrows(IllegalArgumentException::class.java) { TorKeys.privateKey(ByteArray(64)) }
    }

    @Test
    fun x25519PublicRoundTrip() {
        val pair = Primitives.generateX25519()
        val key = TorKeys.x25519PublicKey(pair.publicKey)
        assertArrayEquals(pair.publicKey, key.encoded())
        assertArrayEquals(pair.publicKey, TorKeys.x25519PublicBytes(key))
        assertThrows(IllegalArgumentException::class.java) { TorKeys.x25519PublicKey(ByteArray(31) { 1 }) }
    }

    @Test
    fun x25519PrivateMatchesEngineCurve() {
        // La privada que registra el motor (BouncyCastle) produce en kmp-tor la misma pública que publica el otro lado.
        val pair = Primitives.generateX25519()
        val key: X25519.PrivateKey = TorKeys.x25519PrivateKey(pair.privateKey)
        assertArrayEquals(pair.publicKey, key.generatePublicKey().encoded())
        pair.privateKey.fill(0)
        assertFalse("la clave de kmp-tor es una copia", key.isDestroyed())
        key.destroy()
        assertTrue(key.isDestroyed())
    }

    @Test
    fun onionAddressRoundTrip() {
        val address = TorKeys.onionAddress(DUCKDUCKGO)
        assertEquals(DUCKDUCKGO, TorKeys.onionString(address))
        assertEquals(DUCKDUCKGO, TorKeys.onionString(TorKeys.onionAddress("$DUCKDUCKGO.onion")))
        assertEquals(DUCKDUCKGO, TorKeys.onionString(TorKeys.onionAddress(DUCKDUCKGO.uppercase())))
    }

    @Test
    fun onionAddressRejectsBadChecksumAndJunk() {
        val tampered = DUCKDUCKGO.replaceRange(0, 1, if (DUCKDUCKGO[0] == 'a') "b" else "a")
        assertThrows(IllegalArgumentException::class.java) { TorKeys.onionAddress(tampered) }
        assertThrows(IllegalArgumentException::class.java) { TorKeys.onionAddress("https://$DUCKDUCKGO.onion/x") }
        assertThrows(IllegalArgumentException::class.java) { TorKeys.onionAddress("sub.$DUCKDUCKGO.onion") }
        assertThrows(IllegalArgumentException::class.java) { TorKeys.onionAddress("") }
    }

    @Test
    fun onionFromPrivateKeyMatchesTorDerivation() {
        val key = ED25519_V3.PrivateKey.generate()
        val onion = TorKeys.onionString(key.generatePublicKey().address())
        assertEquals(56, onion.length)
        assertEquals(onion, TorKeys.onionString(TorKeys.onionAddress(onion)))
    }

    private companion object {
        const val DUCKDUCKGO = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad"
    }
}
