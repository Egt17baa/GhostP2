package org.ghostp2.core.crypto

/** Base32 RFC 4648: salida en minúsculas y sin relleno; decodificación estricta. */
object Base32 {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xFF)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** Acepta mayúsculas y minúsculas; rechaza caracteres inválidos y bits finales no canónicos. */
    fun decode(text: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(text.length * 5 / 8)
        var buffer = 0
        var bits = 0
        for (ch in text) {
            val v = ALPHABET.indexOf(ch.lowercaseChar())
            require(v >= 0) { "Carácter base32 no válido" }
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xFF)
                bits -= 8
            }
            buffer = buffer and ((1 shl bits) - 1)
        }
        // Los bits sobrantes deben ser cero y menos de 5 (longitud canónica).
        require(bits < 5 && buffer == 0) { "Base32 no canónico" }
        return out.toByteArray()
    }
}
