package org.ghostp2.core.crypto

/** Invitación de emparejamiento: `ghostp2:<onion v3, 56 car.>.<secreto base32, 26 car.>`. */
class Invitation(val onion: String, val secret: ByteArray) {
    init {
        require(OnionAddress.isValid(onion) && !onion.trim().lowercase().endsWith(".onion")) {
            "Invitación no válida: dirección onion incorrecta"
        }
        require(secret.size == SECRET_SIZE) { "Invitación no válida: el secreto debe tener 16 bytes" }
    }

    fun encode(): String = PREFIX + OnionAddress.normalize(onion) + "." + Base32.encode(secret)

    override fun equals(other: Any?): Boolean =
        other is Invitation && OnionAddress.normalize(onion) == OnionAddress.normalize(other.onion) &&
            secret.contentEquals(other.secret)

    override fun hashCode(): Int = 31 * OnionAddress.normalize(onion).hashCode() + secret.contentHashCode()

    /** No imprime el secreto. */
    override fun toString(): String = "Invitation(onion=$onion, secret=***)"

    companion object {
        const val PREFIX = "ghostp2:"
        const val SECRET_SIZE = 16

        fun parse(text: String): Invitation {
            val t = text.filterNot { it.isWhitespace() }.lowercase()
            require(t.startsWith(PREFIX)) { "Invitación no válida: falta el prefijo ghostp2:" }
            val parts = t.removePrefix(PREFIX).split('.')
            require(parts.size == 2) { "Invitación no válida: formato incorrecto" }
            val (onion, secretText) = parts
            require(onion.length == 56 && OnionAddress.isValid(onion)) {
                "Invitación no válida: dirección onion incorrecta"
            }
            require(secretText.length == 26) { "Invitación no válida: longitud del secreto incorrecta" }
            val secret = try {
                Base32.decode(secretText)
            } catch (e: IllegalArgumentException) {
                throw IllegalArgumentException("Invitación no válida: secreto ilegible")
            }
            return Invitation(onion, secret)
        }
    }
}
