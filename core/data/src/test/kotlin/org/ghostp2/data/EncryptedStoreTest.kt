package org.ghostp2.data

import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

class EncryptedStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Serializable
    data class Note(val text: String = "", val count: Int = 0)

    /** Una versión futura de [Note] con un campo más. */
    @Serializable
    data class NoteV2(val text: String = "", val count: Int = 0, val extra: String = "")

    private val label = "ghostp2-store-v1".toByteArray()
    private val aead = TestAeads.newAead()

    private fun file(): File = File(tmp.root, "ghostp2.bin")

    private fun store(a: Aead = aead, ad: ByteArray = label, f: File = file()) =
        EncryptedStore(f, a, Note.serializer(), Note(), ad)

    private suspend fun assertCorrupted(block: suspend () -> Unit) {
        try {
            block()
            fail("se esperaba StoreCorruptedException")
        } catch (e: StoreCorruptedException) {
            assertEquals("Almacenamiento ilegible", e.message)
        }
    }

    @Test
    fun missingFileReadsDefault() = runBlocking {
        assertEquals(Note(), store().read())
        assertFalse(file().exists())
    }

    @Test
    fun updateRoundTripsAcrossInstances() = runBlocking {
        val s = store()
        val updated = s.update { it.copy(text = "hola", count = 1) }
        assertEquals(Note("hola", 1), updated)
        assertEquals(Note("hola", 1), s.read())
        assertEquals(Note("hola", 1), store().read())
    }

    @Test
    fun fileContentIsNotPlaintext() = runBlocking {
        store().update { it.copy(text = "texto-muy-secreto") }
        val raw = file().readBytes()
        assertFalse(String(raw, Charsets.ISO_8859_1).contains("texto-muy-secreto"))
        assertEquals("primer byte = versión del formato", 1, raw[0].toInt())
    }

    @Test
    fun payloadWithAnUnknownFieldIsStillReadable() = runBlocking {
        // Escrito por una versión más nueva de la app (con un campo que esta no conoce)...
        EncryptedStore(file(), aead, NoteV2.serializer(), NoteV2(), label).update { NoteV2("hola", 2, "nuevo") }
        // ...esta versión lo lee ignorando el campo desconocido, en vez de darlo por ilegible.
        assertEquals(Note("hola", 2), store().read())
    }

    @Test
    fun wrongKeyThrowsStoreCorrupted() = runBlocking {
        store().update { it.copy(text = "a") }
        assertCorrupted { store(a = TestAeads.newAead()).read() }
    }

    @Test
    fun wrongAssociatedDataThrowsStoreCorrupted() = runBlocking {
        store().update { it.copy(text = "a") }
        assertCorrupted { store(ad = "otra-etiqueta".toByteArray()).read() }
    }

    @Test
    fun unknownFormatVersionThrowsStoreCorrupted() = runBlocking {
        store().update { it.copy(text = "a") }
        val raw = file().readBytes()
        raw[0] = 2
        file().writeBytes(raw)
        assertCorrupted { store().read() }
    }

    @Test
    fun tamperedBytesAreReportedAndNeverReplacedByDefault() = runBlocking {
        store().update { it.copy(text = "a") }
        val raw = file().readBytes()
        raw[raw.size - 1] = (raw[raw.size - 1].toInt() xor 1).toByte()
        file().writeBytes(raw)
        val s = store()
        assertCorrupted { s.read() }
        assertCorrupted { s.update { it.copy(count = 9) } }
        assertArrayEquals("el archivo dañado no se toca", raw, file().readBytes())
        s.reset()
        assertFalse(file().exists())
        assertEquals(Note(), s.read())
        s.update { it.copy(count = 2) }
        assertEquals(Note(count = 2), store().read())
    }

    @Test
    fun ioReadFailureIsUnavailableNotCorrupted() = runBlocking {
        // Un directorio en la ruta del archivo: existe pero readBytes() lanza IOException.
        assertTrue(file().mkdir())
        val s = store()
        try {
            s.read()
            fail()
        } catch (e: StoreUnavailableException) {
            assertEquals("Almacenamiento no disponible", e.message)
        }
        assertTrue(file().isDirectory)
        assertTrue(file().delete())
        assertEquals("el fallo no queda en caché: reintentar funciona", Note(), s.read())
    }

    @Test
    fun transientKeyFailureIsUnavailableAndRetryWorks() = runBlocking {
        store().update { it.copy(text = "guardado") }
        val backend = FakeKeyBackend(stored = true, confirmed = true, key = aead)
        backend.openFailure = java.security.KeyStoreException("Keystore ocupado")
        val s = store(a = RecoverableAead({ file().exists() }, backend))
        try {
            s.read()
            fail()
        } catch (_: StoreUnavailableException) {
        }
        assertTrue(file().exists())
        backend.openFailure = null
        assertEquals(Note("guardado"), s.read())
    }

    @Test
    fun emptyOrTruncatedFileThrowsStoreCorrupted() = runBlocking {
        file().writeBytes(ByteArray(0))
        assertCorrupted { store().read() }
        file().writeBytes(byteArrayOf(1, 2, 3))
        assertCorrupted { store().read() }
    }

    @Test
    fun atomicWriteSurvivesCrashMidWrite() = runBlocking {
        store().update { it.copy(text = "bueno") }
        // Simula un corte a mitad de escritura: queda un .tmp huérfano a medio escribir.
        val orphan = File(tmp.root, "ghostp2.bin.tmp")
        orphan.writeBytes(byteArrayOf(1, 9, 9, 9))
        val s = store()
        assertEquals(Note("bueno"), s.read())
        assertFalse("el .tmp huérfano se borra al arrancar", orphan.exists())
        s.update { it.copy(count = 3) }
        assertEquals(Note("bueno", 3), store().read())
        assertFalse(orphan.exists())
    }

    @Test
    fun orphanTmpWithoutTargetReadsDefault() = runBlocking {
        File(tmp.root, "ghostp2.bin.tmp").writeBytes(byteArrayOf(1, 2))
        assertEquals(Note(), store().read())
        assertFalse(File(tmp.root, "ghostp2.bin.tmp").exists())
    }

    @Test
    fun valueEqualToDefaultDeletesFile() = runBlocking {
        val s = store()
        s.update { it.copy(text = "x") }
        assertTrue(file().exists())
        s.update { Note() }
        assertFalse(file().exists())
        assertEquals(Note(), store().read())
    }

    @Test
    fun failingTransformLeavesStoreUnchanged() = runBlocking {
        val s = store()
        s.update { it.copy(text = "antes") }
        val before = file().readBytes()
        try {
            s.update { error("fallo") }
            fail()
        } catch (_: IllegalStateException) {
        }
        assertArrayEquals(before, file().readBytes())
        assertEquals(Note("antes"), s.read())
    }

    @Test
    fun concurrentUpdatesAreSerialized() = runBlocking {
        val s = store()
        (1..50).map { async(Dispatchers.Default) { s.update { it.copy(count = it.count + 1) } } }
            .awaitAll()
        assertEquals(50, s.read().count)
        assertEquals(50, store().read().count)
    }

    @Test
    fun dataFlowEmitsLatestAfterEveryCommit() = runBlocking {
        val s = store()
        s.update { it.copy(count = 1) }
        val seen = CopyOnWriteArrayList<Int>()
        val job = launch(Dispatchers.Default) { s.data.collect { seen += it.count } }
        waitUntil { seen.isNotEmpty() }
        (2..20).forEach { n -> s.update { it.copy(count = n) } }
        waitUntil { seen.lastOrNull() == 20 }
        job.cancel()
        assertEquals(1, seen.first())
        assertEquals("nunca una instantánea vieja tras una nueva", seen.sorted(), seen.toList())
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(5)
        }
    }
}
