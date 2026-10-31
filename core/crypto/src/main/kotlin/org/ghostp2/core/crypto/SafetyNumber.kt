package org.ghostp2.core.crypto

/** Número de seguridad de 30 dígitos (6 grupos de 5) que dos personas comparan a mano. */
object SafetyNumber {
    private const val ITERATIONS = 5200
    private val LABEL = "GhostP2 safety v1".toByteArray(Charsets.US_ASCII)

    fun compute(identityA: ByteArray, identityB: ByteArray): String {
        require(identityA.size == 32 && identityB.size == 32) { "Las claves de identidad deben tener 32 bytes" }
        val (low, high) = if (compareUnsigned(identityA, identityB) <= 0) identityA to identityB else identityB to identityA
        var h = Primitives.sha256(LABEL, low, high)
        repeat(ITERATIONS) { h = Primitives.sha256(h, low, high) }
        return (0 until 6).joinToString(" ") { i ->
            val v = ((h[4 * i].toLong() and 0xFF) shl 24) or ((h[4 * i + 1].toLong() and 0xFF) shl 16) or
                ((h[4 * i + 2].toLong() and 0xFF) shl 8) or (h[4 * i + 3].toLong() and 0xFF)
            (v % 100000).toString().padStart(5, '0')
        }
    }

    private fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
        for (i in a.indices) {
            val d = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (d != 0) return d
        }
        return 0
    }
}
