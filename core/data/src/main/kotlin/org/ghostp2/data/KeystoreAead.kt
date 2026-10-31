package org.ghostp2.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplate
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import com.google.crypto.tink.integration.android.AndroidKeystore
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.crypto.AEADBadTagException

/**
 * AEAD de producción: keyset Tink AES-256-GCM en las preferencias privadas `ghostp2_keyset`, cifrado con
 * la clave maestra `android-keystore://ghostp2_master` (en StrongBox si el dispositivo lo tiene y la
 * clave supera una prueba de cifrado).
 *
 * Devuelve un [RecoverableAead]: la clave se abre en el primer uso y las decisiones (abrir, crear,
 * rechazar) están en [planKeyOpen] y [classifyOpenFailure], probadas en la JVM. [KeystoreKeyBackend]
 * no tiene pruebas unitarias (Robolectric no emula Android Keystore) y por eso es mínimo.
 *
 * `dataExists` indica si hay datos cifrados con la clave actual (`ghostp2.bin`): con datos, nunca se
 * genera un keyset nuevo; el fallo se informa como [StoreCorruptedException].
 */
object KeystoreAead {
    /** Devuelve un [RecoverableAead]; su `forceReset()` borra keyset, marca y clave maestra. */
    fun create(context: Context, dataExists: () -> Boolean): Aead =
        RecoverableAead(dataExists, KeystoreKeyBackend(context.applicationContext))
}

/** Acceso a la clave del almacenamiento; separado para probar [RecoverableAead] sin Keystore. */
internal interface KeyBackend {
    /** Hay un keyset guardado (válido o no). */
    fun keysetStored(): Boolean

    /** El keyset guardado se confirmó envuelto por Keystore al crearlo (marca escrita tras comprobarlo). */
    fun keysetConfirmed(): Boolean

    /**
     * La clave maestra existe en Keystore; `null` si no se puede saber. Un `false` puede ser falso: ver
     * [confirmedMasterKeyPresence].
     */
    fun masterKeyPresent(): Boolean?

    /** Abre el keyset guardado; nunca borra ni genera nada. */
    fun open(): Aead

    /** Borra keyset, marca y clave maestra y crea otros; solo cuando no hay datos que dependan de ellos. */
    fun generate(): Aead

    /** Borra keyset, marca y clave maestra. */
    fun reset()
}

internal enum class KeyPlan { OPEN, GENERATE, DATA_WITHOUT_KEY }

/**
 * Qué hacer con la clave. Un keyset sin marca de confirmación (p. ej. el keyset en claro que Tink escribe
 * si Keystore falla, o uno a medio crear) no es de fiar: nunca se usa. Sin keyset de fiar y con datos,
 * los datos son irrecuperables: no se genera una clave nueva encima. Un keyset en claro *con* marca solo
 * se rechaza si además falla `getAead` de la clave maestra (Tink lo lee entonces sin Keystore); fabricarlo
 * exige privilegios de la propia app y queda fuera del modelo de amenazas.
 */
internal fun planKeyOpen(keysetStored: Boolean, keysetConfirmed: Boolean, dataExists: Boolean): KeyPlan = when {
    keysetStored && keysetConfirmed -> KeyPlan.OPEN
    dataExists -> KeyPlan.DATA_WITHOUT_KEY
    else -> KeyPlan.GENERATE
}

internal enum class OpenFailure { KEY_LOST, TRANSIENT }

/**
 * Un keyset confirmado no se pudo abrir. Pérdida definitiva: Tink lo leyó sin Keystore
 * ([KeystoreNotUsedException]), etiqueta AEAD incorrecta (clave maestra distinta), clave maestra ausente
 * tras volver a comprobarlo ([confirmedMasterKeyPresence]) o keyset ilegible (IOException de Tink).
 * Cualquier otro error de Keystore se considera pasajero y se puede reintentar.
 */
internal fun classifyOpenFailure(error: Throwable, masterKeyPresent: Boolean?): OpenFailure = when {
    error is KeystoreNotUsedException -> OpenFailure.KEY_LOST
    generateSequence(error) { it.cause }.any { it is AEADBadTagException } -> OpenFailure.KEY_LOST
    masterKeyPresent == false -> OpenFailure.KEY_LOST
    error is IOException -> OpenFailure.KEY_LOST
    else -> OpenFailure.TRANSIENT
}

/**
 * Presencia de la clave maestra, con una segunda comprobación tras `wait` si la primera dice que falta.
 * En Keystore2 (AOSP `AndroidKeyStoreSpi.getKeyMetadata`), `containsAlias` devuelve `false` ante
 * *cualquier* `KeyStoreException` (la registra y devuelve null), no solo ante KEY_NOT_FOUND: un fallo
 * pasajero parece una clave borrada. Solo dos "falta" seguidos cuentan como clave perdida.
 */
internal fun confirmedMasterKeyPresence(check: () -> Boolean?, wait: () -> Unit): Boolean? {
    val first = check()
    if (first != false) return first
    wait()
    return check()
}

/** Pausa antes de volver a comprobar la clave maestra. */
internal const val MASTER_KEY_RECHECK_MILLIS = 300L

/** Causa de un almacenamiento cuya clave ya no existe o no es de fiar. */
internal class KeyLostException : Exception("Clave del almacenamiento perdida")

/** Tink abrió el keyset sin Android Keystore (lo leyó en claro): no se usa. */
internal class KeystoreNotUsedException : GeneralSecurityException("Keyset no envuelto por Android Keystore")

/**
 * [Aead] que abre su clave en el primer uso (no al arrancar la app) y no la guarda si falla, así que un
 * "Reintentar" vuelve a probar. Sus fallos no son [GeneralSecurityException]: salen tal cual de
 * [EncryptedStore] como [StoreCorruptedException] (clave perdida con datos) o [StoreUnavailableException]
 * (error pasajero de Keystore o al crear la clave).
 */
internal class RecoverableAead(
    private val dataExists: () -> Boolean,
    private val backend: KeyBackend,
    private val recheckWait: () -> Unit = { Thread.sleep(MASTER_KEY_RECHECK_MILLIS) },
) : Aead {
    private var delegate: Aead? = null

    @Synchronized
    private fun delegate(): Aead {
        delegate?.let { return it }
        val opened = when (planKeyOpen(backend.keysetStored(), backend.keysetConfirmed(), dataExists())) {
            KeyPlan.DATA_WITHOUT_KEY -> throw StoreCorruptedException(KeyLostException())
            KeyPlan.GENERATE -> generate()
            KeyPlan.OPEN -> try {
                backend.open()
            } catch (e: Exception) {
                val present = confirmedMasterKeyPresence(
                    check = { runCatching { backend.masterKeyPresent() }.getOrNull() },
                    wait = recheckWait,
                )
                when (classifyOpenFailure(e, present)) {
                    OpenFailure.TRANSIENT -> throw StoreUnavailableException(e)
                    OpenFailure.KEY_LOST ->
                        if (dataExists()) throw StoreCorruptedException(KeyLostException()) else generate()
                }
            }
        }
        delegate = opened
        return opened
    }

    /**
     * Borrado forzado de la clave (keyset, marca y clave maestra), sea cual sea su estado, y olvido de la
     * clave abierta: la próxima operación genera otra. Solo junto con el borrado de los datos.
     */
    @Synchronized
    fun forceReset() {
        delegate = null
        backend.reset()
    }

    private fun generate(): Aead = try {
        backend.generate()
    } catch (e: Exception) {
        throw StoreUnavailableException(e)
    }

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        delegate().encrypt(plaintext, associatedData)

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
        delegate().decrypt(ciphertext, associatedData)
}

/** Implementación real con Tink `AndroidKeysetManager` y Android Keystore. */
internal class KeystoreKeyBackend(private val context: Context) : KeyBackend {
    private val prefs get() = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    override fun keysetStored() = prefs.contains(KEYSET_NAME)

    override fun keysetConfirmed() = prefs.getBoolean(CONFIRMED, false)

    override fun masterKeyPresent(): Boolean = AndroidKeystore.hasKey(MASTER_ALIAS)

    /**
     * Sin plantilla, para que Tink nunca genere un keyset aquí. Si Tink lo abrió sin Keystore (solo pasa
     * si `getAead` de la clave maestra falló y el keyset estaba en claro) se rechaza sin borrar nada.
     */
    override fun open(): Aead {
        val manager = build(withTemplate = false)
        if (!manager.isUsingKeystore) throw KeystoreNotUsedException()
        return manager.primitive()
    }

    override fun generate(): Aead {
        reset()
        generateStrongBoxMasterKeyIfPossible()
        val manager = build(withTemplate = true)
        if (!manager.isUsingKeystore) {
            // Tink cae a un keyset en claro si Keystore falla; no se admite (restricción global).
            reset()
            throw GeneralSecurityException("Android Keystore no disponible")
        }
        // La marca solo se escribe tras comprobar que el keyset está envuelto por Keystore.
        if (!prefs.edit().putBoolean(CONFIRMED, true).commit()) throw IOException("No se pudo confirmar la clave")
        return manager.primitive()
    }

    override fun reset() {
        context.deleteSharedPreferences(PREF_FILE)
        try {
            AndroidKeystore.deleteKey(MASTER_ALIAS)
        } catch (_: GeneralSecurityException) {
            // Sin Keystore no hay clave que borrar.
        } catch (_: ProviderException) {
        }
    }

    private fun build(withTemplate: Boolean): AndroidKeysetManager {
        registered
        val builder = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREF_FILE)
            .withMasterKeyUri(MASTER_KEY_URI)
        if (withTemplate) builder.withKeyTemplate(KeyTemplate.createFrom(PredefinedAeadParameters.AES256_GCM))
        return builder.build()
    }

    private fun AndroidKeysetManager.primitive(): Aead =
        keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)

    /**
     * Tink solo genera la clave maestra si no existe: si la creamos antes en StrongBox, la usa. Se prueba
     * con un cifrado y descifrado; si falla, se borra y Tink la genera en el TEE.
     */
    private fun generateStrongBoxMasterKeyIfPossible() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) return
        try {
            AndroidKeystore.generateNewKeyWithSpec(
                KeyGenParameterSpec.Builder(MASTER_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setIsStrongBoxBacked(true)
                    .build(),
            )
            val probe = byteArrayOf(1, 2, 3, 4)
            val aead = AndroidKeystore.getAead(MASTER_ALIAS)
            if (!aead.decrypt(aead.encrypt(probe, probe), probe).contentEquals(probe)) {
                throw GeneralSecurityException("Prueba de StrongBox fallida")
            }
        } catch (_: Exception) {
            // StrongBoxUnavailableException, ProviderException o prueba fallida: Tink usará el TEE.
            try {
                AndroidKeystore.deleteKey(MASTER_ALIAS)
            } catch (_: GeneralSecurityException) {
            } catch (_: ProviderException) {
            }
        }
    }

    internal companion object {
        const val KEYSET_NAME = "ghostp2_keyset"
        const val PREF_FILE = "ghostp2_keyset_prefs"
        const val CONFIRMED = "ghostp2_keyset_keystore_confirmed"
        const val MASTER_ALIAS = "ghostp2_master"
        const val MASTER_KEY_URI = "android-keystore://$MASTER_ALIAS"

        private val registered by lazy { AeadConfig.register() }
    }
}
