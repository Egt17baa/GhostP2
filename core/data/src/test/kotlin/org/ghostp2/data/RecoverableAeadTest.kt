package org.ghostp2.data

import com.google.crypto.tink.Aead
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException

class RecoverableAeadTest {
    private val real = TestAeads.newAead()
    private val ad = "ad".toByteArray()

    private class Probe(var failCreate: Boolean, var dataExists: Boolean) {
        var creates = 0
        var resets = 0
    }

    private fun aead(p: Probe, real: Aead) = RecoverableAead(
        dataExists = { p.dataExists },
        resetKey = { p.resets++; p.failCreate = false },
        create = {
            p.creates++
            if (p.failCreate) throw GeneralSecurityException("clave maestra inutilizable") else real
        },
    )

    @Test
    fun createsLazilyOnceAndDelegates() {
        val p = Probe(failCreate = false, dataExists = true)
        val a = aead(p, real)
        assertEquals(0, p.creates)
        val ct = a.encrypt(byteArrayOf(1, 2), ad)
        assertArrayEquals(byteArrayOf(1, 2), a.decrypt(ct, ad))
        assertEquals(1, p.creates)
        assertEquals(0, p.resets)
    }

    @Test
    fun brokenKeyWithExistingDataIsReportedNotReset() {
        val p = Probe(failCreate = true, dataExists = true)
        val a = aead(p, real)
        try {
            a.decrypt(byteArrayOf(1), ad)
            fail()
        } catch (_: GeneralSecurityException) {
        }
        assertEquals(0, p.resets)
        // Tras "empezar de cero" (archivo borrado) la clave rota se descarta y se crea otra.
        p.dataExists = false
        val ct = a.encrypt(byteArrayOf(5), ad)
        assertEquals(1, p.resets)
        assertArrayEquals(byteArrayOf(5), a.decrypt(ct, ad))
    }

    @Test
    fun unexpectedRuntimeFailureBecomesGeneralSecurityException() {
        val a = RecoverableAead(dataExists = { true }, resetKey = {}, create = { throw IllegalStateException("x") })
        try {
            a.encrypt(byteArrayOf(1), ad)
            fail()
        } catch (e: GeneralSecurityException) {
            assertEquals(IllegalStateException::class.java, e.cause!!.javaClass)
        }
    }
}
