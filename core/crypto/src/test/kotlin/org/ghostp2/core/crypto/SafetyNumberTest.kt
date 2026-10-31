package org.ghostp2.core.crypto

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SafetyNumberTest {
    private val a = ByteArray(32) { it.toByte() }
    private val b = ByteArray(32) { (255 - it).toByte() }
    private val c = ByteArray(32) { (it * 3).toByte() }

    @Test
    fun safetyNumberIsSymmetricAndFormatted() {
        val n = SafetyNumber.compute(a, b)
        assertEquals(n, SafetyNumber.compute(b, a))
        assertTrue(Regex("^(\\d{5} ){5}\\d{5}$").matches(n))
        assertNotEquals(n, SafetyNumber.compute(a, c))
    }

    /**
     * Vector conocido calculado con una implementación independiente (Python 3 + hashlib):
     * h = SHA-256("GhostP2 safety v1" || menor || mayor), 5200 veces h = SHA-256(h || menor || mayor),
     * y cada grupo = (u32 big-endian de h[4i..4i+3]) mod 100000 con 5 dígitos.
     */
    @Test
    fun knownAnswerVector() {
        val k1 = ByteArray(32) { 0x01 }
        val k2 = ByteArray(32) { 0x02 }
        val expected = "11384 69342 18126 46878 36715 63359"
        assertEquals(expected, SafetyNumber.compute(k1, k2))
        assertEquals(expected, SafetyNumber.compute(k2, k1))
    }

    @Test
    fun safetyNumberRequires32ByteKeys() {
        assertThrows<IllegalArgumentException> { SafetyNumber.compute(ByteArray(31), b) }
    }
}
