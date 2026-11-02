package org.ghostp2.engine

import org.ghostp2.core.crypto.Primitives

/** Hex en minúsculas de los 16 primeros bytes de SHA-256(peerIdentity): identificador estable del contacto. */
fun contactIdFor(peerIdentity: ByteArray): String =
    Primitives.sha256(peerIdentity).copyOfRange(0, 16).joinToString("") { "%02x".format(it) }

private fun ByteArray?.contentHash(): Int = this?.contentHashCode() ?: 0

private fun eqNullable(a: ByteArray?, b: ByteArray?): Boolean =
    if (a == null || b == null) a === b else a.contentEquals(b)

/**
 * Identidad local: par de claves de identidad y, si ya se publicó, la clave ED25519-V3 de Tor (64 bytes)
 * con su .onion permanente (sin ".onion", minúsculas). `toString` nunca muestra claves privadas.
 */
class Identity(
    val identityPrivate: ByteArray,
    val identityPublic: ByteArray,
    val onionPrivateKey: ByteArray?,
    val onion: String?,
) {
    fun copy(
        identityPrivate: ByteArray = this.identityPrivate,
        identityPublic: ByteArray = this.identityPublic,
        onionPrivateKey: ByteArray? = this.onionPrivateKey,
        onion: String? = this.onion,
    ): Identity = Identity(identityPrivate, identityPublic, onionPrivateKey, onion)

    override fun equals(other: Any?): Boolean =
        other is Identity &&
            identityPrivate.contentEquals(other.identityPrivate) &&
            identityPublic.contentEquals(other.identityPublic) &&
            eqNullable(onionPrivateKey, other.onionPrivateKey) &&
            onion == other.onion

    override fun hashCode(): Int {
        var h = identityPrivate.contentHashCode()
        h = 31 * h + identityPublic.contentHashCode()
        h = 31 * h + onionPrivateKey.contentHash()
        h = 31 * h + (onion?.hashCode() ?: 0)
        return h
    }

    override fun toString(): String =
        "Identity(identityPublic=${identityPublic.size}B, onion=$onion, claves privadas ocultas)"
}

enum class ContactStatus { ACTIVE, IDENTITY_CHANGED }

/**
 * Contacto emparejado. `id` = [contactIdFor] de `peerIdentity`. `clientAuthPrivateForPeerOnion` es la
 * clave x25519 privada para autorizarnos en la .onion del contacto; `myClientAuthPublic` es la pública
 * que registramos en nuestra .onion para él. `toString` no muestra secretos.
 */
class Contact(
    val id: String,
    val name: String,
    val peerIdentity: ByteArray,
    val peerOnion: String,
    val clientAuthPrivateForPeerOnion: ByteArray,
    val myClientAuthPublic: ByteArray,
    val pairSecret: ByteArray,
    val verified: Boolean,
    val status: ContactStatus,
) {
    fun copy(
        id: String = this.id,
        name: String = this.name,
        peerIdentity: ByteArray = this.peerIdentity,
        peerOnion: String = this.peerOnion,
        clientAuthPrivateForPeerOnion: ByteArray = this.clientAuthPrivateForPeerOnion,
        myClientAuthPublic: ByteArray = this.myClientAuthPublic,
        pairSecret: ByteArray = this.pairSecret,
        verified: Boolean = this.verified,
        status: ContactStatus = this.status,
    ): Contact = Contact(
        id, name, peerIdentity, peerOnion, clientAuthPrivateForPeerOnion,
        myClientAuthPublic, pairSecret, verified, status,
    )

    override fun equals(other: Any?): Boolean =
        other is Contact &&
            id == other.id &&
            name == other.name &&
            peerIdentity.contentEquals(other.peerIdentity) &&
            peerOnion == other.peerOnion &&
            clientAuthPrivateForPeerOnion.contentEquals(other.clientAuthPrivateForPeerOnion) &&
            myClientAuthPublic.contentEquals(other.myClientAuthPublic) &&
            pairSecret.contentEquals(other.pairSecret) &&
            verified == other.verified &&
            status == other.status

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + name.hashCode()
        h = 31 * h + peerIdentity.contentHashCode()
        h = 31 * h + peerOnion.hashCode()
        h = 31 * h + clientAuthPrivateForPeerOnion.contentHashCode()
        h = 31 * h + myClientAuthPublic.contentHashCode()
        h = 31 * h + pairSecret.contentHashCode()
        h = 31 * h + verified.hashCode()
        h = 31 * h + status.hashCode()
        return h
    }

    override fun toString(): String =
        "Contact(id=$id, name=$name, peerOnion=$peerOnion, verified=$verified, status=$status, secretos ocultos)"
}
