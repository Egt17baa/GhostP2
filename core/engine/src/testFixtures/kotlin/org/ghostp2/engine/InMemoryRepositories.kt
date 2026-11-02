package org.ghostp2.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Repositorio de identidad en memoria para pruebas. Como el real (que descifra y decodifica en cada
 * lectura), [save] guarda una copia y cada [load] devuelve una copia nueva: rellenar con ceros lo que se
 * ha leído no toca lo guardado ni lo que usa el motor. Para comprobar esas puestas a cero,
 * [loadedCopies] da las copias entregadas por [load] y [saveArguments] los objetos recibidos en [save]
 * (los del propio motor), en orden.
 */
class InMemoryIdentityRepository(initial: Identity? = null) : IdentityRepository {
    @Volatile
    private var identity: Identity? = initial?.deepCopy()

    private val loaded = CopyOnWriteArrayList<Identity>()
    private val saved = CopyOnWriteArrayList<Identity>()

    val loadedCopies: List<Identity> get() = loaded.toList()

    val saveArguments: List<Identity> get() = saved.toList()

    override suspend fun load(): Identity? = identity?.deepCopy()?.also { loaded += it }

    override suspend fun save(identity: Identity) {
        saved += identity
        this.identity = identity.deepCopy()
    }

    override suspend fun wipe() {
        identity = null
    }

    private fun Identity.deepCopy(): Identity =
        Identity(identityPrivate.copyOf(), identityPublic.copyOf(), onionPrivateKey?.copyOf(), onion)
}

/**
 * Repositorio de contactos en memoria para pruebas; conserva el orden de inserción.
 *
 * Como el repositorio real (`DataStoreRepositories`: `distinctUntilChanged { old, new -> old === new }`),
 * [contacts] puede saltarse estados intermedios (conflación) pero emite siempre cada escritura nueva,
 * aunque la lista sea IGUAL (por contenido) a la última que vio un recolector. Un `StateFlow<List<Contact>>`
 * directo no lo haría: con `A` → `IDENTITY_CHANGED` → `A` otra vez, un recolector que llegue tarde al
 * estado intermedio no recibe la vuelta a `A` (igual a la última que vio) y el motor no se entera.
 */
class InMemoryContactRepository(initial: List<Contact> = emptyList()) : ContactRepository {
    /** Sin `equals`: cada escritura es un valor nuevo para el `StateFlow`. */
    private class Snapshot(val list: List<Contact>)

    private val state = MutableStateFlow(Snapshot(initial))

    override val contacts: Flow<List<Contact>> = state.map { it.list }

    override suspend fun all(): List<Contact> = state.value.list

    override suspend fun upsert(contact: Contact) {
        state.update { s ->
            val list = s.list
            Snapshot(
                if (list.any { it.id == contact.id }) list.map { if (it.id == contact.id) contact else it }
                else list + contact,
            )
        }
    }

    override suspend fun delete(id: String) {
        state.update { s -> Snapshot(s.list.filterNot { it.id == id }) }
    }

    override suspend fun wipe() {
        state.value = Snapshot(emptyList())
    }
}

/** Diario de borrados pendientes en memoria para pruebas. */
class InMemoryDeletionJournal(initial: Set<String> = emptySet()) : DeletionJournal {
    private val ids = MutableStateFlow(initial)

    override suspend fun pending(): Set<String> = ids.value

    override suspend fun add(contactId: String) {
        ids.update { it + contactId }
    }

    override suspend fun remove(contactId: String) {
        ids.update { it - contactId }
    }
}
