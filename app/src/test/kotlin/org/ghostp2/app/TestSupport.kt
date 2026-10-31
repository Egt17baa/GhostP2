package org.ghostp2.app

import android.os.Looper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeout
import org.ghostp2.data.AppSettings
import org.ghostp2.data.SettingsRepository
import org.ghostp2.engine.AcceptResult
import org.ghostp2.engine.ContactRepository
import org.ghostp2.engine.EngineHarness
import org.ghostp2.engine.InvitationState
import org.ghostp2.engine.FakeTor
import org.ghostp2.engine.FakeTorNetwork
import org.ghostp2.engine.Identity
import org.ghostp2.engine.IdentityRepository
import org.ghostp2.engine.InMemoryContactRepository
import org.ghostp2.engine.InMemoryDeletionJournal
import org.ghostp2.engine.InMemoryIdentityRepository
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.robolectric.Shadows.shadowOf

/** Aplicación de pruebas: el mismo [GhostApp] con [FakeTor] y almacenamiento en memoria en su [AppContainer]. */
class TestGhostApp : GhostApp() {
    val network = FakeTorNetwork()
    val tor = FakeTor(network)
    val storage = TestStorage()

    override fun createContainer(): AppContainer = AppContainer(this, tor, storage)
}

/** Almacenamiento en memoria; [failure] hace fallar la lectura de la identidad (como un archivo ilegible). */
class TestStorage : AppStorage {
    @Volatile var failure: Exception? = null
    @Volatile var forceResets = 0

    private val memoryIdentities = InMemoryIdentityRepository()

    override val identities: IdentityRepository = object : IdentityRepository {
        override suspend fun load(): Identity? {
            failure?.let { throw it }
            return memoryIdentities.load()
        }

        override suspend fun save(identity: Identity) {
            failure?.let { throw it }
            memoryIdentities.save(identity)
        }

        override suspend fun wipe() {
            memoryIdentities.wipe()
        }
    }

    override val contacts: ContactRepository = InMemoryContactRepository()

    override val settings = InMemorySettingsRepository()

    override val deletions = InMemoryDeletionJournal()

    override suspend fun probe() {
        identities.load()
    }

    /** Como el real: borra todo el archivo, ajustes y borrados pendientes incluidos. */
    override suspend fun forceReset() {
        forceResets++
        failure = null
        memoryIdentities.wipe()
        contacts.wipe()
        settings.wipe()
        deletions.pending().forEach { deletions.remove(it) }
    }
}

/** Ajustes en memoria; como el real, las lecturas devuelven [AppSettings.sanitized]. */
class InMemorySettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    private val state = MutableStateFlow(initial.sanitized())

    override val settings: Flow<AppSettings> = state

    override suspend fun get(): AppSettings = state.value

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        state.update { transform(it).sanitized() }
    }

    override suspend fun wipe() {
        state.value = AppSettings()
    }
}

/** Espera (procesando el hilo principal de Robolectric) hasta que [condition] se cumpla. */
fun eventually(timeoutMillis: Long = 10_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (condition()) return
        if (System.currentTimeMillis() > deadline) fail("La condición no se cumplió en $timeoutMillis ms")
        Thread.sleep(20)
    }
}

/**
 * Empareja el motor del [AppContainer] de [app] (ya `Online`) con el motor 0 de [peer] (arrancado, en la
 * misma `FakeTorNetwork`): invita el otro lado y acepta GhostP2. Devuelve (id del otro en GhostP2,
 * id de GhostP2 en el otro).
 */
suspend fun pairWithPeer(app: TestGhostApp, peer: EngineHarness, timeoutMillis: Long = 20_000): Pair<String, String> {
    val handle = peer.engine(0).createInvitation()
    val waiting = withTimeout(timeoutMillis) { handle.state.first { it is InvitationState.Waiting } } as InvitationState.Waiting
    assertTrue(app.container.engine.acceptInvitation(waiting.text) is AcceptResult.Paired)
    withTimeout(timeoutMillis) { handle.state.first { it is InvitationState.Paired } }
    return app.storage.contacts.all().single().id to peer.contacts(0).all().single().id
}
