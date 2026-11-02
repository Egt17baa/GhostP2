package org.ghostp2.core.crypto

import org.ghostp2.core.crypto.noise.HandshakeState

/**
 * Violación de seguridad del canal: contador inesperado, trama manipulada, tamaño inválido,
 * contador agotado o canal destruido. Se llama así (y no `SecurityException`) para no ocultar
 * `java.lang.SecurityException`.
 */
class ChannelSecurityException(message: String) : Exception(message)

/**
 * Canal de transporte con ratchet simétrico por mensaje sobre un handshake Noise completo.
 *
 * Cada sentido tiene su propia cadena: `mk = HMAC-SHA256(ck, 0x01)`, `ck' = HMAC-SHA256(ck, 0x02)`.
 * La clave de mensaje se usa una sola vez y se borra, de modo que comprometer el estado actual
 * no descubre mensajes anteriores (secreto hacia adelante por mensaje).
 *
 * Trama: `[versión=1][contador u32 BE][ChaCha20-Poly1305(mk, nonce = 8 ceros || contador BE,
 * aad = 5 bytes de cabecera, relleno(texto))]`, con relleno `u32 BE longitud || datos || ceros`
 * hasta múltiplo de 256. El contador es estrictamente secuencial: no se admiten repeticiones,
 * saltos ni desorden. La cadena de recepción solo avanza si la trama autentica.
 */
class RatchetChannel private constructor(
    sendChain: ByteArray,
    recvChain: ByteArray,
    private var sendCounter: Long,
    private var recvCounter: Long,
) {
    private var sendCk: ByteArray? = sendChain.copyOf()
    private var recvCk: ByteArray? = recvChain.copyOf()

    @Synchronized
    fun encrypt(plaintext: ByteArray): ByteArray {
        val ck = sendCk ?: throw ChannelSecurityException("Canal destruido")
        require(plaintext.size <= MAX_PLAINTEXT) { "Texto demasiado grande para una trama" }
        if (sendCounter >= COUNTER_LIMIT) throw ChannelSecurityException("Contador de envío agotado")

        val header = header(sendCounter)
        val padded = pad(plaintext)
        val mk = Primitives.hmacSha256(ck, MK_LABEL)
        val nextCk = Primitives.hmacSha256(ck, CK_LABEL)
        try {
            val body = Primitives.chachaSeal(mk, nonce(sendCounter), header, padded)
            // Avance de la cadena: se borra la clave anterior
            Primitives.wipe(ck)
            sendCk = nextCk
            sendCounter++
            return header + body
        } catch (e: Throwable) {
            Primitives.wipe(nextCk)
            throw e
        } finally {
            Primitives.wipe(mk, padded)
        }
    }

    @Synchronized
    fun decrypt(frame: ByteArray): ByteArray {
        val ck = recvCk ?: throw ChannelSecurityException("Canal destruido")
        // Validaciones baratas antes de cualquier operación criptográfica
        if (frame.size < MIN_FRAME || frame.size > MAX_FRAME || (frame.size - HEADER_SIZE - TAG_SIZE) % BLOCK != 0) {
            throw ChannelSecurityException("Tamaño de trama inválido")
        }
        if (frame[0] != VERSION) throw ChannelSecurityException("Versión de trama desconocida")
        val counter = readU32(frame, 1)
        if (counter != recvCounter) throw ChannelSecurityException("Contador inesperado")
        if (recvCounter >= COUNTER_LIMIT) throw ChannelSecurityException("Contador de recepción agotado")

        val header = frame.copyOfRange(0, HEADER_SIZE)
        val body = frame.copyOfRange(HEADER_SIZE, frame.size)
        val mk = Primitives.hmacSha256(ck, MK_LABEL)
        val padded = try {
            Primitives.chachaOpen(mk, nonce(counter), header, body)
        } catch (e: AuthenticationException) {
            throw ChannelSecurityException("Trama no auténtica")
        } finally {
            Primitives.wipe(mk)
        }
        try {
            // Una trama auténtica con relleno no canónico se rechaza SIN avanzar la cadena. Como solo
            // el par legítimo puede producirla, indica un emisor defectuoso o malicioso: la capa de
            // sesión debe tratar esta excepción como fatal y cerrar la sesión.
            val plaintext = unpad(padded)
            // Solo tras autenticar y validar el relleno se avanza la cadena
            val nextCk = Primitives.hmacSha256(ck, CK_LABEL)
            Primitives.wipe(ck)
            recvCk = nextCk
            recvCounter++
            return plaintext
        } finally {
            Primitives.wipe(padded)
        }
    }

    /** Borra ambas cadenas; a partir de aquí cifrar o descifrar lanza [ChannelSecurityException]. */
    @Synchronized
    fun destroy() {
        sendCk?.let { Primitives.wipe(it) }
        recvCk?.let { Primitives.wipe(it) }
        sendCk = null
        recvCk = null
    }

    companion object {
        const val MAX_PLAINTEXT = 49_152

        private const val VERSION: Byte = 1
        private const val HEADER_SIZE = 5
        private const val TAG_SIZE = 16
        private const val BLOCK = 256
        private const val LEN_PREFIX = 4

        /** 2^32-1 queda reservado: el último contador utilizable es 2^32-2. */
        private const val COUNTER_LIMIT = 0xFFFF_FFFFL

        private val MK_LABEL = byteArrayOf(0x01)
        private val CK_LABEL = byteArrayOf(0x02)
        private val CHAIN_INFO = "GhostP2 chain v1".toByteArray(Charsets.US_ASCII)

        private val MIN_FRAME = HEADER_SIZE + BLOCK + TAG_SIZE
        private val MAX_FRAME = HEADER_SIZE + paddedSize(MAX_PLAINTEXT) + TAG_SIZE

        /**
         * Crea el canal a partir de un handshake completo. Consume [HandshakeState.split] (solo
         * puede llamarse una vez) y borra los CipherState resultantes y las copias de sus claves.
         * El [HandshakeState] no se borra aquí: lo borra el llamador cuando ya no lo necesite.
         * Como `split()` ya devuelve (enviar, recibir) orientados según el rol, [initiator] no
         * interviene en la derivación; se conserva por contrato de la API.
         */
        @Suppress("UNUSED_PARAMETER")
        fun fromHandshake(hs: HandshakeState, initiator: Boolean): RatchetChannel {
            val (send, recv) = hs.split()
            var sendKey: ByteArray? = null
            var recvKey: ByteArray? = null
            var sendChain: ByteArray? = null
            var recvChain: ByteArray? = null
            var h: ByteArray? = null
            try {
                h = hs.handshakeHash()
                sendKey = checkNotNull(send.key) { "CipherState de envío sin clave" }
                recvKey = checkNotNull(recv.key) { "CipherState de recepción sin clave" }
                sendChain = Primitives.hkdf(h, sendKey, CHAIN_INFO, 32)
                recvChain = Primitives.hkdf(h, recvKey, CHAIN_INFO, 32)
                return RatchetChannel(sendChain, recvChain, 0, 0)
            } finally {
                h?.let { Primitives.wipe(it) }
                sendKey?.let { Primitives.wipe(it) }
                recvKey?.let { Primitives.wipe(it) }
                sendChain?.let { Primitives.wipe(it) }
                recvChain?.let { Primitives.wipe(it) }
                send.wipe()
                recv.wipe()
            }
        }

        /** Solo para pruebas: canal con cadenas y contadores arbitrarios (p. ej. agotamiento). */
        internal fun fromChainKeys(
            sendChain: ByteArray,
            recvChain: ByteArray,
            sendCounter: Long = 0,
            recvCounter: Long = 0,
        ): RatchetChannel {
            require(sendChain.size == 32 && recvChain.size == 32) { "Las cadenas deben tener 32 bytes" }
            require(sendCounter in 0..COUNTER_LIMIT && recvCounter in 0..COUNTER_LIMIT) { "Contador fuera de rango" }
            return RatchetChannel(sendChain, recvChain, sendCounter, recvCounter)
        }

        /** Tamaño del bloque relleno (prefijo de longitud incluido) para [len] bytes de texto. */
        internal fun paddedSize(len: Int): Int = (LEN_PREFIX + len + BLOCK - 1) / BLOCK * BLOCK

        private fun pad(data: ByteArray): ByteArray {
            val out = ByteArray(paddedSize(data.size))
            writeU32(out, 0, data.size.toLong())
            data.copyInto(out, LEN_PREFIX)
            return out
        }

        private fun unpad(padded: ByteArray): ByteArray {
            val len = readU32(padded, 0)
            if (len > MAX_PLAINTEXT || len > padded.size - LEN_PREFIX) {
                throw ChannelSecurityException("Longitud de relleno inválida")
            }
            // El relleno debe ser canónico: el mínimo número de bloques y todo ceros
            if (paddedSize(len.toInt()) != padded.size) throw ChannelSecurityException("Relleno no canónico")
            var acc = 0
            for (i in LEN_PREFIX + len.toInt() until padded.size) acc = acc or padded[i].toInt()
            if (acc != 0) throw ChannelSecurityException("Relleno no canónico")
            return padded.copyOfRange(LEN_PREFIX, LEN_PREFIX + len.toInt())
        }

        private fun header(counter: Long): ByteArray =
            ByteArray(HEADER_SIZE).also {
                it[0] = VERSION
                writeU32(it, 1, counter)
            }

        private fun nonce(counter: Long): ByteArray = ByteArray(12).also { writeU32(it, 8, counter) }

        private fun writeU32(dst: ByteArray, off: Int, v: Long) {
            dst[off] = (v ushr 24).toByte()
            dst[off + 1] = (v ushr 16).toByte()
            dst[off + 2] = (v ushr 8).toByte()
            dst[off + 3] = v.toByte()
        }

        private fun readU32(src: ByteArray, off: Int): Long =
            ((src[off].toLong() and 0xFF) shl 24) or
                ((src[off + 1].toLong() and 0xFF) shl 16) or
                ((src[off + 2].toLong() and 0xFF) shl 8) or
                (src[off + 3].toLong() and 0xFF)
    }
}
