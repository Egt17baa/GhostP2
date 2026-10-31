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
    fun create(context: Context, dataExists: () -> Boolean): Aead =
        RecoverableAead(dataExists, KeystoreKeyBackend(context.applicationContext))
}

/** Acceso a la clave del almacenamiento; separado para probar [RecoverableAead] sin Keystore. */
internal interface KeyBackend {
    /** Hay un keyset guardado (válido o no). */
    fun keysetStored(): Boolean

    /** El keyset guardado se confirmó envuelto por Keystore al crearlo (marca escrita tras comprobarlo). */
    fun keysetConfirmed(): Boolean

    /** La clave maestra existe en Keystore; `null` si no se puede saber. */
    fun masterKeyPresent(): Boolean?

    /** Abre el keyset guardado. */
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
 * los datos son irrecuperables: no se genera una clave nueva encima.
 */
internal fun planKeyOpen(keysetStored: Boolean, keysetConfirmed: Boolean, dataExists: Boolean): KeyPlan = when {
    keysetStored && keysetConfirmed -> KeyPlan.OPEN
    dataExists -> KeyPlan.DATA_WITHOUT_KEY
    else -> KeyPlan.GENERATE
}

internal enum class OpenFailure { KEY_LOST, TRANSIENT }

/**
 * Un keyset confirmado no se pudo abrir. Pérdida definitiva: etiqueta AEAD incorrecta (clave maestra
 * distinta), clave maestra ausente o keyset ilegible (IOException de Tink). Cualquier otro error de
 * Keystore se considera pasajero y se puede reintentar.
 */
internal fun classifyOpenFailure(error: Throwable, masterKeyPresent: Boolean?): OpenFailure = when {
    generateSequence(error) { it.cause }.any { it is AEADBadTagException } -> OpenFailure.KEY_LOST
    masterKeyPresent == false -> OpenFailure.KEY_LOST
    error is IOException -> OpenFailure.KEY_LOST
    else -> OpenFailure.TRANSIENT
}

/** Causa de un almacenamiento cuya clave ya no existe o no es de fiar. */
internal class KeyLostException : Exception("Clave del almacenamiento perdida")

/**
 * [Aead] que abre su clave en el primer uso (no al arrancar la app) y no la guarda si falla, así que un
 * "Reintentar" vuelve a probar. Sus fallos no son [GeneralSecurityException]: salen tal cual de
 * [EncryptedStore] como [StoreCorruptedException] (clave perdida con datos) o [StoreUnavailableException]
 * (error pasajero de Keystore o al crear la clave).
 */
internal class RecoverableAead(
    private val dataExists: () -> Boolean,
    private val backend: KeyBackend,
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
                when (classifyOpenFailure(e, runCatching { backend.masterKeyPresent() }.getOrNull())) {
                    OpenFailure.TRANSIENT -> throw StoreUnavailableException(e)
                    OpenFailure.KEY_LOST ->
                        if (dataExists()) throw StoreCorruptedException(KeyLostException()) else generate()
                }
            }
        }
        delegate = opened
        return opened
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

    override fun open(): Aead = build().also { requireKeystore(it) }.primitive()

    override fun generate(): Aead {
        reset()
        generateStrongBoxMasterKeyIfPossible()
        val manager = build()
        requireKeystore(manager)
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
        }
    }

    private fun build(): AndroidKeysetManager {
        registered
        return AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, PREF_FILE)
            .withKeyTemplate(KeyTemplate.createFrom(PredefinedAeadParameters.AES256_GCM))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
    }

    /** Tink cae a un keyset en claro si Keystore falla; no se admite (restricción global). */
    private fun requireKeystore(manager: AndroidKeysetManager) {
        if (!manager.isUsingKeystore) {
            reset()
            throw GeneralSecurityException("Android Keystore no disponible")
        }
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

    private companion object {
        const val KEYSET_NAME = "ghostp2_keyset"
        const val PREF_FILE = "ghostp2_keyset_prefs"
        const val CONFIRMED = "ghostp2_keyset_keystore_confirmed"
        const val MASTER_ALIAS = "ghostp2_master"
        const val MASTER_KEY_URI = "android-keystore://$MASTER_ALIAS"

        val registered by lazy { AeadConfig.register() }
    }
}
