package org.ghostp2.core.crypto.noise

/** Tokens de los patrones Noise (rev. 34, §7 y §9). */
internal enum class Token { E, S, EE, ES, SE, SS, PSK }

/**
 * Patrones de handshake admitidos. Solo DH 25519, cifrado ChaChaPoly y hash SHA-256.
 *
 * - KKpsk2: pre-mensajes `-> s`, `<- s`; mensajes `-> e, es, ss` · `<- e, ee, se, psk`.
 * - XXpsk3: `-> e` · `<- e, ee, s, es` · `-> s, se, psk`.
 */
enum class HandshakePattern(val protocolName: String) {
    KKpsk2("Noise_KKpsk2_25519_ChaChaPoly_SHA256"),
    XXpsk3("Noise_XXpsk3_25519_ChaChaPoly_SHA256");

    /** Indica si ambos lados conocen de antemano la clave estática del otro (pre-mensajes `s`). */
    internal val staticsPreKnown: Boolean
        get() = this == KKpsk2

    /** Mensajes del handshake; el índice par lo envía el iniciador y el impar el respondedor. */
    internal val messages: List<List<Token>>
        get() = when (this) {
            KKpsk2 -> listOf(
                listOf(Token.E, Token.ES, Token.SS),
                listOf(Token.E, Token.EE, Token.SE, Token.PSK),
            )
            XXpsk3 -> listOf(
                listOf(Token.E),
                listOf(Token.E, Token.EE, Token.S, Token.ES),
                listOf(Token.S, Token.SE, Token.PSK),
            )
        }
}
