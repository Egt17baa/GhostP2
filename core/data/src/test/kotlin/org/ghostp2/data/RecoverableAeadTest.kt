package org.ghostp2.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import java.security.ProviderException
import javax.crypto.AEADBadTagException

class RecoverableAeadTest {
    private val ad = "ad".toByteArray()

    private inline fun <reified E : Throwable> assertThrows(block: () -> Unit): E {
        try {
            block()
        } catch (e: Throwable) {
            if (e is E) return e
            throw AssertionError("se esperaba ${E::class.simpleName}, llegó ${e::class.simpleName}", e)
        }
        fail("se esperaba ${E::class.simpleName}")
        throw IllegalStateException()
    }

    @Test
    fun planKeyOpenTable() {
        assertEquals(KeyPlan.OPEN, planKeyOpen(keysetStored = true, keysetConfirmed = true, dataExists = true))
        assertEquals(KeyPlan.OPEN, planKeyOpen(keysetStored = true, keysetConfirmed = true, dataExists = false))
        assertEquals(KeyPlan.DATA_WITHOUT_KEY, planKeyOpen(keysetStored = true, keysetConfirmed = false, dataExists = true))
        assertEquals(KeyPlan.GENERATE, planKeyOpen(keysetStored = true, keysetConfirmed = false, dataExists = false))
        assertEquals(KeyPlan.DATA_WITHOUT_KEY, planKeyOpen(keysetStored = false, keysetConfirmed = false, dataExists = true))
        assertEquals(KeyPlan.GENERATE, planKeyOpen(keysetStored = false, keysetConfirmed = false, dataExists = false))
    }

    @Test
    fun classifyOpenFailureSeparatesLostKeysFromTransientErrors() {
        assertEquals(OpenFailure.KEY_LOST, classifyOpenFailure(AEADBadTagException(), masterKeyPresent = true))
        assertEquals(
            OpenFailure.KEY_LOST,
            classifyOpenFailure(GeneralSecurityException("x", AEADBadTagException()), masterKeyPresent = null),
        )
        assertEquals(OpenFailure.KEY_LOST, classifyOpenFailure(KeyStoreException("x"), masterKeyPresent = false))
        assertEquals(OpenFailure.KEY_LOST, classifyOpenFailure(IOException("keyset ilegible"), masterKeyPresent = true))
        assertEquals(OpenFailure.TRANSIENT, classifyOpenFailure(KeyStoreException("ocupado"), masterKeyPresent = true))
        assertEquals(OpenFailure.TRANSIENT, classifyOpenFailure(ProviderException("x"), masterKeyPresent = null))
        assertEquals(OpenFailure.TRANSIENT, classifyOpenFailure(GeneralSecurityException("x"), masterKeyPresent = true))
    }

    @Test
    fun freshInstallGeneratesLazilyOnce() {
        val b = FakeKeyBackend()
        val a = RecoverableAead({ false }, b)
        assertEquals(0, b.generates)
        val ct = a.encrypt(byteArrayOf(1, 2), ad)
        assertArrayEquals(byteArrayOf(1, 2), a.decrypt(ct, ad))
        assertEquals(1, b.generates)
        assertEquals(0, b.opens)
    }

    @Test
    fun confirmedKeysetIsOpenedNotRegenerated() {
        val b = FakeKeyBackend(stored = true, confirmed = true)
        val ct = b.key.encrypt(byteArrayOf(7), ad)
        val a = RecoverableAead({ true }, b)
        assertArrayEquals(byteArrayOf(7), a.decrypt(ct, ad))
        assertEquals(1, b.opens)
        assertEquals(0, b.generates)
    }

    @Test
    fun missingKeysetWithDataNeverRegenerates() {
        val b = FakeKeyBackend(stored = false)
        val a = RecoverableAead({ true }, b)
        val e = assertThrows<StoreCorruptedException> { a.decrypt(byteArrayOf(1), ad) }
        assertTrue(e.cause is KeyLostException)
        assertThrows<StoreCorruptedException> { a.encrypt(byteArrayOf(1), ad) }
        assertEquals(0, b.generates)
    }

    @Test
    fun unconfirmedKeysetIsNeverUsed() {
        // Keyset sin marca (p. ej. el keyset en claro que Tink deja si Keystore falla).
        val withData = FakeKeyBackend(stored = true, confirmed = false)
        assertThrows<StoreCorruptedException> { RecoverableAead({ true }, withData).decrypt(byteArrayOf(1), ad) }
        assertEquals(0, withData.opens)
        assertEquals(0, withData.generates)

        val noData = FakeKeyBackend(stored = true, confirmed = false)
        RecoverableAead({ false }, noData).encrypt(byteArrayOf(1), ad)
        assertEquals(0, noData.opens)
        assertEquals(1, noData.generates)
    }

    @Test
    fun transientOpenFailureIsUnavailableAndRetryable() {
        val b = FakeKeyBackend(stored = true, confirmed = true)
        b.openFailure = KeyStoreException("Keystore ocupado")
        val a = RecoverableAead({ true }, b)
        assertThrows<StoreUnavailableException> { a.encrypt(byteArrayOf(1), ad) }
        assertEquals(0, b.generates)
        b.openFailure = null
        a.encrypt(byteArrayOf(1), ad)
        assertEquals(2, b.opens)
        assertEquals(0, b.generates)
    }

    @Test
    fun lostMasterKeyWithDataIsCorruptedWithoutDataIsRegenerated() {
        val b = FakeKeyBackend(stored = true, confirmed = true, masterPresent = false)
        b.openFailure = KeyStoreException("the master key exists but is unusable")
        var data = true
        val a = RecoverableAead({ data }, b)
        assertThrows<StoreCorruptedException> { a.decrypt(byteArrayOf(1), ad) }
        assertEquals(0, b.generates)
        data = false
        val ct = a.encrypt(byteArrayOf(5), ad)
        assertEquals(1, b.generates)
        assertArrayEquals(byteArrayOf(5), a.decrypt(ct, ad))
    }

    @Test
    fun badTagOnKeysetIsKeyLost() {
        val b = FakeKeyBackend(stored = true, confirmed = true, masterPresent = true)
        b.openFailure = GeneralSecurityException("decryption failed", AEADBadTagException())
        assertThrows<StoreCorruptedException> { RecoverableAead({ true }, b).decrypt(byteArrayOf(1), ad) }
    }

    @Test
    fun generateFailureIsUnavailable() {
        val b = FakeKeyBackend()
        b.generateFailure = GeneralSecurityException("Android Keystore no disponible")
        val e = assertThrows<StoreUnavailableException> { RecoverableAead({ false }, b).encrypt(byteArrayOf(1), ad) }
        assertEquals("Almacenamiento no disponible", e.message)
        val r = FakeKeyBackend()
        r.generateFailure = IllegalStateException("x")
        assertThrows<StoreUnavailableException> { RecoverableAead({ false }, r).encrypt(byteArrayOf(1), ad) }
    }
}
