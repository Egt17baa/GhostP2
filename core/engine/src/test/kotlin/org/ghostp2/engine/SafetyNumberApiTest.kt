package org.ghostp2.engine

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SafetyNumberApiTest {
    private var harness: EngineHarness? = null

    @AfterEach
    fun cerrar() {
        harness?.close()
    }

    /** Empareja el motor 1 (invitado) con el 0; devuelve (id de B en A, id de A en B, número de `Paired`). */
    private suspend fun paired(): Triple<String, String, String> {
        val h = EngineHarness(2).also { harness = it }
        h.startAll()
        val handle = h.engine(0).createInvitation()
        val waiting = withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Waiting } } as InvitationState.Waiting
        val accepted = assertInstanceOf(AcceptResult.Paired::class.java, h.engine(1).acceptInvitation(waiting.text))
        withTimeout(TIMEOUT) { handle.state.first { it is InvitationState.Paired } }
        val bInA = h.contacts(0).all().single().id
        return Triple(bInA, accepted.contactId, accepted.safetyNumber)
    }

    @Test
    fun safetyNumberMatchesBothSides() = runBlocking<Unit> {
        val (bInA, aInB, expected) = paired()
        val h = harness!!
        val fromA = h.engine(0).safetyNumber(bInA)
        val fromB = h.engine(1).safetyNumber(aInB)
        assertEquals(expected, fromA)
        assertEquals(expected, fromB)
        assertTrue(Regex("""\d{5}( \d{5}){5}""").matches(fromA)) { "formato inesperado" }
        // Las claves privadas de la ejecución siguen intactas: el motor sigue funcionando.
        assertEquals(EngineStatus.Online, h.engine(0).status.value)
        assertTrue(h.identities(0).load()!!.identityPrivate.any { it != 0.toByte() })
    }

    @Test
    fun safetyNumberWorksWhileStopped() = runBlocking<Unit> {
        val (bInA, aInB, expected) = paired()
        val h = harness!!
        h.engine(0).stop()
        h.engine(1).stop()
        assertEquals(EngineStatus.Stopped, h.engine(0).status.value)
        assertEquals(expected, h.engine(0).safetyNumber(bInA))
        assertEquals(expected, h.engine(1).safetyNumber(aInB))
    }

    @Test
    fun readIdentityCopiesAreZeroed() = runBlocking<Unit> {
        val (bInA, _, expected) = paired()
        val h = harness!!
        // En marcha y parado: la copia que lee safetyNumber queda con las claves privadas a cero.
        for (stopFirst in listOf(false, true)) {
            if (stopFirst) h.engine(0).stop()
            val before = h.identities(0).loadedCopies.size
            assertEquals(expected, h.engine(0).safetyNumber(bInA))
            val read = h.identities(0).loadedCopies.drop(before)
            assertEquals(1, read.size)
            assertTrue(read.single().identityPrivate.all { it == 0.toByte() })
            assertTrue(read.single().onionPrivateKey!!.all { it == 0.toByte() })
        }
        // Lo guardado no se tocó: el motor vuelve a arrancar con la misma identidad y el mismo número.
        h.engine(0).start()
        assertEquals(EngineStatus.Online, h.engine(0).status.value)
        assertTrue(h.identities(0).load()!!.identityPrivate.any { it != 0.toByte() })
        assertEquals(expected, h.engine(0).safetyNumber(bInA))
    }

    @Test
    fun unknownContactThrows() = runBlocking<Unit> {
        paired()
        val h = harness!!
        assertUnknown(h.engine(0))
        h.engine(0).stop()
        assertUnknown(h.engine(0))
    }

    /** `safetyNumber` de un id que no es contacto guardado lanza `NoSuchElementException`. */
    private suspend fun assertUnknown(engine: MessengerEngine) {
        val failure = try {
            engine.safetyNumber("00".repeat(16))
            null
        } catch (e: NoSuchElementException) {
            e
        }
        assertNotNull(failure) { "no lanzó NoSuchElementException" }
    }

    private companion object {
        const val TIMEOUT = 10_000L
    }
}
