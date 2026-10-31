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

    @Test
    fun safetyNumberRequires32ByteKeys() {
        assertThrows<IllegalArgumentException> { SafetyNumber.compute(ByteArray(31), b) }
    }
}
