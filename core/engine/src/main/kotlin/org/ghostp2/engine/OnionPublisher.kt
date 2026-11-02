package org.ghostp2.engine

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Publica la .onion permanente según la regla global:
 *
 * - Solo se publica si hay al menos una clave de autorización de cliente: la `myClientAuthPublic` de
 *   **todos** los contactos guardados (también los `IDENTITY_CHANGED`, que conservan su clave hasta que
 *   el usuario los borre) más `extraClientAuthKeys` (p. ej. el contacto pendiente durante un
 *   emparejamiento, regla R10). **Nunca** se llama a `publishService` con la lista vacía.
 * - La primera publicación genera la dirección; su `privateKey` y `onion` se guardan en la [Identity]
 *   (repositorio) para que la dirección sea estable. Las siguientes reutilizan esa clave. Si llega una
 *   identidad antigua (sin .onion) con las mismas claves, se completa con la clave conocida y también se
 *   guarda; si el guardado falla, [sync] lanza sin cambiar la publicación.
 * - Si cambia el conjunto de claves: se retira y se vuelve a publicar con la misma clave privada.
 * - Si no queda ninguna clave: se retira el servicio, pero `onionPrivateKey`/`onion` se conservan en la
 *   identidad: la dirección queda reservada para cuando vuelva a haber contactos.
 *
 * [sync] y [unpublish] están serializados con un `Mutex`: pueden llamarse concurrentemente.
 */
internal class OnionPublisher(
    private val tor: TorTransport,
    private val identities: IdentityRepository,
    private val virtualPort: Int,
    private val localPort: Int,
) {
    private val mutex = Mutex()

    /** Clave ED25519-V3 conocida más reciente (por si una llamada concurrente trae una identidad antigua). */
    private var knownPrivateKey: ByteArray? = null
    private var publishedKeys: Set<String> = emptySet()

    /** .onion permanente publicada ahora mismo, o `null`. */
    @Volatile
    var publishedOnion: String? = null
        private set

    /** Ajusta la publicación a `contacts` (+ `extraClientAuthKeys`) y devuelve la identidad actualizada. */
    suspend fun sync(
        identity: Identity,
        contacts: List<Contact>,
        extraClientAuthKeys: List<ByteArray> = emptyList(),
    ): Identity = mutex.withLock {
        val keys = (contacts.map { it.myClientAuthPublic } + extraClientAuthKeys)
            .distinctBy { it.toHex() }
        val keySet = keys.map { it.toHex() }.toSet()
        val current = publishedOnion

        if (keys.isEmpty()) {
            if (current != null) retire(current)
            return@withLock identity
        }
        if (current != null && keySet == publishedKeys) {
            // Identidad antigua (sin .onion) completada con la clave ya publicada: se guarda, como tras la
            // primera publicación, para que la dirección sobreviva a un reinicio. Si no se puede guardar,
            // falla esta llamada (la publicación sigue igual; el llamante reintenta).
            val completed = withKnownKey(identity, current)
            if (completed !== identity) identities.save(completed)
            return@withLock completed
        }
        if (current != null) retire(current)

        val service = tor.publishService(
            identity.onionPrivateKey ?: knownPrivateKey,
            keys.map { it.copyOf() },
            virtualPort,
            localPort,
        )
        publishedOnion = service.onion
        publishedKeys = keySet
        knownPrivateKey?.fill(0)
        knownPrivateKey = service.privateKey.copyOf()
        // Misma clave que ya tiene la identidad: se conserva ese array (el que se borra al borrar todo)
        // y la copia que devolvió Tor se rellena con ceros, en vez de dejar arrays duplicados sueltos.
        // El contrato de publishService dice que devuelve un array nuevo; aun así, si fuera el mismo
        // objeto que `held`, borrarlo dejaría la identidad sin clave: nunca se rellena ese array.
        val held = identity.onionPrivateKey
        val key = if (held != null && held.contentEquals(service.privateKey)) {
            if (service.privateKey !== held) service.privateKey.fill(0)
            held
        } else {
            service.privateKey
        }
        val updated = identity.copy(onionPrivateKey = key, onion = service.onion)
        if (updated != identity) identities.save(updated)
        updated
    }

    /** Retira la .onion permanente si está publicada (al detener el motor); idempotente. */
    suspend fun unpublish() {
        mutex.withLock { publishedOnion?.let { retire(it) } }
    }

    /** Rellena con ceros la copia de la clave ED25519-V3 (al descartar este publicador). */
    fun wipeKeys() {
        knownPrivateKey?.fill(0)
        knownPrivateKey = null
    }

    /** Retira el servicio; si Tor falla, el error se propaga y el estado sigue diciendo "publicado". */
    private suspend fun retire(onion: String) {
        tor.unpublishService(onion)
        publishedOnion = null
        publishedKeys = emptySet()
    }

    /** Si la identidad recibida es antigua (sin .onion), completa con la clave ya publicada. */
    private fun withKnownKey(identity: Identity, onion: String): Identity {
        val key = knownPrivateKey ?: return identity
        return if (identity.onion == onion) identity else identity.copy(onionPrivateKey = key.copyOf(), onion = onion)
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
