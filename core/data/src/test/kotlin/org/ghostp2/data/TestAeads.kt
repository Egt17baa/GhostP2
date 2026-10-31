package org.ghostp2.data

import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.aead.PredefinedAeadParameters

/** AEAD de Tink en memoria (AES-256-GCM) para pruebas: sin Android Keystore. */
internal object TestAeads {
    init {
        AeadConfig.register()
    }

    fun newAead(): Aead =
        KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM, RegistryConfiguration.get())
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
}
