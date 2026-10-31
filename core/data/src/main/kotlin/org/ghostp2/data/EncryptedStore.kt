package org.ghostp2.data

import android.system.Os
import android.system.OsConstants
import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException

/**
 * El almacenamiento existe pero no se puede leer ni autenticar (bytes alterados, clave distinta o
 * desaparecida, formato desconocido). Nunca se sustituye en silencio por el valor por defecto: quien
 * llama informa al usuario y, si este acepta empezar de cero, llama a [EncryptedStore.reset]. El
 * mensaje y la causa nunca contienen texto en claro.
 */
class StoreCorruptedException(cause: Throwable) : Exception("Almacenamiento ilegible", cause)

/**
 * Fallo pasajero al leer o escribir (error de E/S, Keystore ocupado o no disponible): los datos pueden
 * seguir intactos, así que se ofrece "Reintentar", nunca "Empezar de cero". Es un tipo hermano, no una
 * subclase, de [StoreCorruptedException]: quien trata la corrupción borrando (los `wipe()` sobre un
 * almacén dañado) no debe borrar por un fallo que se puede reintentar.
 */
class StoreUnavailableException(cause: Throwable) : Exception("Almacenamiento no disponible", cause)

/** Causa saneada de un CBOR ilegible: la excepción original podría citar parte del texto en claro. */
private class UnreadableContentException : Exception("Contenido descifrado no decodificable")

/**
 * CBOR del almacén. `ignoreUnknownKeys`: un archivo escrito por una versión más nueva de la app (con
 * campos que esta no conoce) se sigue leyendo en vez de darlo por ilegible (que solo ofrecería "Empezar
 * de cero"). Un valor desconocido de un enum o un campo obligatorio ausente siguen siendo ilegibles.
 */
@OptIn(ExperimentalSerializationApi::class)
private val STORE_CBOR = Cbor { ignoreUnknownKeys = true }

/** Causa de un archivo que no empieza por la versión de formato conocida. */
private class UnknownFormatException : Exception("Versión de formato desconocida")

/**
 * Un objeto completo `T` guardado en `file` como `versión(1 byte) ‖ AEAD(CBOR(T))`, con datos asociados
 * `versión ‖ associatedData` (una etiqueta fija y versionada, p. ej. "ghostp2-store-v1").
 *
 * - Escritura atómica: `<file>.tmp` + `fsync` + `rename` sobre el destino. Un `.tmp` huérfano (corte a
 *   mitad de escritura) se ignora y se borra en el primer acceso.
 * - Sin archivo ⇒ `default`. Un valor para el que `isEmpty` es cierto no se escribe: se borra el archivo.
 * - Toda lectura-modificación-escritura pasa por un único [Mutex]; [data] publica tras cada commit, en
 *   orden, nunca una instantánea anterior después de otra más reciente.
 * - Archivo no auténtico o indecodificable ⇒ [StoreCorruptedException] (también en [update], que entonces
 *   no escribe nada). Solo [reset] borra un archivo dañado. Los campos CBOR desconocidos (de una versión
 *   más nueva) se ignoran: no hacen el archivo ilegible.
 * - Error de E/S al leer o escribir ⇒ [StoreUnavailableException]. Las excepciones que lance el propio
 *   `aead` y no sean [GeneralSecurityException] (p. ej. las de [RecoverableAead]) salen tal cual.
 * - El CBOR en claro que crea esta clase se pone a cero tras cifrar y tras decodificar.
 *
 * Una sola instancia por archivo y proceso: guarda en memoria el último valor confirmado.
 */
class EncryptedStore<T>(
    private val file: File,
    private val aead: Aead,
    private val serializer: KSerializer<T>,
    private val default: T,
    associatedData: ByteArray,
    private val isEmpty: (T) -> Boolean = { it == default },
) {
    /** Envoltorio para distinguir "no cargado" de un `T` nulo y para que cada commit se emita. */
    private class Loaded<T>(val value: T)

    private val associatedData = byteArrayOf(FORMAT_VERSION) + associatedData
    private val tmp = File(file.parentFile, file.name + ".tmp")
    private val mutex = Mutex()
    private val latest = MutableStateFlow<Loaded<T>?>(null)
    private var orphanChecked = false

    /**
     * Último valor confirmado: se siembra desde el disco al empezar a recolectar y después emite tras
     * cada commit (conflado: un colector lento solo ve el más reciente). Falla con
     * [StoreCorruptedException] si la siembra no puede leer el archivo.
     */
    val data: Flow<T> = flow {
        mutex.withLock { loadLocked() }
        emitAll(latest.filterNotNull().map { it.value })
    }

    suspend fun read(): T = mutex.withLock { loadLocked() }

    /**
     * Aplica `transform` al valor actual y lo guarda; devuelve el valor nuevo. Si `transform` lanza o
     * la escritura falla, no cambia nada. Si devuelve el mismo objeto, no se escribe.
     */
    suspend fun update(transform: (T) -> T): T = mutex.withLock {
        val current = loadLocked()
        val next = transform(current)
        if (next !== current) {
            // La caché se actualiza en el mismo bloque que el commit: si la corrutina se cancela al
            // volver de withContext, disco y memoria no divergen.
            withContext(Dispatchers.IO) {
                persist(next)
                latest.value = Loaded(next)
            }
        }
        next
    }

    /**
     * Borra el archivo (aunque esté dañado) y deja el almacén en `default`, de nuevo utilizable.
     * `alsoUnderLock` se ejecuta después, con el cerrojo tomado (p. ej. borrar la clave).
     */
    suspend fun reset(alsoUnderLock: () -> Unit = {}) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                deleteFiles()
                orphanChecked = true
                latest.value = Loaded(default)
                alsoUnderLock()
            }
        }
    }

    private suspend fun loadLocked(): T {
        latest.value?.let { return it.value }
        return withContext(Dispatchers.IO) {
            if (!orphanChecked) {
                unavailableOnIo { Files.deleteIfExists(tmp.toPath()) }
                orphanChecked = true
            }
            readFromDisk().also { latest.value = Loaded(it) }
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun readFromDisk(): T {
        if (!file.exists()) return default
        val bytes = try {
            file.readBytes()
        } catch (e: IOException) {
            throw StoreUnavailableException(e)
        }
        if (bytes.isEmpty() || bytes[0] != FORMAT_VERSION) throw StoreCorruptedException(UnknownFormatException())
        val plain = try {
            aead.decrypt(bytes.copyOfRange(1, bytes.size), associatedData)
        } catch (e: GeneralSecurityException) {
            throw StoreCorruptedException(e)
        }
        try {
            return STORE_CBOR.decodeFromByteArray(serializer, plain)
        } catch (_: Exception) {
            throw StoreCorruptedException(UnreadableContentException())
        } finally {
            plain.fill(0)
        }
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun persist(value: T) {
        if (isEmpty(value)) {
            deleteFiles()
            return
        }
        val plain = STORE_CBOR.encodeToByteArray(serializer, value)
        val sealed = try {
            aead.encrypt(plain, associatedData)
        } catch (e: GeneralSecurityException) {
            throw StoreUnavailableException(e)
        } finally {
            plain.fill(0)
        }
        try {
            FileOutputStream(tmp).use { out ->
                out.write(FORMAT_VERSION.toInt())
                out.write(sealed)
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            tmp.delete()
            throw StoreUnavailableException(e)
        }
        syncDirectory()
    }

    private fun deleteFiles() = unavailableOnIo {
        Files.deleteIfExists(tmp.toPath())
        Files.deleteIfExists(file.toPath())
    }

    private inline fun <R> unavailableOnIo(block: () -> R): R = try {
        block()
    } catch (e: IOException) {
        throw StoreUnavailableException(e)
    }

    /** `fsync` del directorio para que el `rename` sobreviva a un corte de luz; si falla, se ignora. */
    private fun syncDirectory() {
        val dir = file.absoluteFile.parentFile ?: return
        try {
            val fd = Os.open(dir.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (_: Exception) {
            // ErrnoException, o "Stub!" en la JVM de pruebas: la atomicidad no depende de esto.
        }
    }

    private companion object {
        const val FORMAT_VERSION: Byte = 1
    }
}
