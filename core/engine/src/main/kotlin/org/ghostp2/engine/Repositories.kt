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
 *
 * **Copias.** Una implementación puede (la real lo hace) guardar copias de los `ByteArray` que recibe en
 * `upsert` y devolver copias nuevas en cada `all()` y en cada emisión de [contacts]. El motor no depende
 * de la identidad de los arrays: rellena con ceros los que él mismo deja de usar cuando puede (p. ej. los
 * del contacto borrado, o todos al borrar todo), pero muchas copias transitorias leídas del repositorio
 * no se rellenan y quedan en memoria hasta que el recolector de basura las libera (PROTOCOLO §15).
 */
interface ContactRepository {
    /**
     * Lista actual y sus cambios. Puede saltarse estados intermedios, pero debe emitir cada escritura
     * nueva aunque la lista sea igual (por contenido) a una anterior: el motor escribe él mismo algunos
     * cambios (p. ej. `IDENTITY_CHANGED`) y depende de ver la vuelta atrás (no deduplicar con `equals`).
     */
    val contacts: Flow<List<Contact>>

    suspend fun all(): List<Contact>

    suspend fun upsert(contact: Contact)

    suspend fun delete(id: String)

    suspend fun wipe()
}
