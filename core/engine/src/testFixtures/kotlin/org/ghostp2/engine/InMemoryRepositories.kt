package org.ghostp2.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Repositorio de identidad en memoria para pruebas. */
class InMemoryIdentityRepository(initial: Identity? = null) : IdentityRepository {
    @Volatile
    private var identity: Identity? = initial

    override suspend fun load(): Identity? = identity

    override suspend fun save(identity: Identity) {
        this.identity = identity
    }

    override suspend fun wipe() {
        identity = null
    }
}

/** Repositorio de contactos en memoria para pruebas; conserva el orden de inserción. */
class InMemoryContactRepository(initial: List<Contact> = emptyList()) : ContactRepository {
    private val state = MutableStateFlow(initial)

    override val contacts: StateFlow<List<Contact>> = state

    override suspend fun all(): List<Contact> = state.value

    override suspend fun upsert(contact: Contact) {
        state.update { list ->
            if (list.any { it.id == contact.id }) list.map { if (it.id == contact.id) contact else it }
            else list + contact
        }
    }

    override suspend fun delete(id: String) {
        state.update { list -> list.filterNot { it.id == id } }
    }

    override suspend fun wipe() {
        state.value = emptyList()
    }
}
