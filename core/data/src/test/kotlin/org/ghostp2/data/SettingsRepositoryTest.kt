package org.ghostp2.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor
import org.ghostp2.engine.Identity
import org.ghostp2.engine.bridges.BridgeMode
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
    fun disconnectMinutesDefaultsToNeverAndSanitizes() = runBlocking {
        assertEquals(0, AppSettings().disconnectAfterMinutes)
        assertEquals(listOf(0, 2, 5, 15), AppSettings.DISCONNECT_MINUTES)
        assertEquals(AppSettings(), AppSettings(disconnectAfterMinutes = 7).sanitized())
        val repo = StoredSettingsRepository(store())
        repo.update { it.copy(disconnectAfterMinutes = 5) }
        assertEquals(5, StoredSettingsRepository(store()).get().disconnectAfterMinutes)
        repo.update { it.copy(disconnectAfterMinutes = 7) }
        assertEquals(0, StoredSettingsRepository(store()).get().disconnectAfterMinutes)

        // Un valor fuera de la lista ya guardado (p. ej. por otra versión) también se sanea al leer.
        store().update { it.withSettings(StoredSettings(lockEnabled = true, disconnectAfterMinutes = 3)) }
        assertEquals(AppSettings(lockEnabled = true), StoredSettingsRepository(store()).get())
    }

    /** Ajustes del formato 2 tal como se guardaban antes de «Desconectar al salir» (R29). */
    @Serializable
    private class StoredSettingsBeforeR29(
        val onboardingDone: Boolean = false,
        val lockEnabled: Boolean = false,
        val lockAfterMinutes: Int = 1,
        val hideNotificationNames: Boolean = false,
        val maxPhotoSidePx: Int = 2048,
        val startOnBoot: Boolean = false,
    )

    @Serializable
    private class StoredDataBeforeR29(
        val identity: StoredIdentity? = null,
        val settings: StoredSettingsBeforeR29? = null,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun format2FileWithoutDisconnectFieldReadsNever() = runBlocking {
        val old = StoredDataBeforeR29(
            StoredIdentity.from(identity()),
            StoredSettingsBeforeR29(onboardingDone = true, lockEnabled = true, lockAfterMinutes = 15, startOnBoot = true),
        )
        val plain = Cbor.encodeToByteArray(StoredDataBeforeR29.serializer(), old)
        file().writeBytes(byteArrayOf(2) + aead.encrypt(plain, byteArrayOf(2) + "ghostp2-store-v1".toByteArray()))

        val read = StoredSettingsRepository(store()).get()
        assertEquals(AppSettings(onboardingDone = true, lockEnabled = true, lockAfterMinutes = 15, startOnBoot = true), read)
        assertEquals(0, read.disconnectAfterMinutes)
        assertEquals(identity(), StoredIdentityRepository(store()).load())
    }

    private val obfs4Line = "obfs4 37.218.245.14:38224 D9A82D2F9C2F65A18407B1D2B764F130847F8B5D " +
        "cert=bjRaMrr1BRiAW8IE9U5z27fQaYgOhX1UCmOpg2pFpoMvo6ZgQMzLsaTzzQNTlm7hNcb+Sg iat-mode=0"
    private val webtunnelLine = "webtunnel [2001:db8::5]:443 4F1C6A0D1B2E3F405162738495A6B7C8D9E0F1A2 " +
        "url=https://cover.example.org/aB3dE5 ver=0.0.1"

    @Test
    fun bridgeDefaultsToNone() = runBlocking {
        assertEquals(BridgeMode.NONE, AppSettings().bridgeMode)
        assertEquals(emptyList<String>(), AppSettings().customBridges)
        assertEquals(BridgeMode.NONE, StoredSettingsRepository(store()).get().bridgeMode)
        assertEquals(BridgeMode.NONE.name, StoredSettings().bridgeMode)
        assertEquals(emptyList<String>(), StoredSettings().customBridges)
    }

    /** Ajustes del formato 2 tal como se guardaban antes de los puentes (R30), ya con R29. */
    @Serializable
    private class StoredSettingsBeforeR30(
        val onboardingDone: Boolean = false,
        val lockEnabled: Boolean = false,
        val lockAfterMinutes: Int = 1,
        val hideNotificationNames: Boolean = false,
        val maxPhotoSidePx: Int = 2048,
        val startOnBoot: Boolean = false,
        val disconnectAfterMinutes: Int = 0,
    )

    @Serializable
    private class StoredDataBeforeR30(
        val identity: StoredIdentity? = null,
        val settings: StoredSettingsBeforeR30? = null,
    )

    @OptIn(ExperimentalSerializationApi::class)
    @Test
    fun format2FileWithoutBridgeFieldsReadsNone() = runBlocking {
        val old = StoredDataBeforeR30(
            StoredIdentity.from(identity()),
            StoredSettingsBeforeR30(onboardingDone = true, lockEnabled = true, disconnectAfterMinutes = 5),
        )
        val plain = Cbor.encodeToByteArray(StoredDataBeforeR30.serializer(), old)
        file().writeBytes(byteArrayOf(2) + aead.encrypt(plain, byteArrayOf(2) + "ghostp2-store-v1".toByteArray()))

        val read = StoredSettingsRepository(store()).get()
        assertEquals(AppSettings(onboardingDone = true, lockEnabled = true, disconnectAfterMinutes = 5), read)
        assertEquals(BridgeMode.NONE, read.bridgeMode)
        assertEquals(emptyList<String>(), read.customBridges)
        assertEquals(identity(), StoredIdentityRepository(store()).load())
    }

    @Test
    fun unknownModeReadsNone() = runBlocking {
        store().update { it.withSettings(StoredSettings(lockEnabled = true, bridgeMode = "MEEK")) }
        assertEquals(AppSettings(lockEnabled = true), StoredSettingsRepository(store()).get())
        store().update { it.withSettings(StoredSettings(bridgeMode = "obfs4")) }
        assertEquals(BridgeMode.NONE, StoredSettingsRepository(store()).get().bridgeMode)

        // Con un modo desconocido las líneas guardadas se conservan, como con cualquier otro modo que no sea CUSTOM.
        store().update { it.withSettings(StoredSettings(bridgeMode = "WEBTUNNEL", customBridges = listOf(obfs4Line))) }
        assertEquals(AppSettings(customBridges = listOf(obfs4Line)), StoredSettingsRepository(store()).get())
    }

    @Test
    fun customWithoutLinesSanitizesToNone() = runBlocking {
        assertEquals(AppSettings(), AppSettings(bridgeMode = BridgeMode.CUSTOM).sanitized())
        assertEquals(
            AppSettings(),
            AppSettings(bridgeMode = BridgeMode.CUSTOM, customBridges = listOf(obfs4Line, "obfs4 roto")).sanitized(),
        )
        val tooMany = (1..11).map { obfs4Line.replace(":38224", ":${1000 + it}") }
        assertEquals(AppSettings(), AppSettings(bridgeMode = BridgeMode.CUSTOM, customBridges = tooMany).sanitized())
        assertEquals(AppSettings(), AppSettings(bridgeMode = BridgeMode.CUSTOM, customBridges = listOf(" ")).sanitized())

        val repo = StoredSettingsRepository(store())
        repo.update { it.copy(lockEnabled = true, bridgeMode = BridgeMode.CUSTOM) }
        assertEquals(AppSettings(lockEnabled = true), StoredSettingsRepository(store()).get())

        // Líneas guardadas que ya no validan (p. ej. por otra versión) se sanean al leer.
        store().update {
            it.withSettings(StoredSettings(lockEnabled = true, bridgeMode = "CUSTOM", customBridges = listOf("meek_lite 1.2.3.4:80")))
        }
        val fresh = StoredSettingsRepository(store())
        assertEquals(AppSettings(lockEnabled = true), fresh.get())
        assertEquals(AppSettings(lockEnabled = true), fresh.settings.first())

        // Con otro modo, las líneas guardadas se conservan aunque no validen.
        val kept = AppSettings(bridgeMode = BridgeMode.OBFS4, customBridges = listOf("obfs4 roto"))
        assertEquals(kept, kept.sanitized())
    }

    @Test
    fun customLinesRoundTrip() = runBlocking {
        val custom = AppSettings(bridgeMode = BridgeMode.CUSTOM, customBridges = listOf(obfs4Line, webtunnelLine))
        StoredSettingsRepository(store()).update { custom }
        assertEquals(custom, StoredSettingsRepository(store()).get())

        // Cambiar de opción no pierde las líneas propias.
        StoredSettingsRepository(store()).update { it.copy(bridgeMode = BridgeMode.OBFS4) }
        assertEquals(custom.copy(bridgeMode = BridgeMode.OBFS4), StoredSettingsRepository(store()).get())
        StoredSettingsRepository(store()).update { it.copy(bridgeMode = BridgeMode.SNOWFLAKE) }
        assertEquals(custom.copy(bridgeMode = BridgeMode.SNOWFLAKE), StoredSettingsRepository(store()).get())
        StoredSettingsRepository(store()).update { it.copy(bridgeMode = BridgeMode.NONE) }
        assertEquals(custom.copy(bridgeMode = BridgeMode.NONE), StoredSettingsRepository(store()).get())
        assertTrue("las líneas propias guardadas mantienen el archivo", file().exists())
        StoredSettingsRepository(store()).update { it.copy(bridgeMode = BridgeMode.CUSTOM) }
        assertEquals(custom, StoredSettingsRepository(store()).get())

        StoredSettingsRepository(store()).wipe()
        assertEquals(AppSettings(), StoredSettingsRepository(store()).get())
        assertFalse(file().exists())
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
