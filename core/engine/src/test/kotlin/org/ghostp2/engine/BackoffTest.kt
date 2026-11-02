package org.ghostp2.engine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random

class BackoffTest {
    /** `Random` que devuelve siempre el mismo `nextDouble()`. */
    private class FixedRandom(private val value: Double) : Random() {
        override fun nextDouble(): Double = value
    }

    @Test
    fun doublesUpToTheCapWithoutJitterAtMidpoint() {
        val b = Backoff(15_000, 300_000, FixedRandom(0.5))
        assertEquals(listOf(15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L), List(7) { b.next() })
    }

    @Test
    fun jitterStaysWithinTwentyPercent() {
        assertEquals(listOf(12_000L, 24_000L), Backoff(15_000, 300_000, FixedRandom(0.0)).let { b -> List(2) { b.next() } })
        val high = Backoff(15_000, 300_000, FixedRandom(0.999_999)).let { b -> List(2) { b.next() } }
        assertTrue(high[0] in 17_999L..18_000L && high[1] in 35_999L..36_000L, "$high")
        val seeded = Backoff(50, 400, Random(42))
        var nominal = 50L
        repeat(50) {
            val d = seeded.next()
            assertTrue(d >= (nominal * 0.8).toLong() && d <= (nominal * 1.2).toLong(), "$d fuera de ±20 % de $nominal")
            nominal = (nominal * 2).coerceAtMost(400)
        }
    }

    @Test
    fun resetGoesBackToMinimum() {
        val b = Backoff(50, 400, FixedRandom(0.5))
        repeat(5) { b.next() }
        assertEquals(400, b.current)
        b.reset()
        assertEquals(50, b.current)
        assertEquals(listOf(50L, 100L), List(2) { b.next() })
    }
}
