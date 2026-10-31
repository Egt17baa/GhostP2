package org.ghostp2.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * Banco de pruebas: `n` motores sobre una [FakeTorNetwork] compartida, cada uno con su [FakeTor],
 * repositorios en memoria, su diario de borrados pendientes ([InMemoryDeletionJournal]) y su propio `scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)`
 * (regla R7). [close] detiene todos los motores y cancela sus scopes.
 *
 * Uso: `EngineHarness(2).use { h -> h.startAll(); h.engine(0) ... }`.
 */
class EngineHarness(
    n: Int,
    val config: EngineConfig = EngineConfig(),
    val network: FakeTorNetwork = FakeTorNetwork(),
    /** Ver [FakeTor]: `true` simula un Tor real que olvida las autorizaciones de cliente al detenerse. */
    forgetClientAuthOnStop: Boolean = false,
) : AutoCloseable {
    /** Un motor con todas sus dependencias, accesibles para inspección o manipulación en pruebas. */
    class Node(
        val tor: FakeTor,
        val identities: InMemoryIdentityRepository,
        val contacts: InMemoryContactRepository,
        val journal: InMemoryDeletionJournal,
        val scope: CoroutineScope,
        val engine: MessengerEngine,
    )

    val nodes: List<Node> = List(n) {
        val tor = FakeTor(network, forgetClientAuthOnStop)
        val identities = InMemoryIdentityRepository()
        val contacts = InMemoryContactRepository()
        val journal = InMemoryDeletionJournal()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        Node(tor, identities, contacts, journal, scope, MessengerEngine(tor, identities, contacts, scope, config, deletions = journal))
    }

    fun node(i: Int): Node = nodes[i]

    fun engine(i: Int): MessengerEngine = nodes[i].engine

    fun tor(i: Int): FakeTor = nodes[i].tor

    fun identities(i: Int): InMemoryIdentityRepository = nodes[i].identities

    fun contacts(i: Int): InMemoryContactRepository = nodes[i].contacts

    fun journal(i: Int): InMemoryDeletionJournal = nodes[i].journal

    fun scope(i: Int): CoroutineScope = nodes[i].scope

    /** Arranca todos los motores (en orden). */
    suspend fun startAll() {
        nodes.forEach { it.engine.start() }
    }

    /** Detiene todos los motores y cancela sus scopes; idempotente. */
    override fun close() {
        runBlocking {
            nodes.forEach { runCatching { it.engine.stop() } }
        }
        nodes.forEach { it.scope.cancel() }
    }
}
