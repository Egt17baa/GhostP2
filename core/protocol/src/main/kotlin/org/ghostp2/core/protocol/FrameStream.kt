package org.ghostp2.core.protocol

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer

/** El otro lado envió datos mal formados (trama, carga o campos inválidos). */
class ProtocolException(message: String) : Exception(message)

/**
 * El emparejamiento ([Pairing]) o el handshake de sesión ([SessionHandshake]) no pudo
 * completarse. [reason] permite a la app reaccionar de forma distinta (p. ej. "identidad
 * cambiada, volved a emparejar" frente a "reintentar más tarde").
 */
class HandshakeFailedException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason {
        /** Autenticación fallida: secreto o pairSecret distinto, trama de confirmación no válida o canal no auténtico. */
        AUTH,

        /** Sesión: ningún contacto de la lista de candidatos autentica msg1 (identidad desconocida o cambiada). */
        UNKNOWN_CALLER,

        /** La conexión se cerró o falló (EOF/IOException): normalmente el otro lado abortó. */
        CONNECTION,

        /** Venció NUESTRO plazo (60 s emparejamiento, 30 s sesión). */
        TIMEOUT,

        /** Estado interno no válido (handshake incompleto o usado tras un error). */
        STATE,
    }
}

/**
 * Tramas sobre un flujo de bytes (en la app, un socket Tor): prefijo de longitud big-endian de
 * 4 bytes seguido de la carga, con 1 ≤ longitud ≤ [MAX_FRAME].
 *
 * Los envíos y las recepciones se serializan internamente (un [Mutex] para cada sentido), así
 * que varias corrutinas pueden llamar a [send] a la vez sin mezclar bytes. La E/S bloqueante se
 * hace en [Dispatchers.IO]; si la corrutina se cancela con una operación en curso, el flujo se
 * cierra (es la única forma de desbloquear un socket y, además, la trama quedaría a medias).
 */
class FrameStream(input: InputStream, private val output: OutputStream, private val closeable: Closeable) {
    private val input = DataInputStream(input)
    private val sendMutex = Mutex()
    private val receiveMutex = Mutex()

    suspend fun send(frame: ByteArray) {
        require(frame.size <= MAX_FRAME) { "Trama demasiado grande: ${frame.size} > $MAX_FRAME" }
        require(frame.isNotEmpty()) { "No se admiten tramas vacías" }
        val buffer = ByteBuffer.allocate(HEADER + frame.size).putInt(frame.size).put(frame).array()
        try {
            sendMutex.withLock {
                io {
                    output.write(buffer)
                    output.flush()
                }
            }
        } finally {
            buffer.fill(0)
        }
    }

    /**
     * Lee una trama completa. Valida la longitud antes de reservar memoria: si es ≤ 0 o mayor que
     * [MAX_FRAME] lanza [ProtocolException]. Si el flujo se cierra (antes o a mitad de trama)
     * lanza [EOFException].
     */
    suspend fun receive(): ByteArray = receiveMutex.withLock {
        io {
            val length = try {
                input.readInt()
            } catch (e: EOFException) {
                throw EOFException("Flujo cerrado")
            }
            if (length <= 0 || length > MAX_FRAME) throw ProtocolException("Longitud de trama inválida: $length")
            val frame = ByteArray(length)
            try {
                input.readFully(frame)
            } catch (e: EOFException) {
                frame.fill(0)
                throw EOFException("Flujo cerrado a mitad de trama")
            }
            frame
        }
    }

    fun close() {
        runCatching { closeable.close() }
    }

    /** Ejecuta E/S bloqueante en Dispatchers.IO; si nos cancelan, cierra el flujo para desbloquearla. */
    private suspend fun <T> io(block: () -> T): T = coroutineScope {
        val job = async(Dispatchers.IO) {
            try {
                block()
            } catch (e: IOException) {
                // Si el error viene de cerrar el flujo por cancelación, se informa como cancelación
                if (!isActive) throw CancellationException("E/S cancelada").apply { initCause(e) }
                throw e
            }
        }
        try {
            job.await()
        } catch (e: CancellationException) {
            close()
            throw e
        }
    }

    companion object {
        const val MAX_FRAME = 65_536
        private const val HEADER = 4
    }
}
