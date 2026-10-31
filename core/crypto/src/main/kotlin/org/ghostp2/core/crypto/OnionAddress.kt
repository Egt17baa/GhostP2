package org.ghostp2.core.crypto

import org.bouncycastle.crypto.digests.SHA3Digest

/** Direcciones onion v3: clave(32) || checksum(2) || versión(1)=3, en base32 (56 caracteres). */
object OnionAddress {
    private const val SUFFIX = ".onion"
    private val CHECKSUM_PREFIX = ".onion checksum".toByteArray(Charsets.US_ASCII)

    /** Minúsculas, sin espacios laterales y sin el sufijo ".onion". */
    fun normalize(address: String): String = address.trim().lowercase().removeSuffix(SUFFIX)

    fun isValid(address: String): Boolean {
        val n = normalize(address)
        if (n.length != 56) return false
        val raw = try {
            Base32.decode(n)
        } catch (e: IllegalArgumentException) {
            return false
        }
        if (raw.size != 35 || raw[34].toInt() != 3) return false
        val pubkey = raw.copyOfRange(0, 32)
        val digest = SHA3Digest(256)
        digest.update(CHECKSUM_PREFIX, 0, CHECKSUM_PREFIX.size)
        digest.update(pubkey, 0, 32)
        digest.update(byteArrayOf(3), 0, 1)
        val h = ByteArray(32)
        digest.doFinal(h, 0)
        return Primitives.constantTimeEquals(h.copyOfRange(0, 2), raw.copyOfRange(32, 34))
    }
}
