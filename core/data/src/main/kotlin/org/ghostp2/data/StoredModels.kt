@file:OptIn(ExperimentalSerializationApi::class)

package org.ghostp2.data

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.ByteString
import org.ghostp2.engine.Contact
import org.ghostp2.engine.ContactStatus
import org.ghostp2.engine.Identity

/**
 * Contenido completo de `ghostp2.bin`: identidad, contactos, ajustes y borrados pendientes en un único
 * objeto CBOR, para que escribir uno nunca pierda los demás. Los campos del formato 2 (R21) tienen valor
 * por defecto: un archivo de formato 1 se lee con ajustes por defecto y sin borrados pendientes.
 * Inmutable; las conversiones a/desde el modelo del motor copian siempre los `ByteArray`, así que el
 * motor puede rellenar con ceros sus arrays sin tocar los de aquí. Ni estas copias ni muchas de las que
 * entrega se rellenan con ceros: quedan para el recolector de basura (PROTOCOLO §15).
 */
@Serializable
class StoredData internal constructor(
    internal val identity: StoredIdentity? = null,
    internal val contacts: List<StoredContact> = emptyList(),
    /** `null` = ajustes por defecto (nunca se guarda un objeto igual a los de por defecto). */
    internal val settings: StoredSettings? = null,
    /** Ids de contacto con el borrado a medias ([org.ghostp2.engine.DeletionJournal]), sin repetidos. */
    internal val pendingDeletions: List<String> = emptyList(),
) {
    /** Nada que guardar: el archivo se borra. */
    internal val isEmpty: Boolean
        get() = identity == null && contacts.isEmpty() && pendingDeletions.isEmpty() &&
            (settings == null || settings.toModel() == AppSettings())

    internal fun appSettings(): AppSettings = (settings?.toModel() ?: AppSettings()).sanitized()

    internal fun withIdentity(identity: StoredIdentity?) = StoredData(identity, contacts, settings, pendingDeletions)

    internal fun withContacts(contacts: List<StoredContact>) = StoredData(identity, contacts, settings, pendingDeletions)

    /** Unos ajustes iguales a los de por defecto se guardan como `null`. */
    internal fun withSettings(settings: StoredSettings?) =
        StoredData(identity, contacts, settings?.takeIf { it.toModel() != AppSettings() }, pendingDeletions)

    internal fun withPendingDeletions(pendingDeletions: List<String>) =
        StoredData(identity, contacts, settings, pendingDeletions)

    internal companion object {
        val EMPTY = StoredData()
    }
}

/**
 * Ajustes tal como se guardan. Todos los campos tienen valor por defecto: un archivo sin alguno (de una
 * versión anterior) se lee igual. Los valores no se validan aquí; [AppSettings.sanitized] al leer.
 */
@Serializable
internal class StoredSettings(
    val onboardingDone: Boolean = false,
    val lockEnabled: Boolean = false,
    val lockAfterMinutes: Int = 1,
    val hideNotificationNames: Boolean = false,
    val maxPhotoSidePx: Int = 2048,
    val startOnBoot: Boolean = false,
) {
    fun toModel() = AppSettings(
        onboardingDone = onboardingDone,
        lockEnabled = lockEnabled,
        lockAfterMinutes = lockAfterMinutes,
        hideNotificationNames = hideNotificationNames,
        maxPhotoSidePx = maxPhotoSidePx,
        startOnBoot = startOnBoot,
    )

    companion object {
        fun from(s: AppSettings) = StoredSettings(
            onboardingDone = s.onboardingDone,
            lockEnabled = s.lockEnabled,
            lockAfterMinutes = s.lockAfterMinutes,
            hideNotificationNames = s.hideNotificationNames,
            maxPhotoSidePx = s.maxPhotoSidePx,
            startOnBoot = s.startOnBoot,
        )
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
