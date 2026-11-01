package org.ghostp2.app.photo

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * "Guardar" del visor: la única vía por la que una foto llega al disco, y solo por acción del usuario
 * (el destino [target] lo elige él en el selector del sistema, `CreateDocument("image/jpeg")`).
 */
object PhotoSaver {
    /**
     * Escribe en [target] exactamente los bytes de [jpeg] (el array vivo del motor, que no se modifica),
     * con la lectura segura de [withIntactCopy]. `false` si los bytes ya están a cero (la foto se
     * descartó) o si no se puede escribir; en ese caso se intenta borrar el documento vacío que creó el
     * selector. No registra nada.
     */
    suspend fun save(resolver: ContentResolver, target: Uri, jpeg: ByteArray): Boolean = withContext(Dispatchers.IO) {
        val saved = withIntactCopy(jpeg) { copy ->
            try {
                val out = resolver.openOutputStream(target, "w") ?: return@withIntactCopy null
                out.use { it.write(copy) }
                true
            } catch (_: IOException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        } ?: false
        if (!saved) discard(resolver, target)
        saved
    }

    /** Borra (si se puede) el documento que el selector creó y que no llegó a escribirse. */
    private fun discard(resolver: ContentResolver, target: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, target)
        } catch (_: Exception) {
            // No es un documento borrable o el proveedor lo rechaza: se queda como esté.
        }
    }
}
