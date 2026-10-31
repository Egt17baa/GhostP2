package org.ghostp2.data

import android.content.Context
import com.google.crypto.tink.Aead
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.ghostp2.engine.Contact
import org.ghostp2.engine.ContactRepository
import org.ghostp2.engine.Identity
import org.ghostp2.engine.IdentityRepository
import java.io.File

/*
 * Repositorios del motor sobre un único EncryptedStore<StoredData> (`ghostp2.bin`). El nombre del archivo
 * sigue al plan; no se usa Jetpack DataStore: EncryptedStore ya da escritura atómica, un único escritor y
 * un flujo con el último valor, y permite borrar un archivo dañado sin pelear con la caché de DataStore.
 *
 * Borrado en el archivo compartido:
 * - IdentityRepository.wipe() quita la identidad y conserva los contactos.
 * - ContactRepository.wipe() quita los contactos y conserva la identidad.
 * - Sin identidad ni contactos el archivo se borra (no queda nada en disco).
 * - Si el archivo está dañado, cualquiera de los dos wipe() lo borra entero: su contenido ya es
 *   irrecuperable y el usuario ha pedido empezar de cero. Es la única vía que descarta un archivo dañado.
 */

/** Nombre del archivo cifrado con identidad y contactos. */
const val STORE_FILE_NAME = "ghostp2.bin"

/** Datos asociados del AEAD: etiqueta fija y versionada del formato. */
private val STORE_LABEL = "ghostp2-store-v1".encodeToByteArray()

/** El almacén de GhostP2 sobre `file`; compartir la misma instancia entre los dos repositorios. */
fun ghostStore(file: File, aead: Aead): EncryptedStore<StoredData> =
    EncryptedStore(file, aead, StoredData.serializer(), StoredData.EMPTY, STORE_LABEL, isEmpty = { it.isEmpty })

/**
 * Repositorios listos para producción: `noBackupFilesDir/ghostp2.bin` cifrado con [KeystoreAead]. La clave
 * se crea en el primer acceso (no en esta llamada) y sus fallos llegan como [StoreCorruptedException].
 * Llamar una sola vez por proceso y compartir el resultado.
 */
fun createRepositories(context: Context): Pair<IdentityRepository, ContactRepository> {
    val app = context.applicationContext
    val file = File(app.noBackupFilesDir, STORE_FILE_NAME)
    val aead = RecoverableAead(
        dataExists = { file.exists() },
        resetKey = { KeystoreAead.reset(app) },
        create = { KeystoreAead.create(app) },
    )
    return createRepositories(app, aead)
}

internal fun createRepositories(context: Context, aead: Aead): Pair<IdentityRepository, ContactRepository> {
    val store = ghostStore(File(context.noBackupFilesDir, STORE_FILE_NAME), aead)
    return StoredIdentityRepository(store) to StoredContactRepository(store)
}

/** Sobre un almacén dañado, un borrado deja el almacén vacío y utilizable. */
private suspend fun EncryptedStore<StoredData>.updateOrReset(transform: (StoredData) -> StoredData) {
    try {
        update(transform)
    } catch (_: StoreCorruptedException) {
        reset()
    }
}

class StoredIdentityRepository(private val store: EncryptedStore<StoredData>) : IdentityRepository {
    override suspend fun load(): Identity? = store.read().identity?.toModel()

    override suspend fun save(identity: Identity) {
        val stored = StoredIdentity.from(identity)
        store.update { it.withIdentity(stored) }
    }

    override suspend fun wipe() {
        store.updateOrReset { if (it.identity == null) it else it.withIdentity(null) }
    }
}

/** Conserva el orden de inserción; `upsert` reemplaza en su sitio por `id`. */
class StoredContactRepository(private val store: EncryptedStore<StoredData>) : ContactRepository {
    /** Copias nuevas en cada emisión y para cada colector; una escritura de identidad no reemite. */
    override val contacts: Flow<List<Contact>> = store.data
        .map { it.contacts }
        .distinctUntilChanged { old, new -> old === new }
        .map { list -> list.map { it.toModel() } }

    override suspend fun all(): List<Contact> = store.read().contacts.map { it.toModel() }

    override suspend fun upsert(contact: Contact) {
        val stored = StoredContact.from(contact)
        store.update { data ->
            val list = data.contacts
            data.withContacts(
                if (list.any { it.id == stored.id }) list.map { if (it.id == stored.id) stored else it }
                else list + stored,
            )
        }
    }

    override suspend fun delete(id: String) {
        store.update { data ->
            if (data.contacts.none { it.id == id }) data else data.withContacts(data.contacts.filterNot { it.id == id })
        }
    }

    override suspend fun wipe() {
        store.updateOrReset { if (it.contacts.isEmpty()) it else it.withContacts(emptyList()) }
    }
}
