package org.ghostp2.app.lock

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException

/**
 * [ArmedStore] en un archivo pequeño de `noBackupFilesDir` (R29): solo el número de arranque, el plazo
 * en `elapsedRealtime` y los minutos, sin secretos ni contenido. Escritura atómica (archivo temporal y
 * renombrado); un archivo con otra versión o de otro tamaño se lee como "sin plazo".
 */
class ArmedFile(private val file: File) : ArmedStore {
    override fun load(): Armed? {
        if (!file.exists()) return null
        val bytes = file.readBytes()
        if (bytes.size != SIZE) return null
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != VERSION) return null
            return Armed(boot = input.readInt(), deadline = input.readLong(), minutes = input.readInt())
        }
    }

    override fun save(armed: Armed) {
        val bytes = ByteArrayOutputStream(SIZE).also { out ->
            DataOutputStream(out).use {
                it.writeInt(VERSION)
                it.writeInt(armed.boot)
                it.writeLong(armed.deadline)
                it.writeInt(armed.minutes)
            }
        }.toByteArray()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("rename")
        }
    }

    override fun clear() {
        if (file.exists() && !file.delete()) throw IOException("delete")
    }

    companion object {
        /** Nombre del archivo dentro de `noBackupFilesDir`. */
        const val NAME = "auto-disconnect"

        private const val VERSION = 1
        private const val SIZE = 4 + 4 + 8 + 4
    }
}
