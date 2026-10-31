package org.ghostp2.engine

import kotlinx.coroutines.flow.Flow

/**
 * Almacenamiento de la identidad local. Identidad y contactos pueden compartir un único archivo: si el
 * almacenamiento es ilegible, `wipe()` de cualquiera de los dos repositorios borra también el otro.
 */
interface IdentityRepository {
    suspend fun load(): Identity?

    suspend fun save(identity: Identity)

    suspend fun wipe()
}

/**
 * Almacenamiento de contactos; `upsert` reemplaza por `id`. Si el almacenamiento compartido con la
 * identidad es ilegible, `wipe()` borra también la identidad.
 */
interface ContactRepository {
    val contacts: Flow<List<Contact>>

    suspend fun all(): List<Contact>

    suspend fun upsert(contact: Contact)

    suspend fun delete(id: String)

    suspend fun wipe()
}
