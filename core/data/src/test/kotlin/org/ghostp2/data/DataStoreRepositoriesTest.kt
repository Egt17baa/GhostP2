package org.ghostp2.data

import android.content.Context
import com.google.crypto.tink.Aead
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.engine.Contact
import org.ghostp2.engine.ContactRepository
import org.ghostp2.engine.ContactStatus
import org.ghostp2.engine.Identity
import org.ghostp2.engine.IdentityRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
class DataStoreRepositoriesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val aead = TestAeads.newAead()

    private fun file(): File = File(tmp.root, "ghostp2.bin")

    private fun repos(a: Aead = aead): Pair<IdentityRepository, ContactRepository> {
        val store = ghostStore(file(), a)
        return StoredIdentityRepository(store) to StoredContactRepository(store)
    }

    private fun bytes(seed: Int, size: Int = 32) = ByteArray(size) { (seed + it).toByte() }

    private fun identity() = Identity(bytes(1), bytes(2), bytes(3, 64), "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyz56")

    private fun contact(n: Int, name: String = "Contacto$n") = Contact(
        id = "id$n",
        name = name,
        peerIdentity = bytes(10 + n),
        peerOnion = "peeronion${n}xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx",
        clientAuthPrivateForPeerOnion = bytes(20 + n),
        myClientAuthPublic = bytes(30 + n),
        pairSecret = bytes(40 + n),
        verified = n % 2 == 0,
        status = if (n == 3) ContactStatus.IDENTITY_CHANGED else ContactStatus.ACTIVE,
    )

    private fun ByteArray.zero() = fill(0)

    private suspend fun assertCorrupted(block: suspend () -> Unit) {
        try {
            block()
            fail("se esperaba StoreCorruptedException")
        } catch (_: StoreCorruptedException) {
        }
    }

    private fun corruptFile() {
        val raw = file().readBytes()
        raw[raw.size / 2] = (raw[raw.size / 2].toInt() xor 0x40).toByte()
        file().writeBytes(raw)
    }

    @Test
    fun roundTripIdentityAndContacts() = runBlocking {
        val (ids, contacts) = repos()
        assertNull(ids.load())
        assertEquals(emptyList<Contact>(), contacts.all())
        ids.save(identity())
        contacts.upsert(contact(1))
        contacts.upsert(contact(2))
        contacts.upsert(contact(3))

        val (ids2, contacts2) = repos()
        assertEquals(identity(), ids2.load())
        assertEquals(listOf(contact(1), contact(2), contact(3)), contacts2.all())
        assertEquals(listOf(contact(1), contact(2), contact(3)), contacts2.contacts.first())
        val noOnion = identity().copy(onionPrivateKey = null, onion = null)
        ids2.save(noOnion)
        assertEquals(noOnion, repos().first.load())
    }

    @Test
    fun upsertReplacesByIdKeepingOrderAndDeleteRemoves() = runBlocking {
        val (_, contacts) = repos()
        contacts.upsert(contact(1))
        contacts.upsert(contact(2))
        val renamed = contact(1, name = "Nuevo")
        contacts.upsert(renamed)
        assertEquals(listOf(renamed, contact(2)), contacts.all())
        contacts.delete("id1")
        contacts.delete("no-existe")
        assertEquals(listOf(contact(2)), repos().second.all())
    }

    @Test
    fun identityAndContactsShareOneFile() = runBlocking {
        val (ids, contacts) = repos()
        ids.save(identity())
        contacts.upsert(contact(1))
        ids.save(identity().copy(onion = null, onionPrivateKey = null))
        contacts.upsert(contact(2))
        assertEquals(listOf("ghostp2.bin"), tmp.root.list()!!.toList())
        val (ids2, contacts2) = repos()
        assertEquals(identity().copy(onion = null, onionPrivateKey = null), ids2.load())
        assertEquals(listOf(contact(1), contact(2)), contacts2.all())
    }

    @Test
    fun fileContentIsNotPlaintext() = runBlocking {
        val (ids, contacts) = repos()
        ids.save(identity())
        contacts.upsert(contact(1, name = "AliciaSecreta"))
        val raw = String(file().readBytes(), Charsets.ISO_8859_1)
        assertFalse(raw.contains(identity().onion!!))
        assertFalse(raw.contains("AliciaSecreta"))
        assertFalse(raw.contains(contact(1).peerOnion))
        assertFalse(raw.contains("peeronion"))
    }

    @Test
    fun wrongKeyThrowsStoreCorrupted() = runBlocking {
        val (ids, contacts) = repos()
        ids.save(identity())
        contacts.upsert(contact(1))
        val (ids2, contacts2) = repos(TestAeads.newAead())
        assertCorrupted { ids2.load() }
        assertCorrupted { contacts2.all() }
        assertCorrupted { contacts2.contacts.first() }
    }

    @Test
    fun corruptedStoreReportsAndAllowsReset() = runBlocking {
        val (ids, contacts) = repos()
        ids.save(identity())
        contacts.upsert(contact(1))
        corruptFile()

        val (ids2, contacts2) = repos()
        assertCorrupted { ids2.load() }
        assertCorrupted { contacts2.all() }
        assertCorrupted { ids2.save(identity()) }
        assertCorrupted { contacts2.upsert(contact(2)) }
        assertTrue("la corrupción nunca se sustituye en silencio", file().exists())

        // "Empezar de cero": el mismo orden que MessengerEngine.wipeAll (contactos, después identidad).
        contacts2.wipe()
        ids2.wipe()
        assertFalse(file().exists())
        assertNull(ids2.load())
        assertEquals(emptyList<Contact>(), contacts2.all())
        ids2.save(identity())
        contacts2.upsert(contact(2))
        val (ids3, contacts3) = repos()
        assertEquals(identity(), ids3.load())
        assertEquals(listOf(contact(2)), contacts3.all())
    }

    @Test
    fun identityWipeAloneResetsCorruptedStoreAndContactsFlow() = runBlocking {
        val (_, contacts) = repos()
        contacts.upsert(contact(1))
        corruptFile()
        val (ids2, contacts2) = repos()
        assertCorrupted { contacts2.all() }
        ids2.wipe()
        assertFalse(file().exists())
        assertEquals(emptyList<Contact>(), contacts2.all())
        assertEquals(emptyList<Contact>(), contacts2.contacts.first())
    }

    @Test
    fun wipeSemanticsInSharedFile() = runBlocking {
        val (ids, contacts) = repos()
        ids.save(identity())
        contacts.upsert(contact(1))

        contacts.wipe()
        assertEquals(identity(), repos().first.load())
        assertEquals(emptyList<Contact>(), repos().second.all())
        assertTrue(file().exists())

        contacts.upsert(contact(2))
        ids.wipe()
        assertNull(repos().first.load())
        assertEquals(listOf(contact(2)), repos().second.all())

        contacts.wipe()
        assertFalse("sin identidad ni contactos no queda archivo", file().exists())
        ids.wipe()
        contacts.wipe()
        assertFalse(file().exists())
    }

    @Test
    fun atomicWriteSurvivesCrashMidWrite() = runBlocking {
        val (ids, contacts) = repos()
        ids.save(identity())
        contacts.upsert(contact(1))
        val orphan = File(tmp.root, "ghostp2.bin.tmp")
        orphan.writeBytes(ByteArray(100) { 7 })

        val (ids2, contacts2) = repos()
        assertEquals(identity(), ids2.load())
        assertEquals(listOf(contact(1)), contacts2.all())
        assertFalse(orphan.exists())
        contacts2.upsert(contact(2))
        assertEquals(listOf(contact(1), contact(2)), repos().second.all())
    }

    @Test
    fun returnedAndSavedArraysAreNeverAliased() = runBlocking {
        val (ids, contacts) = repos()
        val saved = identity()
        ids.save(saved)
        saved.identityPrivate.zero()
        saved.onionPrivateKey!!.zero()
        val loaded = ids.load()!!
        assertEquals(identity(), loaded)
        loaded.identityPrivate.zero()
        loaded.identityPublic.zero()
        loaded.onionPrivateKey!!.zero()
        assertEquals(identity(), ids.load())
        assertEquals(identity(), repos().first.load())

        val c = contact(1)
        contacts.upsert(c)
        c.pairSecret.zero()
        c.clientAuthPrivateForPeerOnion.zero()
        c.peerIdentity.zero()
        val all = contacts.all()
        assertEquals(listOf(contact(1)), all)
        all.single().pairSecret.zero()
        all.single().myClientAuthPublic.zero()
        assertEquals(listOf(contact(1)), contacts.all())

        val emitted = contacts.contacts.first()
        emitted.single().pairSecret.zero()
        emitted.single().clientAuthPrivateForPeerOnion.zero()
        assertEquals(listOf(contact(1)), contacts.contacts.first())
        assertEquals(listOf(contact(1)), contacts.all())
        assertEquals(listOf(contact(1)), repos().second.all())
    }

    @Test
    fun contactsFlowEmitsUpdates() = runBlocking {
        val (ids, contacts) = repos()
        contacts.upsert(contact(1))
        val seen = CopyOnWriteArrayList<List<String>>()
        val job = launch(Dispatchers.Default) { contacts.contacts.collect { list -> seen += list.map { it.name } } }
        waitUntil { seen.isNotEmpty() }
        assertEquals(listOf("Contacto1"), seen.first())

        contacts.upsert(contact(2))
        waitUntil { seen.last() == listOf("Contacto1", "Contacto2") }
        contacts.upsert(contact(1, name = "Otro"))
        waitUntil { seen.last() == listOf("Otro", "Contacto2") }
        ids.save(identity())
        contacts.delete("id1")
        waitUntil { seen.last() == listOf("Contacto2") }
        contacts.wipe()
        waitUntil { seen.last().isEmpty() }
        job.cancel()
        assertFalse("una escritura de identidad no reemite contactos iguales", seen.zipWithNext().any { (a, b) -> a == b })
    }

    @Test
    fun contactsFlowNeverEmitsStaleSnapshotUnderConcurrentWrites() = runBlocking {
        val (_, contacts) = repos()
        val seen = CopyOnWriteArrayList<Int>()
        val job = launch(Dispatchers.Default) { contacts.contacts.collect { seen += it.size } }
        waitUntil { seen.isNotEmpty() }
        (1..40).map { n -> async(Dispatchers.Default) { contacts.upsert(contact(n)) } }.awaitAll()
        waitUntil { seen.last() == 40 }
        job.cancel()
        assertEquals(40, contacts.all().size)
        assertEquals("los tamaños solo crecen: ninguna instantánea vieja tras una nueva", seen.sorted(), seen.toList())

        // Un colector que llega después recibe directamente el estado más reciente.
        assertEquals(40, contacts.contacts.first().size)
    }

    @Test
    fun createRepositoriesStoresInNoBackupFilesDir() = runBlocking {
        val context: Context = RuntimeEnvironment.getApplication()
        val (ids, contacts) = createRepositories(context, aead)
        ids.save(identity())
        contacts.upsert(contact(1))
        val target = File(context.noBackupFilesDir, "ghostp2.bin")
        assertTrue(target.exists())
        assertEquals(identity(), createRepositories(context, aead).first.load())
    }

    private suspend fun waitUntil(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(5)
        }
    }
}
