package org.ghostp2.app.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

/** El archivo del plazo armado de R29 (`noBackupFilesDir/auto-disconnect`). */
class ArmedFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val file by lazy { File(folder.root, ArmedFile.NAME) }

    private val armedFile by lazy { ArmedFile(file) }

    /** Lo que escribe [ArmedFile.save]: versión, arranque, plazo y minutos (20 bytes, big-endian). */
    private fun bytes(version: Int = 1, boot: Int = 7, deadline: Long = 123_456_789L, minutes: Int = 2): ByteArray =
        ByteBuffer.allocate(20).putInt(version).putInt(boot).putLong(deadline).putInt(minutes).array()

    @Test
    fun missingFileIsNoDeadline() {
        assertNull(armedFile.load())
    }

    @Test
    fun roundTrip() {
        val armed = Armed(boot = 7, deadline = 123_456_789L, minutes = 15)
        armedFile.save(armed)
        assertEquals(armed, armedFile.load())
        assertEquals(20L, file.length())
        assertFalse(File(folder.root, ArmedFile.NAME + ".tmp").exists())
        // Guardar otra vez sustituye el anterior.
        val again = Armed(boot = 8, deadline = Long.MAX_VALUE, minutes = 2)
        armedFile.save(again)
        assertEquals(again, armedFile.load())
    }

    @Test
    fun knownLayoutIsRead() {
        file.writeBytes(bytes())
        assertEquals(Armed(boot = 7, deadline = 123_456_789L, minutes = 2), armedFile.load())
    }

    @Test
    fun wrongSizeIsNoDeadline() {
        val good = bytes()
        listOf(ByteArray(0), good.copyOf(19), good.copyOf(21), good + good).forEach {
            file.writeBytes(it)
            assertNull("${it.size} bytes", armedFile.load())
        }
    }

    @Test
    fun wrongVersionIsNoDeadline() {
        listOf(0, 2, -1).forEach {
            file.writeBytes(bytes(version = it))
            assertNull("versión $it", armedFile.load())
        }
    }

    @Test
    fun corruptedBytesAreNoDeadline() {
        file.writeBytes(ByteArray(20) { 0xA5.toByte() })
        assertNull(armedFile.load())
        // Con la versión estropeada aunque el resto esté bien.
        file.writeBytes(bytes().also { it[3] = (it[3] + 1).toByte() })
        assertNull(armedFile.load())
    }

    @Test
    fun clearDeletesAndToleratesMissingFile() {
        armedFile.save(Armed(boot = 7, deadline = 1L, minutes = 2))
        armedFile.clear()
        assertFalse(file.exists())
        assertNull(armedFile.load())
        armedFile.clear()
        assertFalse(file.exists())
    }
}
