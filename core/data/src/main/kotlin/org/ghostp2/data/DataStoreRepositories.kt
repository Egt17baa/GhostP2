package org.ghostp2.data

import android.content.Context
import com.google.crypto.tink.Aead
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import org.ghostp2.engine.Contact
import org.ghostp2.engine.ContactRepository
import org.ghostp2.engine.DeletionJournal
import org.ghostp2.engine.Identity
import org.ghostp2.engine.IdentityRepository
import java.io.File

/*
 * Repositorios del motor y de los ajustes sobre un único EncryptedStore<StoredData> (`ghostp2.bin`). El
 * nombre del archivo sigue al plan; no se usa Jetpack DataStore: EncryptedStore ya da escritura atómica, un
 * único escritor y un flujo con el último valor, y permite borrar un archivo dañado sin pelear con la caché
 * de DataStore.
 *
 * Borrado en el archivo compartido:
 * - IdentityRepository.wipe() quita la identidad y conserva lo demás.
 * - ContactRepository.wipe() quita los contactos y conserva lo demás.
 * - SettingsRepository.wipe() vuelve a los ajustes por defecto y conserva lo demás.
 * - Sin identidad, contactos ni borrados pendientes y con los ajustes por defecto el archivo se borra (no
 *   queda nada en disco).
 * - Si el archivo está dañado (StoreCorruptedException), cualquiera de los wipe() lo borra entero: su
 *   contenido ya es irrecuperable y el usuario ha pedido empezar de cero. Es la única vía que descarta un
 *   archivo dañado. Ante StoreUnavailableException (fallo pasajero) wipe() falla y no borra nada.
 */

/** Nombre del archivo cifrado con identidad, contactos, ajustes y borrados pendientes. */
const val STORE_FILE_NAME = "ghostp2.bin"

/**
 * Datos asociados del AEAD tras el byte de formato: etiqueta fija. No cambia con el formato 2 (el byte de
 * formato ya va en los datos asociados), así que los archivos de formato 1 se siguen autenticando.
 */
private val STORE_LABEL = "ghostp2-store-v1".encodeToByteArray()

/** El almacén de GhostP2 sobre `file`; compartir la misma instancia entre todos los repositorios. */
fun ghostStore(file: File, aead: Aead): EncryptedStore<StoredData> =
    EncryptedStore(file, aead, StoredData.serializer(), StoredData.EMPTY, STORE_LABEL, isEmpty = { it.isEmpty })

/**
 * Repositorios listos para producción: `noBackupFilesDir/ghostp2.bin` cifrado con [KeystoreAead]. La clave
 * se abre en el primer acceso (no en esta llamada); sus fallos llegan como [StoreCorruptedException]
 * (clave perdida con datos: "Empezar de cero") o [StoreUnavailableException] ("Reintentar").
 * Llamar una sola vez por proceso y compartir el resultado; admite `val (ids, contacts) = ...`.
 */
fun createRepositories(context: Context): StoredRepositories {
    val app = context.applicationContext
    val file = File(app.noBackupFilesDir, STORE_FILE_NAME)
    return createRepositories(app, KeystoreAead.create(app, dataExists = { file.exists() }))
}

internal fun createRepositories(context: Context, aead: Aead): StoredRepositories {
    val store = ghostStore(File(context.noBackupFilesDir, STORE_FILE_NAME), aead)
    val keyReset: () -> Unit = (aead as? RecoverableAead)?.let { it::forceReset } ?: {}
    return StoredRepositories(
        StoredIdentityRepository(store),
        StoredContactRepository(store),
        StoredSettingsRepository(store),
        StoredDeletionJournal(store),
        store,
        keyReset,
    )
}

/** Los repositorios sobre el mismo almacén, más el borrado forzado. */
class StoredRepositories internal constructor(
    val identities: IdentityRepository,
    val contacts: ContactRepository,
    val settings: SettingsRepository,
    val deletions: DeletionJournal,
    private val store: EncryptedStore<StoredData>,
    private val keyReset: () -> Unit,
) {
    operator fun component1(): IdentityRepository = identities

    operator fun component2(): ContactRepository = contacts

    /**
     * "Borrar todo" sin condiciones, para cuando "Reintentar" ya no sirve (p. ej. StoreUnavailableException
     * repetida): borra `ghostp2.bin` (y su `.tmp`, con los ajustes y los borrados pendientes), el keyset
     * de las preferencias, su marca de confirmación y la clave maestra de Keystore, sea cual sea su
     * estado; el flujo de contactos emite la lista vacía y la próxima escritura genera una clave nueva.
     * Irreversible: la interfaz debe pedir una doble confirmación. Primero el archivo y después la clave:
     * si el borrado del archivo falla ([StoreUnavailableException]) la clave no se toca.
     *
     * El borrado de la clave es de mejor esfuerzo: si Keystore no deja borrar la clave maestra (o las
     * preferencias no se borran), el error se ignora y no se informa. El archivo ya no existe, así que lo
     * que quede de la clave no protege nada, y la próxima escritura genera otra.
     */
    suspend fun forceReset() {
        store.reset(alsoUnderLock = keyReset)
    }
}

/** Sobre un almacén dañado, un borrado deja el almacén vacío y utilizable. */
internal suspend fun EncryptedStore<StoredData>.updateOrReset(transform: (StoredData) -> StoredData) {
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

/**
 * Diario de borrados pendientes en `ghostp2.bin`: solo ids de contacto, en orden de alta y sin repetidos.
 * Sus errores llegan tal cual (el motor los trata como de mejor esfuerzo).
 */
class StoredDeletionJournal(private val store: EncryptedStore<StoredData>) : DeletionJournal {
    override suspend fun pending(): Set<String> = store.read().pendingDeletions.toSet()

    override suspend fun add(contactId: String) {
        store.update { data ->
            if (contactId in data.pendingDeletions) data else data.withPendingDeletions(data.pendingDeletions + contactId)
        }
    }

    override suspend fun remove(contactId: String) {
        store.update { data ->
            if (contactId !in data.pendingDeletions) data
            else data.withPendingDeletions(data.pendingDeletions - contactId)
        }
    }
}
