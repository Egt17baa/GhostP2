@file:OptIn(ExperimentalSerializationApi::class)

package org.ghostp2.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import org.ghostp2.engine.Contact
import org.ghostp2.engine.ContactStatus
import org.ghostp2.engine.Identity

/**
 * Contenido completo de `ghostp2.bin`: identidad y contactos en un único objeto CBOR, para que escribir
 * uno nunca pierda el otro. Inmutable; las conversiones a/desde el modelo del motor copian siempre los
 * `ByteArray` (el motor pone a cero los arrays que entrega y los que recibe).
 */
@Serializable
class StoredData internal constructor(
    internal val identity: StoredIdentity? = null,
    internal val contacts: List<StoredContact> = emptyList(),
) {
    internal val isEmpty: Boolean get() = identity == null && contacts.isEmpty()

    internal fun withIdentity(identity: StoredIdentity?) = StoredData(identity, contacts)

    internal fun withContacts(contacts: List<StoredContact>) = StoredData(identity, contacts)

    internal companion object {
        val EMPTY = StoredData()
    }
}

@Serializable
internal class StoredIdentity(
    @ByteString val identityPrivate: ByteArray,
    @ByteString val identityPublic: ByteArray,
    @ByteString val onionPrivateKey: ByteArray? = null,
    val onion: String? = null,
) {
    fun toModel() = Identity(identityPrivate.copyOf(), identityPublic.copyOf(), onionPrivateKey?.copyOf(), onion)

    companion object {
        fun from(i: Identity) =
            StoredIdentity(i.identityPrivate.copyOf(), i.identityPublic.copyOf(), i.onionPrivateKey?.copyOf(), i.onion)
    }
}

/** Estado guardado; un valor desconocido falla al decodificar (⇒ almacenamiento ilegible). */
@Serializable
internal enum class StoredStatus { ACTIVE, IDENTITY_CHANGED }

@Serializable
internal class StoredContact(
    val id: String,
    val name: String,
    @ByteString val peerIdentity: ByteArray,
    val peerOnion: String,
    @ByteString val clientAuthPrivateForPeerOnion: ByteArray,
    @ByteString val myClientAuthPublic: ByteArray,
    @ByteString val pairSecret: ByteArray,
    val verified: Boolean,
    val status: StoredStatus,
) {
    fun toModel() = Contact(
        id = id,
        name = name,
        peerIdentity = peerIdentity.copyOf(),
        peerOnion = peerOnion,
        clientAuthPrivateForPeerOnion = clientAuthPrivateForPeerOnion.copyOf(),
        myClientAuthPublic = myClientAuthPublic.copyOf(),
        pairSecret = pairSecret.copyOf(),
        verified = verified,
        status = when (status) {
            StoredStatus.ACTIVE -> ContactStatus.ACTIVE
            StoredStatus.IDENTITY_CHANGED -> ContactStatus.IDENTITY_CHANGED
        },
    )

    companion object {
        fun from(c: Contact) = StoredContact(
            id = c.id,
            name = c.name,
            peerIdentity = c.peerIdentity.copyOf(),
            peerOnion = c.peerOnion,
            clientAuthPrivateForPeerOnion = c.clientAuthPrivateForPeerOnion.copyOf(),
            myClientAuthPublic = c.myClientAuthPublic.copyOf(),
            pairSecret = c.pairSecret.copyOf(),
            verified = c.verified,
            status = when (c.status) {
                ContactStatus.ACTIVE -> StoredStatus.ACTIVE
                ContactStatus.IDENTITY_CHANGED -> StoredStatus.IDENTITY_CHANGED
            },
        )
    }
}
