package org.ghostp2.core.protocol

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.ghostp2.core.crypto.Invitation
import org.ghostp2.core.crypto.Primitives
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.time.Duration.Companion.seconds

/**
 * Prueba de extremo a extremo sobre sockets TCP reales de bucle local (sin Tor): invitación,
 * emparejamiento XXpsk3 por una conexión, y sesión KKpsk2 con [PeerSession] por otra conexión
 * nueva, con los temporizadores de producción.
 */
class EndToEndSocketTest {
    /** .onion v3 válida (la de DuckDuckGo) usada como dirección ficticia en ambas ofertas. */
    private val fakeOnion = "duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad"

    /** Una conexión TCP aceptada por el servidor de A: extremo de A y extremo de B. */
    private class Connection(server: ServerSocket) : Closeable {
        private val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        private val accepted = server.accept()
        val a = FrameStream(accepted.getInputStream(), accepted.getOutputStream(), accepted)
        val b = FrameStream(client.getInputStream(), client.getOutputStream(), client)

        override fun close() {
            runCatching { client.close() }
            runCatching { accepted.close() }
        }
    }

    /** Registro de eventos suscrito de inmediato (antes de start()). */
    private class EventLog(session: PeerSession, scope: CoroutineScope) {
        private val seen = MutableStateFlow<List<SessionEvent>>(emptyList())

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { session.events.collect { e -> seen.update { it + e } } }
        }

        val all: List<SessionEvent> get() = seen.value

        suspend fun await(predicate: (SessionEvent) -> Boolean): SessionEvent =
            withTimeout(5.seconds) { seen.first { list -> list.any(predicate) }.first(predicate) }
    }

    @Test
    fun pairThenChatOverLocalhost() = runBlocking {
        val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connections = mutableListOf<Connection>()
        try {
            withTimeout(15.seconds) {
                val idA = Primitives.generateX25519()
                val idB = Primitives.generateX25519()

                // A genera la invitación; B la recibe como texto y la interpreta
                val invitationText = Invitation(fakeOnion, Primitives.randomBytes(Invitation.SECRET_SIZE)).encode()
                val inviteA = Invitation.parse(invitationText)
                val inviteB = Invitation.parse(invitationText)
                assertEquals(fakeOnion, inviteB.onion)

                val authForB = Primitives.generateX25519().privateKey // la que A entrega a B
                val authForA = Primitives.generateX25519().privateKey // la que B entrega a A
                val offerA = PairingOffer(fakeOnion, authForB)
                val offerB = PairingOffer(fakeOnion, authForA)

                // --- Conexión 1: emparejamiento (A invitador/escucha, B invitado/conecta) ---
                val c1 = withContext(Dispatchers.IO) { Connection(server) }.also { connections += it }
                val pairA = async { Pairing.asInviter(c1.a, idA, inviteA.secret, offerA) }
                val pairB = async { Pairing.asInvitee(c1.b, idB, inviteB.secret, offerB) }
                val resA = pairA.await()
                val resB = pairB.await()
                c1.close()

                assertArrayEquals(idB.publicKey, resA.peerIdentity)
                assertArrayEquals(idA.publicKey, resB.peerIdentity)
                assertArrayEquals(resA.pairSecret, resB.pairSecret)
                assertArrayEquals(authForA, resA.clientAuthPrivateForPeerOnion)
                assertArrayEquals(authForB, resB.clientAuthPrivateForPeerOnion)
                assertEquals(fakeOnion, resA.peerOnion)
                assertEquals(fakeOnion, resB.peerOnion)
                assertEquals(resA.safetyNumber, resB.safetyNumber)
                assertTrue(Regex("^(\\d{5} ){5}\\d{5}$").matches(resA.safetyNumber))

                // --- Conexión 2 (nueva): sesión KKpsk2, B llama y A lo reconoce entre sus contactos ---
                val c2 = withContext(Dispatchers.IO) { Connection(server) }.also { connections += it }
                val decoy = ContactKeys(Primitives.generateX25519().publicKey, Primitives.randomBytes(32))
                val contactB = ContactKeys(resA.peerIdentity, resA.pairSecret)
                val sessB = async { SessionHandshake.initiate(c2.b, idB, ContactKeys(resB.peerIdentity, resB.pairSecret)) }
                val sessA = async { SessionHandshake.respond(c2.a, idA, listOf(decoy, contactB)) }
                val channelB = sessB.await()
                val (who, channelA) = sessA.await()
                assertSame(contactB, who)

                val a = PeerSession(c2.a, channelA, scope)
                val b = PeerSession(c2.b, channelB, scope)
                val logA = EventLog(a, scope)
                val logB = EventLog(b, scope)
                a.start()
                b.start()

                // Texto A → B
                val t1 = a.sendText("Hola, ¿me lees? ñ ✓")
                val got1 = logB.await { it is SessionEvent.TextReceived && it.id == t1 } as SessionEvent.TextReceived
                assertEquals("Hola, ¿me lees? ñ ✓", got1.text)
                logA.await { it == SessionEvent.Delivered(t1) }

                // Texto B → A
                val t2 = b.sendText("Te leo perfectamente")
                val got2 = logA.await { it is SessionEvent.TextReceived && it.id == t2 } as SessionEvent.TextReceived
                assertEquals("Te leo perfectamente", got2.text)
                logB.await { it == SessionEvent.Delivered(t2) }

                // Foto de 1 MiB A → B
                val photo = Primitives.randomBytes(1024 * 1024)
                val p = a.sendPhoto(photo)
                val gotPhoto = logB.await { it is SessionEvent.PhotoReceived && it.id == p } as SessionEvent.PhotoReceived
                assertArrayEquals(photo, gotPhoto.jpeg)
                logA.await { it == SessionEvent.Delivered(p) }
                logA.await { it == SessionEvent.PhotoProgress(p, photo.size, photo.size) }

                // A cierra: B recibe Bye y se cierra con PEER_BYE
                a.close()
                logB.await { it == SessionEvent.Closed(CloseReason.PEER_BYE) }
                logA.await { it == SessionEvent.Closed(CloseReason.LOCAL) }
                assertFalse(a.isOpen.value)
                assertFalse(b.isOpen.value)

                // Un envío posterior de B se descarta
                val late = b.sendText("¿sigues ahí?")
                logB.await { it == SessionEvent.Failed(late, FailReason.NOT_CONNECTED) }

                // Ningún mensaje acabó en fallo salvo el envío tardío
                assertEquals(
                    listOf(SessionEvent.Failed(late, FailReason.NOT_CONNECTED)),
                    (logA.all + logB.all).filterIsInstance<SessionEvent.Failed>(),
                )
            }
        } finally {
            scope.cancel()
            connections.forEach { it.close() }
            runCatching { server.close() }
        }
    }
}
