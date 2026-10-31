package org.ghostp2.core.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import kotlinx.serialization.cbor.Cbor

/**
 * Mensajes de aplicación que viajan, codificados en CBOR, dentro de las tramas cifradas del
 * [org.ghostp2.core.crypto.RatchetChannel]. Cada trama lleva exactamente un mensaje y se
 * describe a sí misma, así que pueden intercalarse (p. ej. un `Ping` entre trozos de una foto).
 *
 * Los identificadores son 24 caracteres hexadecimales en minúscula (12 bytes aleatorios).
 * Los `toString` no muestran contenido de mensajes (ni texto ni bytes de fotos).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
sealed interface AppMessage {
    @Serializable
    @SerialName("t")
    data class Text(val id: String, val text: String) : AppMessage {
        override fun toString(): String = "Text(id=$id, ${text.length} caracteres)"
    }

    @Serializable
    @SerialName("ps")
    data class PhotoStart(val id: String, val totalBytes: Int, val chunkCount: Int) : AppMessage

    @Serializable
    @SerialName("pc")
    class PhotoChunk(val id: String, val index: Int, @ByteString val data: ByteArray) : AppMessage {
        override fun equals(other: Any?): Boolean =
            other is PhotoChunk && id == other.id && index == other.index && data.contentEquals(other.data)

        override fun hashCode(): Int = (id.hashCode() * 31 + index) * 31 + data.contentHashCode()

        override fun toString(): String = "PhotoChunk(id=$id, index=$index, ${data.size} bytes)"
    }

    @Serializable
    @SerialName("pe")
    data class PhotoEnd(val id: String) : AppMessage

    @Serializable
    @SerialName("a")
    data class Ack(val id: String) : AppMessage

    @Serializable
    @SerialName("p")
    data object Ping : AppMessage

    @Serializable
    @SerialName("b")
    data object Bye : AppMessage

    @Serializable
    @SerialName("u")
    data object Unpair : AppMessage
}

/** Codificación CBOR de [AppMessage]. */
internal object AppCodec {
    fun encode(message: AppMessage): ByteArray = Cbor.encodeToByteArray(AppMessage.serializer(), message)

    /**
     * Decodifica un mensaje. Cualquier fallo del decodificador (no solo SerializationException:
     * entradas truncadas o tipos inesperados pueden lanzar otras excepciones) se traduce a
     * [ProtocolException].
     */
    fun decode(bytes: ByteArray): AppMessage = try {
        Cbor.decodeFromByteArray(AppMessage.serializer(), bytes)
    } catch (e: Exception) {
        throw ProtocolException("Mensaje de aplicación mal formado")
    }
}
