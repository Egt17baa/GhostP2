package org.ghostp2.app.photo

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "Guardar" del visor: la única vía por la que una foto llega al disco, y solo por acción del usuario
 * (el destino [target] lo elige él en el selector del sistema, `CreateDocument("image/jpeg")`).
 */
object PhotoSaver {
    /**
     * Escribe en [target] la foto [jpeg] (el array vivo del motor, que no se modifica): con la lectura
     * segura de [withIntactCopy], sin metadatos (R27: [PhotoEncoder.stripAppSegments] quita APP1–15 y COM,
     * así que el EXIF de una foto recibida nunca llega al disco) y solo si empieza por SOI y acaba en EOI.
     * `false` si los bytes ya están a cero (la foto se descartó), si la estructura no es la esperada o si
     * no se puede escribir; en todos esos casos se borra el documento que creó el selector. No registra nada.
     */
    suspend fun save(resolver: ContentResolver, target: Uri, jpeg: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val saved = try {
            write(resolver, target, jpeg)
        } catch (e: CancellationException) {
            discardNow(resolver, target)
            throw e
        } catch (_: Exception) {
            false
        }
        if (!saved) discardNow(resolver, target)
        saved
    }

    /** Borra el documento [target] que creó el selector cuando no hay nada que guardar (la foto ya no está). */
    suspend fun discard(resolver: ContentResolver, target: Uri) = withContext(Dispatchers.IO) { discardNow(resolver, target) }

    private fun write(resolver: ContentResolver, target: Uri, jpeg: ByteArray): Boolean {
        // stripAppSegments rellena con ceros la copia que recibe y devuelve un array nuevo.
        val clean = withIntactCopy(jpeg) { copy -> PhotoEncoder.stripAppSegments(copy) } ?: return false
        try {
            if (!hasJpegHeader(clean) || !endsWithEoi(clean)) return false
            val out = resolver.openOutputStream(target, "w") ?: return false
            try {
                out.write(clean)
            } finally {
                out.close()
            }
            return true
        } finally {
            clean.fill(0)
        }
    }

    /** Acaba en la marca EOI de JPEG (`FF D9`). */
    private fun endsWithEoi(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte()

    /** Borra (si se puede) el documento que creó el selector y que no llegó a escribirse entero. */
    private fun discardNow(resolver: ContentResolver, target: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, target)
        } catch (_: Exception) {
            // No es un documento borrable o el proveedor lo rechaza: se queda como esté.
        }
    }
}
