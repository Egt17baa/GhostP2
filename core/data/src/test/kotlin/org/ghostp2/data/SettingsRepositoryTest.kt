package org.ghostp2.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.engine.Identity
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

class SettingsRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val aead = TestAeads.newAead()

    private fun file(): File = File(tmp.root, "ghostp2.bin")

    private fun store() = ghostStore(file(), aead)

    private fun identity() = Identity(ByteArray(32) { 1 }, ByteArray(32) { 2 }, null, null)

    @Test
    fun settingsRoundTrip() = runBlocking {
        assertEquals(AppSettings(), StoredSettingsRepository(store()).get())
        val changed = AppSettings(
            onboardingDone = true,
            lockEnabled = true,
            lockAfterMinutes = 15,
            hideNotificationNames = true,
            maxPhotoSidePx = 3072,
            startOnBoot = true,
        )
        StoredSettingsRepository(store()).update { changed }
        assertEquals(changed, StoredSettingsRepository(store()).get())
        StoredSettingsRepository(store()).update { it.copy(lockAfterMinutes = 0, maxPhotoSidePx = 1280) }
        assertEquals(changed.copy(lockAfterMinutes = 0, maxPhotoSidePx = 1280), StoredSettingsRepository(store()).get())
    }

    @Test
    fun outOfRangeValuesAreSanitized() = runBlocking {
        assertEquals(AppSettings(lockAfterMinutes = 1, maxPhotoSidePx = 2048), AppSettings(lockAfterMinutes = 7, maxPhotoSidePx = 999).sanitized())
        val repo = StoredSettingsRepository(store())
        repo.update { it.copy(lockEnabled = true, lockAfterMinutes = 7, maxPhotoSidePx = 999) }
        assertEquals(AppSettings(lockEnabled = true), repo.get())

        // Un valor fuera de rango ya guardado (p. ej. por otra versión) también se sanea al leer.
        store().update { it.withSettings(StoredSettings(lockEnabled = true, lockAfterMinutes = 7, maxPhotoSidePx = 999)) }
        val fresh = StoredSettingsRepository(store())
        assertEquals(AppSettings(lockEnabled = true), fresh.get())
        assertEquals(1, fresh.get().lockAfterMinutes)
        assertEquals(2048, fresh.get().maxPhotoSidePx)
        assertEquals(1, StoredSettingsRepository(store()).settings.first().lockAfterMinutes)
    }

    @Test
    fun nonDefaultSettingsKeepTheFile() = runBlocking {
        val s = store()
        StoredIdentityRepository(s).save(identity())
        StoredSettingsRepository(s).update { it.copy(startOnBoot = true) }
        StoredIdentityRepository(s).wipe()
        StoredContactRepository(s).wipe()
        assertTrue("sin identidad ni contactos, con ajustes, el archivo sigue", file().exists())
        assertEquals(AppSettings(startOnBoot = true), StoredSettingsRepository(store()).get())
    }

    @Test
    fun defaultSettingsAndNoDataDeleteTheFile() = runBlocking {
        val s = store()
        val repo = StoredSettingsRepository(s)
        repo.update { it.copy(onboardingDone = true) }
        assertTrue(file().exists())
        repo.update { it.copy(onboardingDone = false) }
        assertFalse("ajustes por defecto y sin datos: no queda archivo", file().exists())

        repo.update { it.copy(hideNotificationNames = true) }
        assertTrue(file().exists())
        repo.wipe()
        assertFalse(file().exists())
        assertEquals(AppSettings(), StoredSettingsRepository(store()).get())
    }

    @Test
    fun settingsWipeKeepsOtherDataAndResetsCorruptedStore() = runBlocking {
        val s = store()
        StoredIdentityRepository(s).save(identity())
        StoredSettingsRepository(s).update { it.copy(lockEnabled = true) }
        StoredSettingsRepository(s).wipe()
        assertEquals(identity(), StoredIdentityRepository(store()).load())
        assertEquals(AppSettings(), StoredSettingsRepository(store()).get())

        val raw = file().readBytes()
        raw[raw.size / 2] = (raw[raw.size / 2].toInt() xor 0x40).toByte()
        file().writeBytes(raw)
        val broken = StoredSettingsRepository(store())
        try {
            broken.get()
            fail("se esperaba StoreCorruptedException")
        } catch (_: StoreCorruptedException) {
        }
        broken.wipe()
        assertFalse(file().exists())
        assertEquals(AppSettings(), broken.get())
    }

    @Test
    fun rawOutOfRangeSettingsKeepTheFile() = runBlocking {
        // Ajustes guardados fuera de rango (p. ej. por otra versión) y todo lo demás por defecto.
        store().update { it.withSettings(StoredSettings(lockAfterMinutes = 7)) }
        assertTrue(file().exists())
        val s = store()
        val journal = StoredDeletionJournal(s)
        journal.add("a")
        journal.remove("a")
        assertTrue("una escritura con ajustes guardados no por defecto conserva el archivo", file().exists())
        assertEquals(AppSettings(), StoredSettingsRepository(store()).get())
    }

    @Test
    fun corruptedFileFailsClosedAndIsNotTouched() = runBlocking {
        StoredDeletionJournal(store()).add("a")
        val raw = file().readBytes()
        raw[raw.size / 2] = (raw[raw.size / 2].toInt() xor 0x40).toByte()
        file().writeBytes(raw)
        val s = store()
        assertCorrupted { StoredDeletionJournal(s).pending() }
        assertCorrupted { StoredDeletionJournal(s).add("b") }
        assertCorrupted { StoredDeletionJournal(s).remove("a") }
        assertCorrupted { StoredSettingsRepository(s).settings.first() }
        assertCorrupted { StoredSettingsRepository(s).get() }
        assertCorrupted { StoredSettingsRepository(s).update { it.copy(lockEnabled = true) } }
        assertArrayEquals("el archivo dañado no se toca", raw, file().readBytes())
    }

    private suspend fun assertCorrupted(block: suspend () -> Unit) {
        try {
            block()
            fail("se esperaba StoreCorruptedException")
        } catch (_: StoreCorruptedException) {
        }
    }

    @Test
    fun pendingDeletionsRoundTrip() = runBlocking {
        val s = store()
        val journal = StoredDeletionJournal(s)
        assertEquals(emptySet<String>(), journal.pending())
        journal.add("a")
        journal.add("b")
        journal.add("a")
        assertEquals(setOf("a", "b"), StoredDeletionJournal(store()).pending())
        assertEquals(listOf("a", "b"), store().read().pendingDeletions)
        journal.remove("a")
        journal.remove("x")
        assertEquals(setOf("b"), StoredDeletionJournal(store()).pending())
        assertTrue("un borrado pendiente mantiene el archivo", file().exists())
        journal.remove("b")
        assertFalse("sin nada pendiente ni otros datos, no queda archivo", file().exists())
    }

    @Test
    fun settingsFlowEmitsUpdates() = runBlocking {
        val s = store()
        val repo = StoredSettingsRepository(s)
        val seen = CopyOnWriteArrayList<AppSettings>()
        val job = launch(Dispatchers.Default) { repo.settings.collect { seen += it } }
        waitUntil { seen.isNotEmpty() }
        assertEquals(AppSettings(), seen.first())
        repo.update { it.copy(lockEnabled = true) }
        waitUntil { seen.last() == AppSettings(lockEnabled = true) }
        StoredIdentityRepository(s).save(identity())
        repo.update { it.copy(lockAfterMinutes = 5) }
        waitUntil { seen.last() == AppSettings(lockEnabled = true, lockAfterMinutes = 5) }
        repo.wipe()
        waitUntil { seen.last() == AppSettings() }
        job.cancel()
        assertFalse("una escritura de otros datos no reemite ajustes iguales", seen.zipWithNext().any { (a, b) -> a == b })
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(5)
        }
    }
}
