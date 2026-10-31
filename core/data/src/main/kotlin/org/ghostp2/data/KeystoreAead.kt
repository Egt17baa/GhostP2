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
import java.security.GeneralSecurityException
import java.security.ProviderException

/**
 * AEAD de producción: keyset Tink AES-256-GCM en las preferencias privadas `ghostp2_keyset`, cifrado con
 * la clave maestra `android-keystore://ghostp2_master` (en StrongBox si el dispositivo lo tiene).
 *
 * Sin pruebas unitarias: Robolectric no emula Android Keystore. Por eso es mínimo; la lógica que sí se
 * puede probar (crear perezosamente y recuperarse de una clave rota) está en [RecoverableAead].
 */
object KeystoreAead {
    private const val KEYSET_NAME = "ghostp2_keyset"
    private const val PREF_FILE = "ghostp2_keyset_prefs"
    private const val MASTER_ALIAS = "ghostp2_master"
    private const val MASTER_KEY_URI = "android-keystore://$MASTER_ALIAS"

    private val registered by lazy { AeadConfig.register() }

    /**
     * Lanza si el keyset existe pero no se puede descifrar (clave maestra perdida o inutilizable) o si
     * Android Keystore no está disponible: nunca se usa un keyset guardado en claro.
     */
    fun create(context: Context): Aead {
        registered
        val app = context.applicationContext
        if (!app.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE).contains(KEYSET_NAME)) {
            generateStrongBoxMasterKeyIfPossible(app)
        }
        val manager = AndroidKeysetManager.Builder()
            .withSharedPref(app, KEYSET_NAME, PREF_FILE)
            .withKeyTemplate(KeyTemplate.createFrom(PredefinedAeadParameters.AES256_GCM))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
        if (!manager.isUsingKeystore) {
            // Tink cae a un keyset en claro si Keystore falla; no se admite (restricción global).
            reset(app)
            throw GeneralSecurityException("Android Keystore no disponible")
        }
        return manager.keysetHandle.getPrimitive(RegistryConfiguration.get(), Aead::class.java)
    }

    /** Descarta keyset y clave maestra; solo cuando ya no queda ningún dato cifrado con ellos. */
    internal fun reset(context: Context) {
        context.applicationContext.deleteSharedPreferences(PREF_FILE)
        try {
            AndroidKeystore.deleteKey(MASTER_ALIAS)
        } catch (_: GeneralSecurityException) {
            // Sin Keystore no hay clave que borrar.
        }
    }

    /** Tink solo genera la clave maestra si no existe: si la creamos antes en StrongBox, la usa. */
    private fun generateStrongBoxMasterKeyIfPossible(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)) return
        try {
            if (AndroidKeystore.hasKey(MASTER_ALIAS)) return
            AndroidKeystore.generateNewKeyWithSpec(
                KeyGenParameterSpec.Builder(MASTER_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setIsStrongBoxBacked(true)
                    .build(),
            )
        } catch (_: GeneralSecurityException) {
            // Sin StrongBox utilizable: Tink genera la clave maestra en el TEE.
        } catch (_: ProviderException) {
            // StrongBoxUnavailableException y similares: mismo respaldo.
        }
    }
}

/**
 * [Aead] que crea su delegado en el primer uso (fuera del arranque de la app) y se recupera de una clave
 * rota solo cuando ya no hay datos que dependan de ella: si `create` falla y `dataExists()` es cierto,
 * el fallo se propaga (⇒ [StoreCorruptedException] al leer); tras "empezar de cero" (archivo borrado) se
 * descarta la clave con `resetKey` y se crea otra. Cualquier fallo sale como [GeneralSecurityException].
 */
internal class RecoverableAead(
    private val dataExists: () -> Boolean,
    private val resetKey: () -> Unit,
    private val create: () -> Aead,
) : Aead {
    private var delegate: Aead? = null

    @Synchronized
    private fun delegate(): Aead {
        delegate?.let { return it }
        val created = try {
            attempt()
        } catch (e: GeneralSecurityException) {
            if (dataExists()) throw e
            resetKey()
            attempt()
        }
        delegate = created
        return created
    }

    private fun attempt(): Aead = try {
        create()
    } catch (e: GeneralSecurityException) {
        throw e
    } catch (e: Exception) {
        throw GeneralSecurityException("No se pudo abrir la clave del almacenamiento", e)
    }

    override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        delegate().encrypt(plaintext, associatedData)

    override fun decrypt(ciphertext: ByteArray, associatedData: ByteArray): ByteArray =
        delegate().decrypt(ciphertext, associatedData)
}
