package org.ghostp2.engine

import kotlinx.coroutines.flow.Flow

/** Almacenamiento de la identidad local. */
interface IdentityRepository {
    suspend fun load(): Identity?

    suspend fun save(identity: Identity)

    suspend fun wipe()
}

/** Almacenamiento de contactos; `upsert` reemplaza por `id`. */
interface ContactRepository {
    val contacts: Flow<List<Contact>>

    suspend fun all(): List<Contact>

    suspend fun upsert(contact: Contact)

    suspend fun delete(id: String)

    suspend fun wipe()
}
