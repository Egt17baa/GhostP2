package org.ghostp2.tor

import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3
import io.matthewnelson.kmp.tor.runtime.core.key.ED25519_V3.PrivateKey.Companion.toED25519_V3PrivateKey
import io.matthewnelson.kmp.tor.runtime.core.key.X25519
import io.matthewnelson.kmp.tor.runtime.core.key.X25519.PrivateKey.Companion.toX25519PrivateKey
import io.matthewnelson.kmp.tor.runtime.core.key.X25519.PublicKey.Companion.toX25519PublicKey
import io.matthewnelson.kmp.tor.runtime.core.net.OnionAddress
import io.matthewnelson.kmp.tor.runtime.core.net.OnionAddress.V3.Companion.toOnionAddressV3

/**
 * Conversiones entre los `ByteArray` del motor y los tipos de clave de kmp-tor. Todas copian: el array
 * del llamante y el objeto de kmp-tor son independientes (borrar uno no afecta al otro). Los errores son
 * siempre [IllegalArgumentException] con un mensaje que no incluye claves ni direcciones.
 */
internal object TorKeys {
    private const val ONION_LENGTH = 56
    private const val SUFFIX = ".onion"

    /** Clave ED25519-V3 de Tor (64 bytes, formato expandido) → objeto de kmp-tor (con su propia copia). */
    fun privateKey(bytes: ByteArray): ED25519_V3.PrivateKey = keyOrThrow { bytes.toED25519_V3PrivateKey() }

    /** Copia nueva de los 64 bytes de la clave; lanza si ya se destruyó. */
    fun privateKeyBytes(key: ED25519_V3.PrivateKey): ByteArray = keyOrThrow { key.encoded() }

    fun x25519PublicKey(bytes: ByteArray): X25519.PublicKey = keyOrThrow { bytes.toX25519PublicKey() }

    fun x25519PublicBytes(key: X25519.PublicKey): ByteArray = key.encoded()

    /** Clave x25519 privada de autorización de cliente → objeto de kmp-tor (con su propia copia). */
    fun x25519PrivateKey(bytes: ByteArray): X25519.PrivateKey = keyOrThrow { bytes.toX25519PrivateKey() }

    /**
     * Dirección v3 exacta: 56 caracteres base32 con o sin ".onion" (sin subdominios, puertos ni URL),
     * con la suma de comprobación y la versión válidas.
     */
    fun onionAddress(onion: String): OnionAddress.V3 {
        val bare = onion.trim().lowercase().removeSuffix(SUFFIX)
        require(bare.length == ONION_LENGTH && bare.all { it in 'a'..'z' || it in '2'..'7' }) {
            "Dirección .onion v3 no válida"
        }
        return keyOrThrow { bare.toOnionAddressV3() }
    }

    /** Dirección en el formato del motor: minúsculas y sin ".onion". */
    fun onionString(address: OnionAddress): String = address.value.lowercase().removeSuffix(SUFFIX)

    /** Uniformiza los errores de kmp-tor (`InvalidKeyException`, `IllegalArgumentException`…) sin su mensaje. */
    private inline fun <T> keyOrThrow(block: () -> T): T = try {
        block()
    } catch (e: IllegalStateException) {
        throw IllegalArgumentException("Clave destruida", e)
    } catch (e: Exception) {
        throw IllegalArgumentException("Clave o dirección no válida")
    }
}
