package org.ghostp2.core.crypto.noise

import org.ghostp2.core.crypto.Primitives

/** SymmetricState de Noise (rev. 34, §5.2) con SHA-256 (HASHLEN = 32). */
internal class SymmetricState(protocolName: String) {
    private var ck: ByteArray
    private var h: ByteArray
    private var cipher = CipherState(null)

    init {
        val name = protocolName.toByteArray(Charsets.US_ASCII)
        h = if (name.size <= HASHLEN) name.copyOf(HASHLEN) else Primitives.sha256(name)
        ck = h.copyOf()
    }

    val hasKey: Boolean
        get() = cipher.hasKey

    fun handshakeHash(): ByteArray = h.copyOf()

    fun mixHash(data: ByteArray) {
        val old = h
        h = Primitives.sha256(old, data)
        Primitives.wipe(old)
    }

    fun mixKey(ikm: ByteArray) {
        val (newCk, tempK) = hkdf2(ck, ikm)
        Primitives.wipe(ck)
        ck = newCk
        cipher.wipe()
        cipher = CipherState(tempK)
        Primitives.wipe(tempK)
    }

    fun mixKeyAndHash(ikm: ByteArray) {
        val out = hkdf(ck, ikm, 3)
        Primitives.wipe(ck)
        ck = out[0]
        mixHash(out[1])
        cipher.wipe()
        cipher = CipherState(out[2])
        Primitives.wipe(out[1], out[2])
    }

    fun encryptAndHash(plaintext: ByteArray): ByteArray {
        val ct = cipher.encryptWithAd(h, plaintext)
        mixHash(ct)
        return ct
    }

    fun decryptAndHash(ciphertext: ByteArray): ByteArray {
        val pt = cipher.decryptWithAd(h, ciphertext)
        mixHash(ciphertext)
        return pt
    }

    /** Devuelve (c1, c2) y borra la clave de encadenamiento; `h` se conserva para [handshakeHash]. */
    fun split(): Pair<CipherState, CipherState> {
        val (k1, k2) = hkdf2(ck, ByteArray(0))
        val pair = CipherState(k1) to CipherState(k2)
        Primitives.wipe(k1, k2, ck)
        cipher.wipe()
        return pair
    }

    fun wipe() {
        Primitives.wipe(ck, h)
        cipher.wipe()
    }

    private fun hkdf2(chainingKey: ByteArray, ikm: ByteArray): Pair<ByteArray, ByteArray> {
        val out = hkdf(chainingKey, ikm, 2)
        return out[0] to out[1]
    }

    private companion object {
        const val HASHLEN = 32

        /** HKDF de Noise (§4.3), distinto de RFC 5869: sin "info" y con contador tras la salida previa. */
        fun hkdf(chainingKey: ByteArray, ikm: ByteArray, outputs: Int): List<ByteArray> {
            val tempKey = Primitives.hmacSha256(chainingKey, ikm)
            val result = ArrayList<ByteArray>(outputs)
            var prev = ByteArray(0)
            for (i in 1..outputs) {
                prev = Primitives.hmacSha256(tempKey, prev, byteArrayOf(i.toByte()))
                result.add(prev)
            }
            Primitives.wipe(tempKey)
            return result
        }
    }
}
