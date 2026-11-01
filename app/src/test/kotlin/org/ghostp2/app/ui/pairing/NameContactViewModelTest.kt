package org.ghostp2.app.ui.pairing

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.runBlocking
import org.ghostp2.app.R
import org.ghostp2.app.TestGhostApp
import org.ghostp2.app.eventually
import org.ghostp2.app.pairWithPeer
import org.ghostp2.engine.Contact
import org.ghostp2.engine.ContactStatus
import org.ghostp2.engine.EngineHarness
import org.ghostp2.engine.MAX_CONTACT_NAME_CHARS
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class NameContactViewModelTest {
    private val app = RuntimeEnvironment.getApplication() as TestGhostApp
    private val store = ViewModelStore()

    @After
    fun tearDown() {
        store.clear()
        runBlocking { app.container.engine.stop() }
    }

    private fun viewModel(id: String): NameContactViewModel =
        ViewModelProvider.create(store, viewModelFactory { initializer { NameContactViewModel(app.container, id) } })[NameContactViewModel::class]

    private fun stored(id: String) = runBlocking { app.storage.contacts.all() }.single { it.id == id }

    @Test
    fun saveRenamesAndVerifies() {
        assertTrue(runBlocking { app.container.start().await() })
        EngineHarness(1, network = app.network).use { peer ->
            val (id, _) = runBlocking {
                peer.startAll()
                pairWithPeer(app, peer, TIMEOUT)
            }
            val vm = viewModel(id)
            eventually(TIMEOUT) { vm.safetyNumber.value != null }
            assertEquals(runBlocking { app.container.engine.safetyNumber(id) }, vm.safetyNumber.value)
            assertTrue(Regex("""\d{5}( \d{5}){5}""").matches(vm.safetyNumber.value!!))
            assertFalse(vm.saved.value)

            assertTrue(vm.save("  Ana  ", verified = true))
            eventually(TIMEOUT) { vm.saved.value }
            assertEquals("Ana", stored(id).name)
            assertTrue(stored(id).verified)
            assertNull(vm.error.value)
        }
    }

    @Test
    fun blankOrTooLongNameIsRejected() {
        runBlocking { app.storage.contacts.upsert(contact("c1")) }
        val vm = viewModel("c1")

        assertFalse(vm.save(" \n ", verified = true))
        assertEquals(R.string.name_error_blank, vm.error.value)
        assertFalse(vm.save("x".repeat(MAX_CONTACT_NAME_CHARS + 1), verified = true))
        assertEquals(R.string.name_error_too_long, vm.error.value)

        // Se rechaza sin llegar a guardar: sigue siendo "Contacto nuevo" sin verificar.
        assertEquals("Contacto nuevo", stored("c1").name)
        assertFalse(stored("c1").verified)
        assertFalse(vm.saved.value)

        // Justo en el límite (contado en caracteres, no en unidades UTF-16) sí vale.
        val limit = "😀".repeat(MAX_CONTACT_NAME_CHARS)
        assertTrue(vm.save(limit, verified = false))
        eventually(TIMEOUT) { vm.saved.value }
        assertEquals(limit, stored("c1").name)
        assertNull(vm.error.value)
    }

    @Test
    fun saveFailureShowsError() {
        // Contacto que no existe: el motor lanza al renombrar; no se navega.
        val vm = viewModel("no-existe")
        assertTrue(vm.save("Ana", verified = false))
        eventually(TIMEOUT) { vm.error.value == R.string.name_error_save }
        assertFalse(vm.saved.value)
    }

    private companion object {
        const val TIMEOUT = 20_000L

        fun contact(id: String) =
            Contact(id, "Contacto nuevo", ByteArray(32), "x".repeat(56), ByteArray(32), ByteArray(32), ByteArray(32), false, ContactStatus.ACTIVE)
    }
}
