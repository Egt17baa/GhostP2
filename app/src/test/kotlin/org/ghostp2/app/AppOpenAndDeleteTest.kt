package org.ghostp2.app

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.ghostp2.engine.EngineHarness
import org.ghostp2.engine.EngineStatus
import org.ghostp2.engine.Notice
import org.ghostp2.engine.Presence
import org.ghostp2.engine.UnpairDelivery
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart

@RunWith(RobolectricTestRunner::class)
@Config(application = TestGhostApp::class)
class AppOpenAndDeleteTest {
    private val app = RuntimeEnvironment.getApplication() as TestGhostApp
    private val engine get() = app.container.engine

    @After
    fun tearDown() {
        runBlocking { engine.stop() }
    }

    private fun startEngine() {
        assertTrue(runBlocking { app.container.start().await() })
        assertEquals(EngineStatus.Online, engine.status.value)
    }

    @Test
    fun openingTheAppRetriesDisconnectedContactsAtOnce() {
        startEngine()
        EngineHarness(1, network = app.network).use { peer ->
            val (peerInMine, _) = runBlocking {
                peer.startAll()
                pairWithPeer(app, peer, TIMEOUT)
            }
            // El otro lado desaparece: el contacto queda desconectado y la siguiente llamada tardaría ≥ 12 s.
            runBlocking { peer.engine(0).stop() }
            runBlocking { withTimeout(TIMEOUT) { engine.presence.first { it[peerInMine] == Presence.OFFLINE } } }
            Thread.sleep(500)
            val before = app.tor.connectAttempts.get()
            Thread.sleep(1_000)
            assertEquals("sin abrir la app no se reintenta todavía", before, app.tor.connectAttempts.get())

            Robolectric.buildActivity(MainActivity::class.java).setup() // onResume ⇒ onAppOpened()

            eventually(5_000) { app.tor.connectAttempts.get() > before }
        }
    }

    @Test
    fun openingTheAppWithEngineStoppedDoesNothing() {
        Robolectric.buildActivity(MainActivity::class.java).setup()

        assertEquals(EngineStatus.Stopped, engine.status.value)
        assertEquals(0, app.tor.connectAttempts.get())
    }

    @Test
    fun deleteContactNotifiesThePeerAndRemovesItLocally() {
        startEngine()
        EngineHarness(1, network = app.network).use { peer ->
            runBlocking {
                peer.startAll()
                // Suscrito antes de borrar: los avisos no se reproducen.
                val notice = async(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                    peer.engine(0).notices.first { it is Notice.ContactUnpairedYou }
                }
                val (peerInMine, mineInPeer) = pairWithPeer(app, peer, TIMEOUT)
                withTimeout(TIMEOUT) { engine.presence.first { it[peerInMine] == Presence.ONLINE } }

                val result = withTimeout(TIMEOUT) { app.container.deleteContact(peerInMine, "Contacto").await() }

                assertEquals(UnpairDelivery.DELIVERED, result)
                assertTrue(app.storage.contacts.all().isEmpty())
                val unpaired = withTimeout(TIMEOUT) { notice.await() } as Notice.ContactUnpairedYou
                assertEquals(mineInPeer, unpaired.contactId)
                withTimeout(TIMEOUT) { peer.contacts(0).contacts.first { it.isEmpty() } }
            }
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
    }
}
