package org.ghostp2.core.crypto.noise

import org.ghostp2.core.crypto.AuthenticationException
import org.ghostp2.core.crypto.KeyPair25519
import org.ghostp2.core.crypto.Primitives

/**
 * HandshakeState de Noise (rev. 34, §5.3 y §9) para [HandshakePattern.KKpsk2] y [HandshakePattern.XXpsk3].
 *
 * Al ser patrones PSK, cada token `e` hace también MixKey(e.pub) tras MixHash, y el token `psk`
 * hace MixKeyAndHash(psk). Si [readMessage] falla, el handshake queda abortado.
 *
 * @param remoteStatic obligatoria en KKpsk2 (pre-mensaje); debe ser `null` en XXpsk3.
 * @param fixedEphemeral solo para pruebas con vectores; en producción se genera al azar.
 */
class HandshakeState(
    private val pattern: HandshakePattern,
    private val initiator: Boolean,
    prologue: ByteArray,
    private val localStatic: KeyPair25519,
    remoteStatic: ByteArray? = null,
    psk: ByteArray,
    private val fixedEphemeral: KeyPair25519? = null,
) {
    private val symmetric = SymmetricState(pattern.protocolName)
    private val psk: ByteArray = psk.copyOf()
    private var localEphemeral: KeyPair25519? = null
    private var remoteEphemeral: ByteArray? = null
    private var remoteStaticValue: ByteArray? = remoteStatic?.copyOf()
    private var messageIndex = 0
    private var failed = false
    private var splitDone = false

    init {
        require(psk.size == KEY_LEN) { "La PSK debe tener 32 bytes" }
        require(localStatic.privateKey.size == KEY_LEN && localStatic.publicKey.size == KEY_LEN) {
            "La clave estática local debe ser X25519 (32 bytes)"
        }
        fixedEphemeral?.let {
            require(it.privateKey.size == KEY_LEN && it.publicKey.size == KEY_LEN) { "Efímera fija inválida" }
        }
        if (pattern.staticsPreKnown) {
            require(remoteStatic != null && remoteStatic.size == KEY_LEN) {
                "${pattern.name} requiere la clave estática remota (32 bytes)"
            }
        } else {
            require(remoteStatic == null) { "${pattern.name} no admite clave estática remota previa" }
        }
        symmetric.mixHash(prologue)
        if (pattern.staticsPreKnown) {
            // Pre-mensajes: primero la estática del iniciador y después la del respondedor
            val rs = remoteStaticValue!!
            if (initiator) {
                symmetric.mixHash(localStatic.publicKey)
                symmetric.mixHash(rs)
            } else {
                symmetric.mixHash(rs)
                symmetric.mixHash(localStatic.publicKey)
            }
        }
    }

    val isComplete: Boolean
        get() = messageIndex == pattern.messages.size

    /** Clave estática del otro lado (conocida de antemano en KK; recibida durante el handshake en XX). */
    val remoteStaticKey: ByteArray?
        get() = remoteStaticValue?.copyOf()

    fun handshakeHash(): ByteArray = symmetric.handshakeHash()

    private fun checkTurn(writing: Boolean) {
        check(!failed) { "Handshake abortado tras un error" }
        check(!isComplete) { "El handshake ya está completo" }
        val initiatorTurn = messageIndex % 2 == 0
        check((initiatorTurn == initiator) == writing) {
            if (writing) "No es nuestro turno de enviar" else "No es nuestro turno de recibir"
        }
    }

    fun writeMessage(payload: ByteArray): ByteArray {
        checkTurn(writing = true)
        val out = java.io.ByteArrayOutputStream()
        try {
            for (token in pattern.messages[messageIndex]) {
                when (token) {
                    Token.E -> {
                        val e = fixedEphemeral ?: Primitives.generateX25519()
                        localEphemeral = e
                        out.write(e.publicKey)
                        symmetric.mixHash(e.publicKey)
                        symmetric.mixKey(e.publicKey)
                    }
                    Token.S -> out.write(symmetric.encryptAndHash(localStatic.publicKey))
                    Token.PSK -> symmetric.mixKeyAndHash(psk)
                    else -> mixDh(token)
                }
            }
            out.write(symmetric.encryptAndHash(payload))
        } catch (e: Exception) {
            failed = true
            throw e
        }
        val message = out.toByteArray()
        if (message.size > CipherState.MAX_MESSAGE) {
            failed = true
            throw IllegalArgumentException("Mensaje Noise demasiado grande")
        }
        advance()
        return message
    }

    /** Procesa un mensaje del otro lado y devuelve su carga útil; lanza [AuthenticationException] si no es válido. */
    fun readMessage(message: ByteArray): ByteArray {
        checkTurn(writing = false)
        try {
            if (message.size > CipherState.MAX_MESSAGE) throw AuthenticationException("Mensaje Noise demasiado grande")
            var offset = 0
            fun take(len: Int): ByteArray {
                if (message.size - offset < len) throw AuthenticationException("Mensaje Noise truncado")
                return message.copyOfRange(offset, offset + len).also { offset += len }
            }
            for (token in pattern.messages[messageIndex]) {
                when (token) {
                    Token.E -> {
                        val re = take(KEY_LEN)
                        remoteEphemeral = re
                        symmetric.mixHash(re)
                        symmetric.mixKey(re)
                    }
                    Token.S -> {
                        val len = if (symmetric.hasKey) KEY_LEN + CipherState.TAG_SIZE else KEY_LEN
                        remoteStaticValue = symmetric.decryptAndHash(take(len))
                    }
                    Token.PSK -> symmetric.mixKeyAndHash(psk)
                    else -> mixDh(token)
                }
            }
            val payload = symmetric.decryptAndHash(message.copyOfRange(offset, message.size))
            advance()
            return payload
        } catch (e: Exception) {
            failed = true
            throw e
        }
    }

    private fun mixDh(token: Token) {
        val e = localEphemeral
        val s = localStatic
        val re = remoteEphemeral
        val rs = remoteStaticValue
        // es: efímera del iniciador con estática del respondedor; se: al revés
        val dh = when (token) {
            Token.EE -> Primitives.x25519(e!!.privateKey, re!!)
            Token.SS -> Primitives.x25519(s.privateKey, rs!!)
            Token.ES -> if (initiator) Primitives.x25519(e!!.privateKey, rs!!) else Primitives.x25519(s.privateKey, re!!)
            Token.SE -> if (initiator) Primitives.x25519(s.privateKey, re!!) else Primitives.x25519(e!!.privateKey, rs!!)
            else -> error("Token sin DH: $token")
        }
        symmetric.mixKey(dh)
        Primitives.wipe(dh)
    }

    private fun advance() {
        messageIndex++
    }

    /**
     * Deriva los dos CipherState de transporte ya orientados: (enviar, recibir).
     * Solo puede llamarse una vez, con el handshake completo; borra efímeras, PSK y clave de encadenamiento.
     * [handshakeHash] sigue disponible después.
     */
    fun split(): Pair<CipherState, CipherState> {
        check(isComplete && !failed) { "El handshake no está completo" }
        check(!splitDone) { "split() ya se llamó" }
        splitDone = true
        val (c1, c2) = symmetric.split()
        // Solo se borran las efímeras generadas aquí; la fija pertenece al llamador (pruebas)
        localEphemeral?.let { if (it !== fixedEphemeral) Primitives.wipe(it.privateKey) }
        localEphemeral = null
        Primitives.wipe(psk)
        return if (initiator) c1 to c2 else c2 to c1
    }

    private companion object {
        const val KEY_LEN = 32
    }
}
