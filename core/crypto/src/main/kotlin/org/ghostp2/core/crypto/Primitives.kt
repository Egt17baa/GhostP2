package org.ghostp2.core.crypto

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.macs.HMac
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/** Par de claves X25519 (32 + 32 bytes). */
class KeyPair25519(val privateKey: ByteArray, val publicKey: ByteArray)

/** Fallo de autenticación o de validación criptográfica. */
class AuthenticationException(message: String) : Exception(message)

/** Primitivas criptográficas sobre la API ligera de BouncyCastle (sin proveedor JCA). */
object Primitives {
    private const val TAG_SIZE = 16
    private const val KEY_SIZE = 32
    private const val NONCE_SIZE = 12

    private val secureRandom = SecureRandom()

    fun randomBytes(n: Int): ByteArray {
        require(n >= 0) { "El número de bytes no puede ser negativo" }
        return ByteArray(n).also { secureRandom.nextBytes(it) }
    }

    /** Genera un par X25519; BouncyCastle aplica el "clamping" a la clave privada. */
    fun generateX25519(): KeyPair25519 {
        val priv = X25519PrivateKeyParameters(secureRandom)
        return KeyPair25519(priv.encoded, priv.generatePublicKey().encoded)
    }

    fun x25519PublicFromPrivate(privateKey: ByteArray): KeyPair25519 {
        require(privateKey.size == KEY_SIZE) { "La clave privada X25519 debe tener 32 bytes" }
        val pub = X25519PrivateKeyParameters(privateKey, 0).generatePublicKey().encoded
        return KeyPair25519(privateKey.copyOf(), pub)
    }

    /** Acuerdo de clave X25519; rechaza resultados todo ceros (puntos de orden bajo). */
    fun x25519(privateKey: ByteArray, publicKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_SIZE) { "La clave privada X25519 debe tener 32 bytes" }
        require(publicKey.size == KEY_SIZE) { "La clave pública X25519 debe tener 32 bytes" }
        val out = ByteArray(KEY_SIZE)
        try {
            X25519PrivateKeyParameters(privateKey, 0).generateSecret(X25519PublicKeyParameters(publicKey, 0), out, 0)
        } catch (e: IllegalStateException) {
            // BouncyCastle lanza esta excepción ante resultado todo ceros
            throw AuthenticationException("Secreto compartido X25519 inválido (punto de orden bajo)")
        }
        var acc = 0
        for (b in out) acc = acc or b.toInt()
        if (acc == 0) {
            throw AuthenticationException("Secreto compartido X25519 inválido (punto de orden bajo)")
        }
        return out
    }

    fun sha256(vararg parts: ByteArray): ByteArray {
        val d = SHA256Digest()
        for (p in parts) d.update(p, 0, p.size)
        return ByteArray(d.digestSize).also { d.doFinal(it, 0) }
    }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = HMac(SHA256Digest())
        mac.init(KeyParameter(key))
        for (p in parts) mac.update(p, 0, p.size)
        return ByteArray(mac.macSize).also { mac.doFinal(it, 0) }
    }

    /** HKDF (RFC 5869) con HMAC-SHA256. */
    fun hkdf(salt: ByteArray, ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..(255 * 32)) { "Longitud HKDF fuera de rango" }
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(ikm, salt, info))
        return ByteArray(length).also { gen.generateBytes(it, 0, length) }
    }

    /** Devuelve texto cifrado || etiqueta de 16 bytes. */
    fun chachaSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray {
        val cipher = initChacha(true, key, nonce, aad)
        val out = ByteArray(plaintext.size + TAG_SIZE)
        var len = cipher.processBytes(plaintext, 0, plaintext.size, out, 0)
        len += cipher.doFinal(out, len)
        check(len == out.size) { "Longitud de salida AEAD inesperada" }
        return out
    }

    fun chachaOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        if (ciphertext.size < TAG_SIZE) {
            throw AuthenticationException("Texto cifrado demasiado corto")
        }
        val cipher = initChacha(false, key, nonce, aad)
        val out = ByteArray(ciphertext.size - TAG_SIZE)
        try {
            var len = cipher.processBytes(ciphertext, 0, ciphertext.size, out, 0)
            len += cipher.doFinal(out, len)
            check(len == out.size) { "Longitud de salida AEAD inesperada" }
        } catch (e: org.bouncycastle.crypto.InvalidCipherTextException) {
            wipe(out)
            throw AuthenticationException("Etiqueta de autenticación inválida")
        }
        return out
    }

    private fun initChacha(encrypt: Boolean, key: ByteArray, nonce: ByteArray, aad: ByteArray): ChaCha20Poly1305 {
        require(key.size == KEY_SIZE) { "La clave ChaCha20-Poly1305 debe tener 32 bytes" }
        require(nonce.size == NONCE_SIZE) { "El nonce ChaCha20-Poly1305 debe tener 12 bytes" }
        return ChaCha20Poly1305().also {
            it.init(encrypt, AEADParameters(KeyParameter(key), TAG_SIZE * 8, nonce, aad))
        }
    }

    /** Comparación en tiempo constante. */
    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = java.security.MessageDigest.isEqual(a, b)

    /** Rellena con ceros los arreglos dados. */
    fun wipe(vararg arrays: ByteArray) {
        for (a in arrays) a.fill(0)
    }
}
