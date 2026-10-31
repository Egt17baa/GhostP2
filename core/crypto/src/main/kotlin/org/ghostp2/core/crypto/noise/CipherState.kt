package org.ghostp2.core.crypto.noise

import org.ghostp2.core.crypto.AuthenticationException
import org.ghostp2.core.crypto.Primitives

/**
 * CipherState de Noise (rev. 34, §5.1) con ChaCha20-Poly1305.
 * El nonce es 4 bytes a cero seguidos del contador de 64 bits en little-endian.
 * Sin clave, cifrar/descifrar devuelve el texto tal cual (solo durante el handshake).
 */
class CipherState internal constructor(key: ByteArray?) {
    private var k: ByteArray? = key?.copyOf()

    /** Contador tratado como entero sin signo de 64 bits; 2^64-1 está reservado. */
    private var n: Long = 0

    /** Copia de la clave actual (para derivar el ratchet), o `null` si aún no hay clave. */
    val key: ByteArray?
        get() = k?.copyOf()

    internal val hasKey: Boolean
        get() = k != null

    private fun nonce(): ByteArray {
        // El valor 2^64-1 (-1 como Long) está reservado por la especificación
        if (n == -1L) throw IllegalStateException("Contador de nonce agotado")
        val out = ByteArray(12)
        var v = n
        for (i in 4 until 12) {
            out[i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
        return out
    }

    fun encryptWithAd(ad: ByteArray, plaintext: ByteArray): ByteArray {
        val key = k ?: return plaintext.copyOf()
        require(plaintext.size <= MAX_MESSAGE - TAG_SIZE) { "Mensaje Noise demasiado grande" }
        val ct = Primitives.chachaSeal(key, nonce(), ad, plaintext)
        n++
        return ct
    }

    /** Lanza [AuthenticationException] si la etiqueta no es válida; en ese caso el contador no avanza. */
    fun decryptWithAd(ad: ByteArray, ciphertext: ByteArray): ByteArray {
        val key = k ?: return ciphertext.copyOf()
        if (ciphertext.size > MAX_MESSAGE) throw AuthenticationException("Mensaje Noise demasiado grande")
        if (ciphertext.size < TAG_SIZE) throw AuthenticationException("Mensaje Noise demasiado corto")
        val pt = Primitives.chachaOpen(key, nonce(), ad, ciphertext)
        n++
        return pt
    }

    /** Borra la clave de memoria; el estado queda inutilizable para cifrar. */
    fun wipe() {
        k?.let { Primitives.wipe(it) }
        k = null
        n = 0
    }

    internal companion object {
        const val TAG_SIZE = 16
        const val MAX_MESSAGE = 65535
    }
}
