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
 *   (repositorio) para que la dirección sea estable. Las siguientes reutilizan esa clave.
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
        if (current != null && keySet == publishedKeys) return@withLock withKnownKey(identity, current)
        if (current != null) retire(current)

        val service = tor.publishService(
            identity.onionPrivateKey ?: knownPrivateKey,
            keys.map { it.copyOf() },
            virtualPort,
            localPort,
        )
        publishedOnion = service.onion
        publishedKeys = keySet
        knownPrivateKey = service.privateKey.copyOf()
        val updated = identity.copy(onionPrivateKey = service.privateKey, onion = service.onion)
        if (updated != identity) identities.save(updated)
        updated
    }

    /** Retira la .onion permanente si está publicada (al detener el motor); idempotente. */
    suspend fun unpublish() {
        mutex.withLock { publishedOnion?.let { retire(it) } }
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
